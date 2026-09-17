package todo.service

import cats.Monad
import cats.syntax.all.*
import todo.domain.*
import todo.repository.TodoRepository
import todo.runtime.{Clock, IdGen}

/**
 * Use-case algebra. This is what the HTTP layer talks to; it knows nothing
 * about HTTP, and the repository knows nothing about use cases.
 */
trait TodoService[F[_]]:

  def create(input: CreateTodo): Result[F, Todo]

  def get(id: TodoId): Result[F, Todo]

  def list(filter: TodoFilter, limit: Int, offset: Int): Result[F, Page[Todo]]

  def update(id: TodoId, patch: UpdateTodo): Result[F, Todo]

  def delete(id: TodoId): Result[F, Unit]

object TodoService:

  val DefaultLimit: Int = 20
  val MaxLimit: Int     = 100

  /** `Page` bounds are clamped rather than rejected — a bad query string should not 400. */
  private def clampLimit(limit: Int): Int = math.max(1, math.min(limit, MaxLimit))

  def make[F[_]: Monad](
      repository: TodoRepository[F],
      clock: Clock[F],
      ids: IdGen[F]
  ): TodoService[F] = new TodoService[F]:

    def create(input: CreateTodo): Result[F, Todo] =
      Validation.createTodo(input) match
        case Left(error) => Result.raise(error)
        case Right(valid) =>
          for
            now  <- clock.now
            uuid <- ids.next
            todo = Todo(
                     id = TodoId(uuid),
                     title = valid.title,
                     description = valid.description,
                     status = TodoStatus.Pending,
                     dueAt = valid.dueAt,
                     createdAt = now,
                     updatedAt = now
                   )
            saved <- repository.insert(todo)
          yield Right(saved)

    def get(id: TodoId): Result[F, Todo] =
      repository.find(id).map(_.toRight(TodoError.NotFound(id)))

    def list(filter: TodoFilter, limit: Int, offset: Int): Result[F, Page[Todo]] =
      val effectiveLimit  = clampLimit(limit)
      val effectiveOffset = math.max(0, offset)
      for
        items <- repository.list(filter, effectiveLimit, effectiveOffset)
        total <- repository.count(filter)
      yield Right(Page(items, total, effectiveLimit, effectiveOffset))

    def update(id: TodoId, patch: UpdateTodo): Result[F, Todo] =
      repository.find(id).flatMap {
        case None => Result.raise(TodoError.NotFound(id))
        case Some(existing) =>
          Validation.patch(existing, patch) match
            case Left(error) => Result.raise(error)
            case Right(patched) =>
              for
                now   <- clock.now
                saved <- repository.replace(patched.copy(updatedAt = now))
              yield saved.toRight(TodoError.NotFound(id))
      }

    def delete(id: TodoId): Result[F, Unit] =
      repository.delete(id).map(removed => Either.cond(removed, (), TodoError.NotFound(id)))
