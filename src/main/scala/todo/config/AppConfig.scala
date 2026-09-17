package todo.config

import com.typesafe.config.{Config, ConfigFactory}
import scala.concurrent.duration.*
import scala.util.Try

final case class HttpConfig(host: String, port: Int)

final case class DatabaseConfig(
    host: String,
    port: Int,
    name: String,
    user: String,
    password: String,
    poolSize: Int,
    connectionTimeout: FiniteDuration
):
  def jdbcUrl: String = s"jdbc:postgresql://$host:$port/$name"

final case class AppConfig(
    http: HttpConfig,
    database: DatabaseConfig,
    migrateOnStart: Boolean
)

/** Reads `application.conf` (which itself layers env vars on top of defaults).
  *
  * Failures come back as a `Left` with a human-readable message instead of exploding inside `IOApp`, so a typo in an
  * env var is one clear log line rather than a stack trace.
  */
object AppConfig:

  def load(): Either[String, AppConfig] =
    Try(ConfigFactory.load()).toEither.left
      .map(throwable => s"could not read configuration: ${throwable.getMessage}")
      .flatMap(load)

  def load(config: Config): Either[String, AppConfig] =
    for
      _ <- section(config, "todo") // fail fast, with a message that names the section
      http <- http(config)
      database <- database(config)
      migrate <- bool(config, "todo.migrate-on-start")
    yield AppConfig(http, database, migrate)

  private def section(config: Config, path: String): Either[String, Config] =
    Try(config.getConfig(path)).toEither.left.map(_ => s"missing configuration section '$path'")

  private def string(config: Config, path: String): Either[String, String] =
    Try(config.getString(path)).toEither.left.map(_ => s"'$path' must be a string")

  private def int(config: Config, path: String, min: Int, max: Int): Either[String, Int] =
    Try(config.getInt(path)).toEither.left
      .map(_ => s"'$path' must be an integer")
      .flatMap(value =>
        Either.cond(value >= min && value <= max, value, s"'$path' must be between $min and $max (got $value)")
      )

  private def bool(config: Config, path: String): Either[String, Boolean] =
    Try(config.getBoolean(path)).toEither.left.map(_ => s"'$path' must be a boolean")

  private def http(config: Config): Either[String, HttpConfig] =
    for
      _ <- section(config, "todo.http")
      host <- string(config, "todo.http.host")
      port <- int(config, "todo.http.port", 1, 65535)
    yield HttpConfig(host, port)

  private def database(config: Config): Either[String, DatabaseConfig] =
    for
      _ <- section(config, "todo.database")
      host <- string(config, "todo.database.host")
      port <- int(config, "todo.database.port", 1, 65535)
      name <- string(config, "todo.database.name")
      user <- string(config, "todo.database.user")
      password <- string(config, "todo.database.password")
      poolSize <- int(config, "todo.database.pool-size", 1, 64)
      timeoutMillis <- Try(config.getDuration("todo.database.connection-timeout").toMillis).toEither.left
        .map(_ => "'todo.database.connection-timeout' must be a duration, e.g. 5s")
    yield DatabaseConfig(host, port, name, user, password, poolSize, timeoutMillis.millis)
