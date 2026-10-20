package todo.adapter.in.http

import cats.data.Kleisli
import cats.effect.Sync
import cats.syntax.all.*
import org.http4s.HttpApp
import org.http4s.Request
import org.http4s.Response
import org.slf4j.LoggerFactory

object RequestLogging:

  private val logger = LoggerFactory.getLogger("todo.http.RequestLogging")

  def apply[F[_]: Sync](app: HttpApp[F]): HttpApp[F] =
    Kleisli[F, Request[F], Response[F]]: request =>
      for
        started <- Sync[F].monotonic
        outcome <- app.run(request).attempt
        finished <- Sync[F].monotonic
        elapsedMs = (finished - started).toMillis
        line = outcome match
          case Right(response) => s"${request.method.name} ${request.uri} ${response.status.code} ${elapsedMs}ms"
          case Left(error)     => s"${request.method.name} ${request.uri} FAILED ${elapsedMs}ms — ${error.getMessage}"
        _ <- Sync[F].delay(logger.info(line))
        response <- Sync[F].fromEither(outcome)
      yield response
