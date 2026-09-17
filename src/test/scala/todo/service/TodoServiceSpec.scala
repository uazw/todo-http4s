package todo.service

import cats.effect.IO
import java.time.{Duration, Instant}
import munit.CatsEffectSuite
import todo.TestStack
import todo.domain.*

class TodoServiceSpec extends CatsEffectSuite:

  private val start = Instant.parse("2026-03-01T09:00:00Z")

  private def fixture = TestStack.create(start = start, step = Duration.ofSeconds(30))

  test("create assigns an id, starts as pending and stamps both timestamps") {
    fixture.flatMap { f =>
      f.service.create(CreateTodo("  write the report  ")).map { result =>
        val todo = result.toOption.get
        assertEquals(todo.title, "write the report")
        assertEquals(todo.status, TodoStatus.Pending)
        assertEquals(todo.createdAt, start)
        assertEquals(todo.updatedAt, start)
        assertEquals(todo.description, None)
      }
    }
  }

  test("create rejects a blank title and a title past the limit") {
    fixture.flatMap { f =>
      for
        blank <- f.service.create(CreateTodo("   "))
        long  <- f.service.create(CreateTodo("x" * (Validation.MaxTitleLength + 1)))
      yield
        assertEquals(blank, Left(TodoError.Invalid("title", "must not be blank")))
        assert(long.isLeft)
    }
  }

  test("create collapses an empty description to None") {
    fixture.flatMap { f =>
      f.service.create(CreateTodo("ship it", Some("   "))).map { result =>
        assertEquals(result.toOption.get.description, None)
      }
    }
  }

  test("get reports NotFound for an unknown id") {
    fixture.flatMap { f =>
      f.service.get(TodoId(java.util.UUID.randomUUID())).map { result =>
        assert(result.left.exists(_.isInstanceOf[TodoError.NotFound]))
      }
    }
  }

  test("update patches only the fields present in the request") {
    fixture.flatMap { f =>
      for
        created <- f.service.create(CreateTodo("draft", Some("first pass")))
        id = created.toOption.get.id
        updated <- f.service.update(
                     id,
                     UpdateTodo(title = Some("final"), status = Some(TodoStatus.InProgress))
                   )
      yield
        val todo = updated.toOption.get
        assertEquals(todo.title, "final")
        assertEquals(todo.description, Some("first pass")) // untouched
        assertEquals(todo.status, TodoStatus.InProgress)
        assertEquals(todo.createdAt, start)
        assert(todo.updatedAt.isAfter(start), "updatedAt should be bumped")
    }
  }

  test("update distinguishes an absent field from an explicit null") {
    fixture.flatMap { f =>
      for
        created <- f.service.create(CreateTodo("draft", Some("first pass"), Some(start.plusSeconds(60))))
        id = created.toOption.get.id
        cleared  <- f.service.update(id, UpdateTodo(description = Some(None)))
        kept     <- f.service.update(id, UpdateTodo(title = Some("renamed")))
      yield
        assertEquals(cleared.toOption.get.description, None)
        assertEquals(cleared.toOption.get.dueAt, Some(start.plusSeconds(60)))
        assertEquals(kept.toOption.get.description, None)
        assertEquals(kept.toOption.get.dueAt, Some(start.plusSeconds(60)))
    }
  }

  test("update rejects an invalid patch and leaves the row alone") {
    fixture.flatMap { f =>
      for
        created <- f.service.create(CreateTodo("draft"))
        id = created.toOption.get.id
        failed <- f.service.update(id, UpdateTodo(title = Some("  ")))
        reloaded <- f.service.get(id)
      yield
        assertEquals(failed, Left(TodoError.Invalid("title", "must not be blank")))
        assertEquals(reloaded.toOption.get.title, "draft")
    }
  }

  test("update reports NotFound for an unknown id") {
    fixture.flatMap { f =>
      f.service.update(TodoId(java.util.UUID.randomUUID()), UpdateTodo(status = Some(TodoStatus.Done))).map { result =>
        assert(result.left.exists(_.isInstanceOf[TodoError.NotFound]))
      }
    }
  }

  test("delete removes the row and is NotFound the second time") {
    fixture.flatMap { f =>
      for
        created <- f.service.create(CreateTodo("obsolete"))
        id = created.toOption.get.id
        first  <- f.service.delete(id)
        second <- f.service.delete(id)
        gone   <- f.service.get(id)
      yield
        assertEquals(first, Right(()))
        assert(second.left.exists(_.isInstanceOf[TodoError.NotFound]))
        assert(gone.left.exists(_.isInstanceOf[TodoError.NotFound]))
    }
  }

  test("list filters, counts every match and pages the window") {
    fixture.flatMap { f =>
      for
        _ <- f.service.create(CreateTodo("buy milk", Some("from the corner shop")))
        _ <- f.service.create(CreateTodo("buy bread"))
        _ <- f.service.create(CreateTodo("call the dentist"))
        page <- f.service.list(TodoFilter(search = Some("buy")), limit = 1, offset = 0)
        all  <- f.service.list(TodoFilter.all, limit = 50, offset = 0)
        done <- f.service.list(TodoFilter(status = Some(TodoStatus.Done)), limit = 50, offset = 0)
      yield
        assertEquals(page.toOption.get.total, 2L) // total counts matches, not the page
        assertEquals(page.toOption.get.items.length, 1)
        assertEquals(all.toOption.get.total, 3L)
        assertEquals(done.toOption.get.items, Nil)
    }
  }

  test("list clamps an out-of-range limit instead of failing") {
    fixture.flatMap { f =>
      f.service.list(TodoFilter.all, limit = 5000, offset = -3).map { result =>
        assertEquals(result.toOption.get.limit, TodoService.MaxLimit)
        assertEquals(result.toOption.get.offset, 0)
      }
    }
  }
