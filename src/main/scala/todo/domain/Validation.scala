package todo.domain

/**
 * Pure validation of incoming payloads. Kept out of the HTTP layer so the same
 * rules apply no matter which interface calls the service.
 */
object Validation:

  val MaxTitleLength: Int       = 200
  val MaxDescriptionLength: Int = 2000

  private def prepare(raw: String): String =
    Option(raw).getOrElse("").trim

  def title(raw: String): Either[TodoError, String] =
    val value = prepare(raw)
    if value.isEmpty then Left(TodoError.Invalid("title", "must not be blank"))
    else if value.length > MaxTitleLength then
      Left(TodoError.Invalid("title", s"must be at most $MaxTitleLength characters"))
    else Right(value)

  def description(raw: Option[String]): Either[TodoError, Option[String]] =
    Option(raw).flatten.map(_.trim) match
      case None | Some("") => Right(None)
      case Some(value) if value.length > MaxDescriptionLength =>
        Left(TodoError.Invalid("description", s"must be at most $MaxDescriptionLength characters"))
      case Some(value) => Right(Some(value))

  def createTodo(input: CreateTodo): Either[TodoError, CreateTodo] =
    for
      t <- title(input.title)
      d <- description(input.description)
    yield CreateTodo(t, d, input.dueAt)

  /** Applies a patch on top of an existing item, validating whatever it touches. */
  def patch(existing: Todo, update: UpdateTodo): Either[TodoError, Todo] =
    val base = existing.copy(
      status = update.status.getOrElse(existing.status),
      dueAt = update.dueAt.getOrElse(existing.dueAt)
    )

    val patchedTitle: Either[TodoError, String] = update.title match
      case Some(t) => title(t)
      case None    => Right(base.title)

    val patchedDescription: Either[TodoError, Option[String]] = update.description match
      case Some(d) => description(d)
      case None    => Right(base.description)

    for
      t <- patchedTitle
      d <- patchedDescription
    yield base.copy(title = t, description = d)
