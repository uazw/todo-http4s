package todo.adapter.out.fake

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import java.util.UUID
import todo.application.port.out.IdGen

object FakeIdGen:

  /** Deterministic UUIDs derived from `namespace` + a monotonic counter. */
  def sequential[F[_]: Sync](namespace: UUID): F[IdGen[F]] =
    Ref[F].of(0L).map { counter =>
      new IdGen[F]:
        def next: F[UUID] =
          counter.getAndUpdate(_ + 1L).map(n => new UUID(namespace.getMostSignificantBits, n))
    }
