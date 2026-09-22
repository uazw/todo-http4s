package todo.http

import cats.effect.Concurrent
import cats.syntax.all.*
import java.time.Instant
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Location
import todo.db.HealthCheck
import todo.domain.*
import todo.service.TodoService

/** HTTP edge. It does three things and nothing else: turn a request into domain input, call the service algebra, turn
  * the `TodoError` back into a status code.
  */
final class TodoRoutes[F[_]: Concurrent](service: TodoService[F], health: HealthCheck[F]) extends Http4sDsl[F]:

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {

    case GET -> Root / "health" =>
      health.isHealthy.flatMap { databaseUp =>
        val body = HealthView(status = "ok", database = if databaseUp then "up" else "down")
        if databaseUp then Ok(body) else ServiceUnavailable(body)
      }

    case req @ GET -> Root / "api" / "todos" =>
      listTodos(req)

    case req @ POST -> Root / "api" / "todos" =>
      create(req)

    case GET -> Root / "api" / "todos" / UUIDVar(uuid) =>
      complete(service.get(TodoId(uuid)))(todo => Ok(TodoView.from(todo)))

    case req @ PATCH -> Root / "api" / "todos" / UUIDVar(uuid) =>
      patch(req, TodoId(uuid))

    case DELETE -> Root / "api" / "todos" / UUIDVar(uuid) =>
      complete(service.delete(TodoId(uuid)))(_ => NoContent())

    case GET -> Root / "api" / "todos" / _ =>
      BadRequest(ErrorView.of("invalid_request", "id must be a UUID"))

    case req =>
      NotFound(ErrorView.of("not_found", s"no route for ${req.method.name} ${req.uri.path.renderString}"))
  }

  private def listTodos(req: Request[F]): F[Response[F]] =
    val params = req.uri.query.params
    val parsed = for
      status <- optionalStatus(params)
      limit <- intParam(params, "limit", TodoService.DefaultLimit, 1, TodoService.MaxLimit)
      offset <- intParam(params, "offset", 0, 0, Int.MaxValue)
      dueBefore <- optionalInstant(params, "due_before")
    yield TodoFilter(status, search = params.get("q").map(_.trim).filter(_.nonEmpty), dueBefore = dueBefore) -> (
      limit,
      offset
    )

    parsed match
      case Left(error)                      => errorResponse(error)
      case Right((filter, (limit, offset))) =>
        complete(service.list(filter, limit, offset))(page => Ok(PageView.from(page)))

  private def create(req: Request[F]): F[Response[F]] =
    req.attemptAs[CreateTodoRequest].value.flatMap {
      case Left(failure) =>
        BadRequest(ErrorView.of("invalid_json", failure.message))
      case Right(body) =>
        complete(service.create(CreateTodoRequest.toDomain(body))) { todo =>
          Created(TodoView.from(todo)).map(_.putHeaders(Location(Uri.unsafeFromString(s"/api/todos/${todo.id.value}"))))
        }
    }

  private def patch(req: Request[F], id: TodoId): F[Response[F]] =
    req.attemptAs[PatchTodoRequest].value.flatMap {
      case Left(failure) =>
        BadRequest(ErrorView.of("invalid_json", failure.message))
      case Right(body) =>
        PatchTodoRequest.toDomain(body) match
          case Left(error)   => errorResponse(error)
          case Right(update) => complete(service.update(id, update))(todo => Ok(TodoView.from(todo)))
    }

  /** Runs the service call and maps its error channel onto an HTTP response. */
  private def complete[A](result: Result[F, A])(onSuccess: A => F[Response[F]]): F[Response[F]] =
    Concurrent[F].flatMap(result) {
      case Right(value) => onSuccess(value)
      case Left(error)  => errorResponse(error)
    }

  private def errorResponse(error: TodoError): F[Response[F]] = error match
    case TodoError.NotFound(_)   => NotFound(ErrorView.of("not_found", error.message))
    case TodoError.Invalid(f, r) => BadRequest(ErrorView.of("invalid_request", r, Some(f)))
    case TodoError.Storage(r)    => InternalServerError(ErrorView.of("internal_error", r))

  private def optionalStatus(params: Map[String, String]): Either[TodoError, Option[TodoStatus]] =
    params.get("status") match
      case None | Some("") => Right(None)
      case Some(raw)       =>
        TodoStatus
          .fromWire(raw)
          .toRight(
            TodoError.Invalid("status", s"must be one of ${TodoStatus.all.map(TodoStatus.toWire).mkString(", ")}")
          )
          .map(Some(_))

  private def optionalInstant(params: Map[String, String], key: String): Either[TodoError, Option[Instant]] =
    params.get(key) match
      case None | Some("") => Right(None)
      case Some(raw)       =>
        Either
          .catchOnly[Exception](Instant.parse(raw))
          .left
          .map(_ => TodoError.Invalid(key, "must be an ISO-8601 instant, e.g. 2026-01-31T09:00:00Z"))
          .map(Some(_))

  private def intParam(
      params: Map[String, String],
      key: String,
      default: Int,
      min: Int,
      max: Int
  ): Either[TodoError, Int] =
    params.get(key) match
      case None | Some("") => Right(default)
      case Some(raw)       =>
        raw.trim.toIntOption
          .filter(value => value >= min && value <= max)
          .toRight(
            TodoError.Invalid(key, s"must be an integer between $min and $max")
          )

object TodoRoutes:

  def apply[F[_]: Concurrent](service: TodoService[F], health: HealthCheck[F]): TodoRoutes[F] =
    new TodoRoutes[F](service, health)
