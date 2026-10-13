package todo.adapter.out.system

import cats.effect.Sync
import java.util.UUID
import todo.application.port.out.IdGen

final class RandomIdGen[F[_]: Sync] extends IdGen[F]:
  def next: F[UUID] = Sync[F].delay(UUID.randomUUID())

object RandomIdGen:
  def apply[F[_]: Sync]: IdGen[F] = new RandomIdGen[F]
