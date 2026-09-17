package todo

import cats.data.Kleisli
import cats.effect.*
import com.comcast.ip4s.*
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import org.slf4j.LoggerFactory
import todo.config.AppConfig
import todo.db.{Database, HealthCheck, Migrator}
import todo.http.TodoRoutes
import todo.repository.DoobieTodoRepository
import todo.runtime.{Clock, IdGen}
import todo.service.TodoService

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
      service = TodoService.make[IO](repository, Clock.system[IO], IdGen.random[IO])
      routes = TodoRoutes[IO](service, HealthCheck.postgres[IO](xa)).routes
      httpApp = requestLogging(routes.orNotFound)
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

  /** One structured log line per request. Hand-rolled because http4s' own request logger needs a log4cats instance this
    * service has no other use for.
    */
  private def requestLogging(app: HttpApp[IO]): HttpApp[IO] =
    Kleisli { request =>
      IO.monotonic.flatMap { started =>
        app.run(request).attempt.flatMap { outcome =>
          IO.monotonic.flatMap { finished =>
            val elapsedMs = (finished - started).toMillis
            val line = outcome match
              case Right(response) => s"${request.method.name} ${request.uri} ${response.status.code} ${elapsedMs}ms"
              case Left(error) => s"${request.method.name} ${request.uri} FAILED ${elapsedMs}ms — ${error.getMessage}"
            IO(logger.info(line)) *> IO.fromEither(outcome)
          }
        }
      }
    }
