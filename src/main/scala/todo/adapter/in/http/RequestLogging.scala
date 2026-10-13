package todo.adapter.in.http

import cats.data.Kleisli
import cats.effect.*
import org.http4s.HttpApp
import org.slf4j.LoggerFactory

object RequestLogging:

  private val logger = LoggerFactory.getLogger("todo.http.RequestLogging")

  def apply(app: HttpApp[IO]): HttpApp[IO] =
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
