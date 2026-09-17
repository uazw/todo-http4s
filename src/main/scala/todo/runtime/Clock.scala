package todo.runtime

import cats.Applicative
import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import java.time.{Duration, Instant}
import java.util.UUID

/** Effectful notion of "now", so the service can be tested without freezing time globally. */
trait Clock[F[_]]:
  def now: F[Instant]

object Clock:

  def system[F[_]: Sync]: Clock[F] = new Clock[F]:
    def now: F[Instant] = Sync[F].delay(Instant.now())

  def fixed[F[_]: Applicative](instant: Instant): Clock[F] = new Clock[F]:
    def now: F[Instant] = Applicative[F].pure(instant)

  /** Advances by `step` on every call — handy for asserting on `updatedAt`. */
  def stepping[F[_]: Sync](start: Instant, step: Duration): F[Clock[F]] =
    Ref[F].of(0L).map { counter =>
      new Clock[F]:
        def now: F[Instant] =
          counter.getAndUpdate(_ + 1L).map(offset => start.plus(step.multipliedBy(offset)))
    }

/** Source of identifiers for newly created todos. */
trait IdGen[F[_]]:
  def next: F[UUID]

object IdGen:

  def random[F[_]: Sync]: IdGen[F] = new IdGen[F]:
    def next: F[UUID] = Sync[F].delay(UUID.randomUUID())

  /** Deterministic UUIDs derived from `namespace` + a monotonic counter. */
  def sequential[F[_]: Sync](namespace: UUID): F[IdGen[F]] =
    Ref[F].of(0L).map { counter =>
      new IdGen[F]:
        def next: F[UUID] =
          counter.getAndUpdate(_ + 1L).map(n => new UUID(namespace.getMostSignificantBits, n))
    }
