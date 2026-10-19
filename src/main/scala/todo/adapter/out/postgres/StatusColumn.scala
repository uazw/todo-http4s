package todo.adapter.out.postgres

import doobie.Meta
import todo.domain.TodoStatus

private[postgres] object StatusColumn:

  private val readStatus: String => Either[String, TodoStatus] =
    case "pending"     => Right(TodoStatus.Pending)
    case "in_progress" => Right(TodoStatus.InProgress)
    case "done"        => Right(TodoStatus.Done)
    case other         => Left(s"unknown status in DB: '$other'")

  private val writeStatus: TodoStatus => String =
    case TodoStatus.Pending    => "pending"
    case TodoStatus.InProgress => "in_progress"
    case TodoStatus.Done       => "done"

  given Meta[TodoStatus] = Meta[String].tiemap(readStatus)(writeStatus)
