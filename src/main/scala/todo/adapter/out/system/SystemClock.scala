package todo.adapter.out.system

import cats.effect.Sync
import java.time.Instant
import todo.application.port.out.Clock

final class SystemClock[F[_]: Sync] extends Clock[F]:
  def now: F[Instant] = Sync[F].delay(Instant.now())

object SystemClock:
  def apply[F[_]: Sync]: Clock[F] = new SystemClock[F]
