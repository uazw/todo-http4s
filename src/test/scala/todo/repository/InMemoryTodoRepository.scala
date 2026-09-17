package todo.repository

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import todo.domain.*

/**
 * Second interpreter of [[TodoRepository]], backed by a `Ref`. It is what makes
 * the tagless-final split pay off: the service and HTTP specs exercise real
 * behaviour with no database in sight.
 *
 * Filtering, ordering and paging mirror the SQL implementation on purpose, so
 * the two can be swapped in tests without changing expectations.
 */
final class InMemoryTodoRepository[F[_]: Sync](state: Ref[F, Map[TodoId, Todo]]) extends TodoRepository[F]:

  def insert(todo: Todo): F[Todo] =
    state.update(_ + (todo.id -> todo)).as(todo)

  def find(id: TodoId): F[Option[Todo]] =
    state.get.map(_.get(id))

  def list(filter: TodoFilter, limit: Int, offset: Int): F[List[Todo]] =
    state.get.map { todos =>
      todos.values.toList
        .filter(matches(filter))
        .sortBy(todo => (-todo.createdAt.toEpochMilli, todo.id.value.toString))
        .slice(offset, offset + limit)
    }

  def count(filter: TodoFilter): F[Long] =
    state.get.map(_.values.count(matches(filter)).toLong)

  def replace(todo: Todo): F[Option[Todo]] =
    state.modify { todos =>
      if todos.contains(todo.id) then (todos + (todo.id -> todo), Some(todo))
      else (todos, None)
    }

  def delete(id: TodoId): F[Boolean] =
    state.modify(todos => (todos - id, todos.contains(id)))

  private def matches(filter: TodoFilter)(todo: Todo): Boolean =
    filter.status.forall(_ == todo.status) &&
      filter.dueBefore.forall(due => todo.dueAt.exists(!_.isAfter(due))) &&
      filter.search.map(_.trim.toLowerCase).filter(_.nonEmpty).forall { needle =>
        todo.title.toLowerCase.contains(needle) ||
        todo.description.exists(_.toLowerCase.contains(needle))
      }

object InMemoryTodoRepository:

  def create[F[_]: Sync]: F[TodoRepository[F]] =
    Ref.of[F, Map[TodoId, Todo]](Map.empty).map(new InMemoryTodoRepository[F](_))

  def apply[F[_]: Sync](state: Ref[F, Map[TodoId, Todo]]): TodoRepository[F] =
    new InMemoryTodoRepository[F](state)
