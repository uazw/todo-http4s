package todo.domain

import java.time.Instant
import java.util.UUID

/** Lifecycle state of a todo item. */
enum TodoStatus:
  case Pending, InProgress, Done

object TodoStatus:

  /** Canonical representation used on the wire and in the database. */
  def toWire(status: TodoStatus): String = status match
    case Pending    => "pending"
    case InProgress => "in_progress"
    case Done       => "done"

  /** Tolerant parser: accepts `in-progress`, `In Progress`, `completed`, ... */
  def fromWire(value: String): Option[TodoStatus] =
    value.trim.toLowerCase.replace('-', '_').replace(' ', '_') match
      case "pending"                         => Some(Pending)
      case "in_progress" | "inprogress"      => Some(InProgress)
      case "done" | "complete" | "completed" => Some(Done)
      case _                                 => None

  val all: List[TodoStatus] = List(Pending, InProgress, Done)

/** Newtype over the surrogate key so a raw `UUID` can never be passed by mistake. */
final case class TodoId(value: UUID)

object TodoId:
  def fromString(raw: String): Option[TodoId] =
    scala.util.Try(UUID.fromString(raw.trim)).toOption.map(TodoId.apply)

/** A persisted todo item. */
final case class Todo(
    id: TodoId,
    title: String,
    description: Option[String],
    status: TodoStatus,
    dueAt: Option[Instant],
    createdAt: Instant,
    updatedAt: Instant
)

/** Payload accepted when creating a todo. */
final case class CreateTodo(
    title: String,
    description: Option[String] = None,
    dueAt: Option[Instant] = None
)

/** Partial update. `None` means "leave this field alone"; the nested `Option` on `description`/`dueAt` distinguishes
  * "absent" from "explicitly set to null".
  */
final case class UpdateTodo(
    title: Option[String] = None,
    description: Option[Option[String]] = None,
    status: Option[TodoStatus] = None,
    dueAt: Option[Option[Instant]] = None
)

object UpdateTodo:
  val empty: UpdateTodo = UpdateTodo()

/** Query-side filter, shared by the repository and the service algebra. */
final case class TodoFilter(
    status: Option[TodoStatus] = None,
    search: Option[String] = None,
    dueBefore: Option[Instant] = None
)

object TodoFilter:
  val all: TodoFilter = TodoFilter()

/** A window over an ordered result set. */
final case class Page[A](items: List[A], total: Long, limit: Int, offset: Int)

object Page:
  def of[A](items: List[A], total: Long, limit: Int, offset: Int): Page[A] =
    Page(items, total, limit, offset)
