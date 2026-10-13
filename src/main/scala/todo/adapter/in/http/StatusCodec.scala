package todo.adapter.in.http

import todo.domain.{TodoError, TodoStatus}

object StatusCodec:

  def toWire(status: TodoStatus): String = status match
    case TodoStatus.Pending    => "pending"
    case TodoStatus.InProgress => "in_progress"
    case TodoStatus.Done       => "done"

  /** Tolerant parser: accepts `in-progress`, `In Progress`, `completed`, ... */
  def parse(raw: String): Option[TodoStatus] =
    raw.trim.toLowerCase.replace('-', '_').replace(' ', '_') match
      case "pending"                         => Some(TodoStatus.Pending)
      case "in_progress" | "inprogress"      => Some(TodoStatus.InProgress)
      case "done" | "complete" | "completed" => Some(TodoStatus.Done)
      case _                                 => None

  def parseField(key: String, raw: String): Either[TodoError, TodoStatus] =
    parse(raw).toRight(
      TodoError.Invalid(key, s"must be one of ${TodoStatus.all.map(toWire).mkString(", ")}")
    )
