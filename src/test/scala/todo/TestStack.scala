package todo

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import java.time.{Duration, Instant}
import java.util.UUID
import org.http4s.HttpApp
import todo.adapter.in.http.{DocsRoutes, HealthRoutes, TodoRoutes}
import todo.adapter.out.fake.{FakeClock, FakeIdGen, InMemoryTodoRepository}
import todo.application.TodoServiceImpl
import todo.application.port.in.TodoService
import todo.application.port.out.TodoRepository
import todo.domain.Todo

/** Wires the test stack with in-memory adapters and deterministic time/ids. */
object TestStack:

  val Epoch: Instant = Instant.parse("2026-01-01T00:00:00Z")

  final case class Fixture(
      repository: TodoRepository[IO],
      service: TodoService[IO],
      todoRoutes: TodoRoutes[IO],
      healthRoutes: HealthRoutes[IO],
      docsRoutes: DocsRoutes[IO]
  ):
    def http: HttpApp[IO] = (docsRoutes.routes <+> healthRoutes.routes <+> todoRoutes.routes).orNotFound

  def create(
      start: Instant = Epoch,
      step: Duration = Duration.ofSeconds(1),
      namespace: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
      isHealthy: IO[Boolean] = IO.pure(true)
  ): IO[Fixture] =
    for
      state <- Ref.of[IO, Map[todo.domain.TodoId, Todo]](Map.empty)
      clock <- FakeClock.stepping[IO](start, step)
      ids <- FakeIdGen.sequential[IO](namespace)
      repository = InMemoryTodoRepository[IO](state)
      service = TodoServiceImpl[IO](repository, clock, ids)
      todoRoutes = TodoRoutes[IO](service)
      healthRoutes = HealthRoutes[IO](isHealthy)
      docsRoutes = DocsRoutes[IO]
    yield Fixture(repository, service, todoRoutes, healthRoutes, docsRoutes)
