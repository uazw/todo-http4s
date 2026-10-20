package todo.application

import cats.Monad
import cats.data.EitherT
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

  /** The one repeated step across use cases: resolve an id or fail the error channel with NotFound. */
  private def findOrFail(id: TodoId): EitherT[F, TodoError, Todo] =
    EitherT.fromOptionF(repository.find(id), TodoError.NotFound(id))

  def create(input: CreateTodo): Result[F, Todo] =
    (for
      valid <- EitherT.fromEither[F](Validation.createTodo(input))
      now <- EitherT.liftF(clock.now)
      uuid <- EitherT.liftF(ids.next)
      todo = Todo(
        id = TodoId(uuid),
        title = valid.title,
        description = valid.description,
        status = TodoStatus.Pending,
        dueAt = valid.dueAt,
        createdAt = now,
        updatedAt = now
      )
      saved <- EitherT.liftF(repository.insert(todo))
    yield saved).value

  def get(id: TodoId): Result[F, Todo] =
    findOrFail(id).value

  def list(filter: TodoFilter, limit: Int, offset: Int): Result[F, Page[Todo]] =
    val effectiveLimit = clampLimit(limit)
    val effectiveOffset = math.max(0, offset)
    (repository.list(filter, effectiveLimit, effectiveOffset), repository.count(filter))
      .mapN(Page(_, _, effectiveLimit, effectiveOffset))
      .map(_.asRight[TodoError])

  def update(id: TodoId, patch: UpdateTodo): Result[F, Todo] =
    (for
      existing <- findOrFail(id)
      patched <- EitherT.fromEither[F](Validation.patch(existing, patch))
      now <- EitherT.liftF(clock.now)
      saved <- EitherT.liftF(repository.replace(patched.copy(updatedAt = now)))
      todo <- EitherT.fromOption[F](saved, TodoError.NotFound(id))
    yield todo).value

  def delete(id: TodoId): Result[F, Unit] =
    EitherT
      .liftF(repository.delete(id))
      .flatMap(removed => EitherT.fromEither[F](Either.cond(removed, (), TodoError.NotFound(id))))
      .value

object TodoServiceImpl:

  def apply[F[_]: Monad](repository: TodoRepository[F], clock: Clock[F], ids: IdGen[F]): TodoService[F] =
    new TodoServiceImpl[F](repository, clock, ids)

  /** `Page` bounds are clamped rather than rejected — a bad query string should not 400. */
  private def clampLimit(limit: Int): Int = math.max(1, math.min(limit, TodoService.MaxLimit))
