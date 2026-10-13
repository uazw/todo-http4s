package todo.adapter.in.http

import cats.effect.Async
import org.http4s.*
import org.http4s.dsl.Http4sDsl

final class DocsRoutes[F[_]: Async] extends Http4sDsl[F]:

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ GET -> Root / "docs" =>
      StaticFile.fromResource[F]("/swagger-ui/index.html", Some(req)).getOrElseF(NotFound())

    case req @ GET -> Root / "openapi.yaml" =>
      StaticFile.fromResource[F]("/openapi.yaml", Some(req)).getOrElseF(NotFound())
  }

object DocsRoutes:
  def apply[F[_]: Async]: DocsRoutes[F] = new DocsRoutes[F]
