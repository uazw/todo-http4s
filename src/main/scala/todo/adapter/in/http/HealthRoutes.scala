package todo.adapter.in.http

import cats.effect.Concurrent
import cats.syntax.all.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.Http4sDsl

final class HealthRoutes[F[_]: Concurrent](isHealthy: F[Boolean]) extends Http4sDsl[F]:

  val routes: HttpRoutes[F] = HttpRoutes.of[F] { case GET -> Root / "health" =>
    isHealthy.flatMap { databaseUp =>
      val body = HealthView(status = "ok", database = if databaseUp then "up" else "down")
      if databaseUp then Ok(body) else ServiceUnavailable(body)
    }
  }

object HealthRoutes:
  def apply[F[_]: Concurrent](isHealthy: F[Boolean]): HealthRoutes[F] =
    new HealthRoutes[F](isHealthy)
