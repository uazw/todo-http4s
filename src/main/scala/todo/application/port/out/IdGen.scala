package todo.application.port.out

import java.util.UUID

/** Outbound port: source of identifiers for newly created todos. */
trait IdGen[F[_]]:
  def next: F[UUID]
