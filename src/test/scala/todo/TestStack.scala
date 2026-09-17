package todo

import cats.effect.{IO, Ref}
import java.time.{Duration, Instant}
import java.util.UUID
import todo.db.HealthCheck
import todo.http.TodoRoutes
import todo.repository.{InMemoryTodoRepository, TodoRepository}
import todo.runtime.{Clock, IdGen}
import todo.service.TodoService
import todo.domain.Todo

/** Wires the same object graph as `Main`, but with deterministic time and ids and no database. */
object TestStack:

  val Epoch: Instant = Instant.parse("2026-01-01T00:00:00Z")

  final case class Fixture(
      repository: TodoRepository[IO],
      service: TodoService[IO],
      routes: TodoRoutes[IO]
  ):
    def http = routes.routes.orNotFound

  def create(
      start: Instant = Epoch,
      step: Duration = Duration.ofSeconds(1),
      namespace: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
  ): IO[Fixture] =
    for
      state <- Ref.of[IO, Map[todo.domain.TodoId, Todo]](Map.empty)
      clock <- Clock.stepping[IO](start, step)
      ids <- IdGen.sequential[IO](namespace)
      repository = InMemoryTodoRepository[IO](state)
      service = TodoService.make[IO](repository, clock, ids)
    yield Fixture(repository, service, TodoRoutes[IO](service, HealthCheck.alwaysHealthy[IO]))
