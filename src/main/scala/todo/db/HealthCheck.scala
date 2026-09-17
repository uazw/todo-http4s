package todo.db

import cats.Applicative
import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*

/** Liveness of a dependency, reported by `GET /health`. */
trait HealthCheck[F[_]]:
  def isHealthy: F[Boolean]

object HealthCheck:

  def alwaysHealthy[F[_]: Applicative]: HealthCheck[F] = new HealthCheck[F]:
    def isHealthy: F[Boolean] = Applicative[F].pure(true)

  def postgres[F[_]: MonadCancelThrow](xa: Transactor[F]): HealthCheck[F] = new HealthCheck[F]:
    def isHealthy: F[Boolean] =
      sql"SELECT 1".query[Int].unique.transact(xa).map(_ == 1).handleError(_ => false)
