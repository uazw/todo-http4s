package todo.application.port.in

import todo.domain.*

/** Inbound (driving) port: the use cases this application offers. Inbound adapters such as the HTTP routes depend on
  * this trait and nothing else in the core; it knows nothing about HTTP, and the outbound ports know nothing about use
  * cases.
  */
trait TodoService[F[_]]:

  def create(input: CreateTodo): Result[F, Todo]

  def get(id: TodoId): Result[F, Todo]

  def list(filter: TodoFilter, limit: Int, offset: Int): Result[F, Page[Todo]]

  def update(id: TodoId, patch: UpdateTodo): Result[F, Todo]

  def delete(id: TodoId): Result[F, Unit]

object TodoService:

  val DefaultLimit: Int = 20
  val MaxLimit: Int = 100
