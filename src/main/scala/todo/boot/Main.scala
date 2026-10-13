package todo.boot

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.slf4j.LoggerFactory
import todo.adapter.in.http.{DocsRoutes, HealthRoutes, RequestLogging, TodoRoutes}
import todo.adapter.out.postgres.{Database, DoobieTodoRepository, Migrator, PostgresPing}
import todo.adapter.out.system.{RandomIdGen, SystemClock}
import todo.application.TodoServiceImpl

object Main extends IOApp:

  private val logger = LoggerFactory.getLogger("todo.Main")

  def run(args: List[String]): IO[ExitCode] =
    AppConfig.load() match
      case Left(problem) =>
        IO(logger.error("startup aborted — {}", problem)).as(ExitCode(1))
      case Right(config) =>
        // `use_` would acquire and release immediately; the server has to stay
        // acquired until the process is signalled.
        application(config).useForever

  /** The whole object graph, expressed as a `Resource` so ordering and cleanup are explicit. */
  def application(config: AppConfig): Resource[IO, Unit] =
    for
      xa <- Database.transactor[IO](config.database)
      _ <- Resource.eval(IO.whenA(config.migrateOnStart)(Migrator.run[IO](xa)))
      _ <- Resource.eval(IO(logger.info("connected to {}", config.database.jdbcUrl)))
      repository = DoobieTodoRepository[IO](xa)
      service = TodoServiceImpl[IO](repository, SystemClock[IO], RandomIdGen[IO])
      routes = DocsRoutes[IO].routes <+>
        HealthRoutes[IO](PostgresPing[IO](xa)).routes <+>
        TodoRoutes[IO](service).routes
      httpApp = RequestLogging(routes.orNotFound)
      host = Host.fromString(config.http.host).getOrElse(host"0.0.0.0")
      port = Port.fromInt(config.http.port).getOrElse(port"8080")
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(host)
        .withPort(port)
        .withHttpApp(httpApp)
        .build
        .evalTap(_ => IO(logger.info("todo-service listening on http://{}:{}", host.toString, port.value.toString)))
    yield ()
