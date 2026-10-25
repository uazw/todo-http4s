package todo.adapter.out.postgres

import cats.effect.Sync
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import todo.boot.DatabaseConfig

/** Flyway-backed schema migrations, loaded from `classpath:db/migration`.
  *
  * Takes the connection settings rather than a doobie `Transactor`: Flyway wants a JDBC `DataSource`, and opening its
  * own short-lived connection keeps migrations independent of the application's connection pool. Production boot and
  * the integration specs both go through [[run]], so the tests exercise the real migration path.
  */
object Migrator:

  val Location: String = "classpath:db/migration"

  /** Databases created before Flyway was adopted already contain the V1 schema but have no history table. Baselining at
    * version 1 records V1 as applied there instead of failing on the existing table. An empty database still runs V1
    * normally.
    */
  private val BaselineVersion: String = "1"

  def run[F[_]: Sync](config: DatabaseConfig): F[MigrateResult] =
    Sync[F].blocking(flyway(config).migrate())

  private[postgres] def flyway(config: DatabaseConfig): Flyway =
    Flyway
      .configure()
      .dataSource(config.jdbcUrl, config.user, config.password)
      .locations(Location)
      .baselineOnMigrate(true)
      .baselineVersion(BaselineVersion)
      .load()
