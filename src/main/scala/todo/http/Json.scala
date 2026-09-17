package todo.http

import io.circe.{Decoder, DecodingFailure, Encoder, HCursor}
import io.circe.generic.semiauto.deriveEncoder
import java.time.Instant
import todo.domain.*

/** Outbound representation of a todo. */
final case class TodoView(
    id: String,
    title: String,
    description: Option[String],
    status: String,
    dueAt: Option[Instant],
    createdAt: Instant,
    updatedAt: Instant
)

object TodoView:
  given Encoder[TodoView] = deriveEncoder

  def from(todo: Todo): TodoView =
    TodoView(
      id = todo.id.value.toString,
      title = todo.title,
      description = todo.description,
      status = TodoStatus.toWire(todo.status),
      dueAt = todo.dueAt,
      createdAt = todo.createdAt,
      updatedAt = todo.updatedAt
    )

final case class PageView(items: List[TodoView], total: Long, limit: Int, offset: Int)

object PageView:
  given Encoder[PageView] = deriveEncoder

  def from(page: Page[Todo]): PageView =
    PageView(page.items.map(TodoView.from), page.total, page.limit, page.offset)

final case class CreateTodoRequest(
    title: String,
    description: Option[String] = None,
    dueAt: Option[Instant] = None
)

object CreateTodoRequest:
  given Decoder[CreateTodoRequest] = Decoder.instance { c =>
    for
      title       <- c.get[String]("title")
      description <- c.get[Option[String]]("description")
      dueAt       <- c.get[Option[Instant]]("dueAt")
    yield CreateTodoRequest(title, description, dueAt)
  }

  def toDomain(request: CreateTodoRequest): CreateTodo =
    CreateTodo(request.title, request.description, request.dueAt)

/**
 * PATCH body. Decoded by hand rather than derived so that a field which is
 * *absent* can be told apart from one explicitly set to `null`: absent leaves
 * the value alone, explicit null clears it.
 */
final case class PatchTodoRequest(
    title: Option[String] = None,
    description: Option[Option[String]] = None,
    status: Option[String] = None,
    dueAt: Option[Option[Instant]] = None
)

object PatchTodoRequest:

  private def patchField[A: Decoder](c: HCursor, name: String): Decoder.Result[Option[Option[A]]] =
    val field = c.downField(name)
    if field.succeeded then field.as[Option[A]].map(Some(_))
    else Right(None)

  given Decoder[PatchTodoRequest] = Decoder.instance { c =>
    for
      title       <- c.get[Option[String]]("title")
      description <- patchField[String](c, "description")
      status      <- c.get[Option[String]]("status")
      dueAt       <- patchField[Instant](c, "dueAt")
    yield PatchTodoRequest(title, description, status, dueAt)
  }

  def toDomain(request: PatchTodoRequest): Either[TodoError, UpdateTodo] =
    request.status match
      case None => Right(UpdateTodo(request.title, request.description, None, request.dueAt))
      case Some(raw) =>
        TodoStatus
          .fromWire(raw)
          .toRight(
            TodoError.Invalid("status", s"must be one of ${TodoStatus.all.map(TodoStatus.toWire).mkString(", ")}")
          )
          .map(status => UpdateTodo(request.title, request.description, Some(status), request.dueAt))

final case class ErrorView(error: String, message: String, field: Option[String] = None)

object ErrorView:
  given Encoder[ErrorView] = deriveEncoder

  def of(code: String, message: String, field: Option[String] = None): ErrorView =
    ErrorView(code, message, field)

final case class HealthView(status: String, database: String)

object HealthView:
  given Encoder[HealthView] = deriveEncoder

private[http] object JsonSupport:

  /** Renders a decode failure as `dueAt: Invalid date-time format`, say. */
  def decodingFailureMessage(failure: DecodingFailure): String =
    val path = failure.history.reverse.flatMap {
      case io.circe.CursorOp.DownField(name) => Some(name)
      case _                                 => None
    }.mkString(".")
    if path.isEmpty then failure.message else s"$path: ${failure.message}"
