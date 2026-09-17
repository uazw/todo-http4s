package todo.domain

import cats.Applicative

/** The error channel used by the service algebras: a typed `TodoError` in the left position, wrapped in the effect so
  * it composes with effectful steps.
  */
type Result[F[_], A] = F[Either[TodoError, A]]

object Result:

  def ok[F[_]: Applicative, A](value: A): Result[F, A] =
    Applicative[F].pure(Right(value))

  def raise[F[_]: Applicative, A](error: TodoError): Result[F, A] =
    Applicative[F].pure(Left(error))

  def fromEither[F[_]: Applicative, A](value: Either[TodoError, A]): Result[F, A] =
    Applicative[F].pure(value)

  def fromOption[F[_]: Applicative, A](value: Option[A], ifEmpty: => TodoError): Result[F, A] =
    Applicative[F].pure(value.toRight(ifEmpty))
