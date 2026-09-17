package todo.db

import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import scala.io.Source

/**
 * Minimal schema bootstrap: runs the bundled `schema.sql`. The DDL is written
 * to be idempotent (`IF NOT EXISTS`), so running it on every boot is a no-op.
 *
 * For a schema that evolves, swap this for Flyway/Liquibase — the rest of the
 * application does not care how the tables got there.
 */
object Migrator:

  val DefaultScript: String = "db/schema.sql"

  def run[F[_]: MonadCancelThrow](xa: Transactor[F], script: String = DefaultScript): F[Unit] =
    load(script) match
      case Left(message) => MonadCancelThrow[F].raiseError(new IllegalStateException(message))
      case Right(statements) =>
        statements.traverse_(statement => Fragment.const(statement).update.run.transact(xa)).void

  private[db] def load(script: String): Either[String, List[String]] =
    Option(getClass.getClassLoader.getResourceAsStream(script)) match
      case None => Left(s"migration script '$script' not found on the classpath")
      case Some(stream) =>
        val source = Source.fromInputStream(stream, "UTF-8")
        try
          val statements = source
            .getLines()
            .filterNot(_.trim.startsWith("--"))
            .mkString("\n")
            .split(";")
            .map(_.trim)
            .filter(_.nonEmpty)
            .toList
          Right(statements)
        finally source.close()
