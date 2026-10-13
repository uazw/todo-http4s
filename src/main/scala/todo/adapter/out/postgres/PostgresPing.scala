package todo.adapter.out.postgres

import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*

object PostgresPing:
  def apply[F[_]: MonadCancelThrow](xa: Transactor[F]): F[Boolean] =
    sql"SELECT 1".query[Int].unique.transact(xa).map(_ == 1).handleError(_ => false)
