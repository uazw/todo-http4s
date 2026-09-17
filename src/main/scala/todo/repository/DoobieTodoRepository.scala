package todo.repository

import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.time.Instant
import java.util.UUID
import todo.domain.*

/** doobie interpreter of [[TodoRepository]].
  *
  * The row type is mapped once, in the `Read[Todo]` given below, and every query selects the same column list, so
  * adding a column is a single-place change.
  */
final class DoobieTodoRepository[F[_]: MonadCancelThrow](xa: Transactor[F]) extends TodoRepository[F]:

  private val columns: Fragment = fr"id, title, description, status, due_at, created_at, updated_at"

  /** `doobie.postgres.implicits` maps java.time types through the driver's own `setObject`/`getObject` support, so
    * `Instant` reaches `TIMESTAMPTZ` as an absolute instant. Routing it through `java.sql.Timestamp` instead would
    * reinterpret it in the JVM's default zone — a bug that only shows up in production, on a machine that is not in
    * UTC.
    */
  private given Read[Todo] =
    Read[(UUID, String, Option[String], String, Option[Instant], Instant, Instant)].map {
      case (id, title, description, status, dueAt, createdAt, updatedAt) =>
        Todo(
          id = TodoId(id),
          title = title,
          description = description,
          status = TodoStatus.fromWire(status).getOrElse(TodoStatus.Pending),
          dueAt = dueAt,
          createdAt = createdAt,
          updatedAt = updatedAt
        )
    }

  private def where(filter: TodoFilter): Fragment =
    Fragments.whereAndOpt(
      filter.status.map(s => fr"status = ${TodoStatus.toWire(s)}"),
      filter.search.filter(_.trim.nonEmpty).map(q => fr"(title ILIKE ${like(q)} OR description ILIKE ${like(q)})"),
      filter.dueBefore.map(d => fr"due_at <= $d")
    )

  /** `%` and `_` typed by a user must match literally, not as wildcards. */
  private def like(raw: String): String =
    "%" + raw.trim.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

  def insert(todo: Todo): F[Todo] =
    sql"""INSERT INTO todos ($columns)
          VALUES (${todo.id.value}, ${todo.title}, ${todo.description},
                  ${TodoStatus.toWire(todo.status)}, ${todo.dueAt},
                  ${todo.createdAt}, ${todo.updatedAt})""".update.run
      .transact(xa)
      .as(todo)

  def find(id: TodoId): F[Option[Todo]] =
    (fr"SELECT" ++ columns ++ fr"FROM todos WHERE id = ${id.value}")
      .query[Todo]
      .option
      .transact(xa)

  def list(filter: TodoFilter, limit: Int, offset: Int): F[List[Todo]] =
    (fr"SELECT" ++ columns ++ fr"FROM todos" ++ where(filter) ++
      fr"ORDER BY created_at DESC, id ASC LIMIT $limit OFFSET $offset")
      .query[Todo]
      .to[List]
      .transact(xa)

  def count(filter: TodoFilter): F[Long] =
    (fr"SELECT count(*) FROM todos" ++ where(filter))
      .query[Long]
      .unique
      .transact(xa)

  def replace(todo: Todo): F[Option[Todo]] =
    sql"""UPDATE todos
          SET title = ${todo.title},
              description = ${todo.description},
              status = ${TodoStatus.toWire(todo.status)},
              due_at = ${todo.dueAt},
              updated_at = ${todo.updatedAt}
          WHERE id = ${todo.id.value}""".update.run
      .transact(xa)
      .map(rows => Option.when(rows > 0)(todo))

  def delete(id: TodoId): F[Boolean] =
    sql"DELETE FROM todos WHERE id = ${id.value}".update.run
      .transact(xa)
      .map(_ > 0)

object DoobieTodoRepository:

  def apply[F[_]: MonadCancelThrow](xa: Transactor[F]): TodoRepository[F] =
    new DoobieTodoRepository[F](xa)
