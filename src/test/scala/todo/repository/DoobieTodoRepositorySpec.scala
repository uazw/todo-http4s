package todo.repository

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import doobie.Transactor
import doobie.implicits.*
import java.time.{Instant, ZoneOffset}
import java.util.UUID
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import todo.config.DatabaseConfig
import todo.db.{Database, Migrator}
import todo.domain.*

/** Integration spec for the doobie interpreter against a real PostgreSQL.
  *
  * Start one with `docker compose up -d postgres-test`, then run `TODO_TEST_DB=1 sbt test`. Without that env var the
  * suite is skipped so a plain `sbt test` stays hermetic.
  */
class DoobieTodoRepositorySpec extends CatsEffectSuite:

  override def munitIgnore: Boolean = !sys.env.get("TODO_TEST_DB").exists(_.nonEmpty)

  private val config = DatabaseConfig(
    host = sys.env.getOrElse("TODO_TEST_DB_HOST", "localhost"),
    port = sys.env.get("TODO_TEST_DB_PORT").flatMap(_.toIntOption).getOrElse(55432),
    name = sys.env.getOrElse("TODO_TEST_DB_NAME", "todo_test"),
    user = sys.env.getOrElse("TODO_TEST_DB_USER", "todo"),
    password = sys.env.getOrElse("TODO_TEST_DB_PASSWORD", "todo"),
    poolSize = 4,
    connectionTimeout = 5.seconds
  )

  private val transactor: Resource[IO, Transactor[IO]] = Database.transactor[IO](config)

  /** Fresh schema per test — the table is truncated, not the database dropped. */
  private def withRepository[A](body: TodoRepository[IO] => IO[A]): IO[A] =
    transactor.use { xa =>
      Migrator.run[IO](xa) *>
        sql"TRUNCATE todos".update.run.transact(xa) *>
        body(DoobieTodoRepository[IO](xa))
    }

  private def todo(
      title: String,
      status: TodoStatus = TodoStatus.Pending,
      createdAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
      dueAt: Option[Instant] = None,
      description: Option[String] = None
  ): Todo =
    Todo(TodoId(UUID.randomUUID()), title, description, status, dueAt, createdAt, createdAt)

  test("insert then find round-trips every column, including timestamps with sub-second precision") {
    withRepository { repository =>
      val created = todo("round trip", description = Some("with details")).copy(
        dueAt = Some(Instant.parse("2026-02-14T08:30:15.123456Z"))
      )
      for
        _ <- repository.insert(created)
        loaded <- repository.find(created.id)
      yield
        assertEquals(loaded, Some(created))
        assertEquals(loaded.flatMap(_.dueAt).map(_.getNano), Some(123456000))
    }
  }

  test("find returns None for an id that was never stored") {
    withRepository { repository =>
      repository.find(TodoId(UUID.randomUUID())).map(loaded => assertEquals(loaded, None))
    }
  }

  test("timestamps survive a JVM in a non-UTC zone") {
    withRepository { repository =>
      val instant = Instant.parse("2026-07-04T23:45:00Z")
      val stored = todo("timezone", createdAt = instant).copy(dueAt = Some(instant))
      for
        _ <- repository.insert(stored)
        loaded <- repository.find(stored.id)
      yield
        assertEquals(loaded.map(_.createdAt), Some(instant))
        assertEquals(loaded.flatMap(_.dueAt), Some(instant))
        assertEquals(loaded.map(_.createdAt.atOffset(ZoneOffset.UTC)), Some(instant.atOffset(ZoneOffset.UTC)))
    }
  }

  test("list orders newest first and paginates") {
    withRepository { repository =>
      val base = Instant.parse("2026-01-01T00:00:00Z")
      val todos = (1 to 5).toList.map(i => todo(s"item $i", createdAt = base.plusSeconds(i.toLong)))
      for
        _ <- todos.traverse_(repository.insert)
        first <- repository.list(TodoFilter.all, limit = 2, offset = 0)
        next <- repository.list(TodoFilter.all, limit = 2, offset = 2)
        total <- repository.count(TodoFilter.all)
      yield
        assertEquals(first.map(_.title), List("item 5", "item 4"))
        assertEquals(next.map(_.title), List("item 3", "item 2"))
        assertEquals(total, 5L)
    }
  }

  test("filters by status, due date and free text") {
    withRepository { repository =>
      val base = Instant.parse("2026-01-01T00:00:00Z")
      for
        _ <- repository.insert(
          todo("buy milk", TodoStatus.Pending, base, Some(base.plusSeconds(60)), Some("corner shop"))
        )
        _ <- repository.insert(todo("buy bread", TodoStatus.Done, base.plusSeconds(1)))
        _ <- repository.insert(todo("call dentist", TodoStatus.Done, base.plusSeconds(2)))
        pendings <- repository.list(TodoFilter(status = Some(TodoStatus.Pending)), 10, 0)
        search <- repository.list(TodoFilter(search = Some("BUY")), 10, 0)
        byDescription <- repository.list(TodoFilter(search = Some("corner")), 10, 0)
        due <- repository.list(TodoFilter(dueBefore = Some(base.plusSeconds(120))), 10, 0)
      yield
        assertEquals(pendings.map(_.title), List("buy milk"))
        assertEquals(search.map(_.title).sorted, List("buy bread", "buy milk"))
        assertEquals(byDescription.map(_.title), List("buy milk"))
        assertEquals(due.map(_.title), List("buy milk"))
    }
  }

  test("a literal % in the search term is not a wildcard") {
    withRepository { repository =>
      for
        _ <- repository.insert(todo("100% done"))
        _ <- repository.insert(todo("nothing here"))
        hits <- repository.list(TodoFilter(search = Some("100%")), 10, 0)
        misses <- repository.list(TodoFilter(search = Some("%")), 10, 0)
      yield
        assertEquals(hits.map(_.title), List("100% done"))
        assertEquals(misses.map(_.title), List("100% done"))
    }
  }

  test("replace updates in place and reports a missing row") {
    withRepository { repository =>
      val original = todo("original")
      val edited =
        original.copy(title = "edited", status = TodoStatus.Done, dueAt = Some(Instant.parse("2026-05-05T05:05:05Z")))
      for
        _ <- repository.insert(original)
        updated <- repository.replace(edited)
        loaded <- repository.find(original.id)
        missing <- repository.replace(todo("ghost"))
      yield
        assertEquals(updated, Some(edited))
        assertEquals(loaded, Some(edited))
        assertEquals(missing, None)
    }
  }

  test("delete reports whether a row was removed") {
    withRepository { repository =>
      val stored = todo("temporary")
      for
        _ <- repository.insert(stored)
        first <- repository.delete(stored.id)
        second <- repository.delete(stored.id)
        remaining <- repository.count(TodoFilter.all)
      yield
        assertEquals(first, true)
        assertEquals(second, false)
        assertEquals(remaining, 0L)
    }
  }

  test("a duplicate id is an infrastructure failure, not a silent overwrite") {
    withRepository { repository =>
      val stored = todo("unique")
      repository.insert(stored) *> repository.insert(stored).attempt.map { outcome =>
        assert(outcome.isLeft, "inserting the same primary key twice must fail")
      }
    }
  }

  test("schema bootstrap is idempotent") {
    withRepository { repository =>
      // `withRepository` has already applied the schema once; applying it again
      // must not fail on the existing table or indexes.
      transactor.use { xa =>
        Migrator.run[IO](xa) *> Migrator
          .run[IO](xa) *> repository.count(TodoFilter.all).map(total => assertEquals(total, 0L))
      }
    }
  }
