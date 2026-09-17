package todo.repository

import todo.domain.*

/** Persistence algebra. Deliberately expressed in terms of the domain only — nothing here leaks `ConnectionIO`,
  * `Fragment` or any other doobie type, so a second interpreter (in-memory, Redis, ...) is a drop-in replacement.
  *
  * Methods return `F[...]`, not `Result[F, ...]`: "row not found" is *data* (`Option`/`Boolean`) for the caller to
  * interpret, while genuine infrastructure failures propagate as errors in `F`.
  */
trait TodoRepository[F[_]]:

  def insert(todo: Todo): F[Todo]

  def find(id: TodoId): F[Option[Todo]]

  def list(filter: TodoFilter, limit: Int, offset: Int): F[List[Todo]]

  def count(filter: TodoFilter): F[Long]

  /** Full overwrite of an existing row; `None` when the id does not exist. */
  def replace(todo: Todo): F[Option[Todo]]

  /** `true` when a row was actually removed. */
  def delete(id: TodoId): F[Boolean]
