package todo.application.port.out

import java.time.Instant

/** Outbound port: effectful notion of "now", so the service can be tested without freezing time globally. */
trait Clock[F[_]]:
  def now: F[Instant]
