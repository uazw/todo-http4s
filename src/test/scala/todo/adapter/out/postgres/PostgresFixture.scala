package todo.adapter.out.postgres

import cats.effect.{IO, Resource}
import doobie.Transactor
import java.sql.DriverManager
import org.testcontainers.postgresql.PostgreSQLContainer
import scala.concurrent.duration.*
import scala.io.Source
import todo.boot.DatabaseConfig

/** A throwaway PostgreSQL for integration specs, migrated with the production [[Migrator]].
  *
  * Testcontainers only supplies an empty database; the schema comes from the same Flyway scripts the service runs at
  * boot, so a broken migration fails here first. The only external requirement is a Docker-compatible runtime.
  */
object PostgresFixture:

  val Image: String = "postgres:18-alpine"

  final case class Postgres(config: DatabaseConfig, xa: Transactor[IO])

  /** Container started, migrated, and pooled once; everything is torn down in reverse order on release. */
  def resource: Resource[IO, Postgres] =
    for
      container <- Resource.make(IO.blocking(startContainer()))(c => IO.blocking(c.stop()))
      config = configFor(container)
      _ <- Resource.eval(Migrator.run[IO](config))
      xa <- Database.transactor[IO](config)
    yield Postgres(config, xa)

  private def startContainer(): PostgreSQLContainer =
    val container =
      new PostgreSQLContainer(Image)
        .withDatabaseName("todo_test")
        .withUsername("todo")
        .withPassword("todo")
    container.start()
    container

  private def configFor(container: PostgreSQLContainer): DatabaseConfig =
    DatabaseConfig(
      host = container.getHost,
      port = container.getFirstMappedPort,
      name = container.getDatabaseName,
      user = container.getUsername,
      password = container.getPassword,
      poolSize = 4,
      connectionTimeout = 5.seconds
    )

  /** `CREATE DATABASE` cannot run inside a transaction, so this bypasses doobie and uses an autocommit connection. */
  def createDatabase(server: DatabaseConfig, name: String): IO[Unit] =
    execute(server, s"""CREATE DATABASE "$name"""")

  /** Runs raw SQL (possibly several `;`-separated statements) on a short-lived autocommit connection. */
  def execute(config: DatabaseConfig, sql: String): IO[Unit] =
    Resource
      .fromAutoCloseable(IO.blocking(DriverManager.getConnection(config.jdbcUrl, config.user, config.password)))
      .flatMap(connection => Resource.fromAutoCloseable(IO.blocking(connection.createStatement())))
      .use(statement => IO.blocking(statement.execute(sql)).void)

  def resourceText(path: String): String =
    val source = Source.fromResource(path)
    try source.mkString
    finally source.close()
