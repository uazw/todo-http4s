package todo.db

import cats.effect.{Async, Resource, Sync}
import com.zaxxer.hikari.HikariConfig
import doobie.Transactor
import doobie.hikari.HikariTransactor
import java.util.concurrent.{ExecutorService, Executors, ThreadFactory}
import scala.concurrent.ExecutionContext
import todo.config.DatabaseConfig

object Database:

  /** Hikari-backed [[Transactor]] as a `Resource`, so the pool is closed on shutdown. JDBC calls block, so they get a
    * dedicated pool of their own — the compute pool is never blocked waiting on a query.
    */
  def transactor[F[_]: Async](config: DatabaseConfig): Resource[F, Transactor[F]] =
    blockingPool[F](math.max(2, config.poolSize), "todo-db").flatMap { connectEC =>
      val hikari = new HikariConfig()
      hikari.setPoolName("todo-pool")
      hikari.setDriverClassName("org.postgresql.Driver")
      hikari.setJdbcUrl(config.jdbcUrl)
      hikari.setUsername(config.user)
      hikari.setPassword(config.password)
      hikari.setMaximumPoolSize(config.poolSize)
      hikari.setConnectionTimeout(config.connectionTimeout.toMillis)
      hikari.setAutoCommit(true)

      HikariTransactor.fromHikariConfigCustomEc[F](hikari, connectEC)
    }

  private def blockingPool[F[_]: Sync](size: Int, name: String): Resource[F, ExecutionContext] =
    Resource
      .make(Sync[F].delay(Executors.newFixedThreadPool(size, namedThreads(name))))(executor =>
        Sync[F].delay(executor.shutdown())
      )
      .map((executor: ExecutorService) => ExecutionContext.fromExecutorService(executor))

  private def namedThreads(name: String): ThreadFactory = new ThreadFactory:
    def newThread(runnable: Runnable): Thread =
      val thread = new Thread(runnable, name)
      thread.setDaemon(true)
      thread
