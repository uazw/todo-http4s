package todo.domain

/** Every failure the service can express on purpose. Infrastructure failures are *not* modelled here — they surface as
  * effect errors and are turned into a 500 at the edge.
  */
sealed abstract class TodoError(val message: String) extends Product with Serializable

object TodoError:

  final case class NotFound(id: TodoId) extends TodoError(s"todo '${id.value}' was not found")

  final case class Invalid(field: String, reason: String) extends TodoError(s"invalid '$field': $reason")

  final case class Storage(reason: String) extends TodoError(s"storage failure: $reason")
