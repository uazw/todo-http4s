package todo.adapter.out.fake

import cats.Applicative
import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import java.time.{Duration, Instant}
import todo.application.port.out.Clock

object FakeClock:

  def fixed[F[_]: Applicative](instant: Instant): Clock[F] = new Clock[F]:
    def now: F[Instant] = Applicative[F].pure(instant)

  /** Advances by `step` on every call — handy for asserting on `updatedAt`. */
  def stepping[F[_]: Sync](start: Instant, step: Duration): F[Clock[F]] =
    Ref[F]
      .of(0L)
      .map: counter =>
        new Clock[F]:
          def now: F[Instant] =
            counter.getAndUpdate(_ + 1L).map(offset => start.plus(step.multipliedBy(offset)))
