package todo.application

import cats.Monad
import cats.syntax.all.*
import todo.application.port.in.{Result, TodoService}
import todo.application.port.out.{Clock, IdGen, TodoRepository}
import todo.domain.*

/** Application service: implements the inbound [[TodoService]] port purely in terms of the outbound ports. */
final class TodoServiceImpl[F[_]: Monad](
    repository: TodoRepository[F],
    clock: Clock[F],
    ids: IdGen[F]
) extends TodoService[F]:

  import TodoServiceImpl.clampLimit

  def create(input: CreateTodo): Result[F, Todo] =
    Validation.createTodo(input) match
      case Left(error)  => Result.raise(error)
      case Right(valid) =>
        for
          now <- clock.now
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
    val effectiveLimit = clampLimit(limit)
    val effectiveOffset = math.max(0, offset)
    for
      items <- repository.list(filter, effectiveLimit, effectiveOffset)
      total <- repository.count(filter)
    yield Right(Page(items, total, effectiveLimit, effectiveOffset))

  def update(id: TodoId, patch: UpdateTodo): Result[F, Todo] =
    repository.find(id).flatMap {
      case None           => Result.raise(TodoError.NotFound(id))
      case Some(existing) =>
        Validation.patch(existing, patch) match
          case Left(error)    => Result.raise(error)
          case Right(patched) =>
            for
              now <- clock.now
              saved <- repository.replace(patched.copy(updatedAt = now))
            yield saved.toRight(TodoError.NotFound(id))
    }

  def delete(id: TodoId): Result[F, Unit] =
    repository.delete(id).map(removed => Either.cond(removed, (), TodoError.NotFound(id)))

object TodoServiceImpl:

  def apply[F[_]: Monad](repository: TodoRepository[F], clock: Clock[F], ids: IdGen[F]): TodoService[F] =
    new TodoServiceImpl[F](repository, clock, ids)

  /** `Page` bounds are clamped rather than rejected — a bad query string should not 400. */
  private def clampLimit(limit: Int): Int = math.max(1, math.min(limit, TodoService.MaxLimit))
