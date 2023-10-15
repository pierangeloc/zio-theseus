package io.tuliplogic.ziotoolbox.doobie

import com.zaxxer.hikari.HikariDataSource
import zio.Task

object FlywayMigration {

  def migrate(dbConnectionParams: DbConnectionParams): Task[Unit] = {
    import org.flywaydb.core.Flyway
    import zio._

    val hikariDataSource = new HikariDataSource {
      setJdbcUrl(dbConnectionParams.url)
      setUsername(dbConnectionParams.user)
      setPassword(dbConnectionParams.password)
      setMaximumPoolSize(dbConnectionParams.maxConnections)
    }

    val flyway = ZIO.attempt {
      Flyway
        .configure()
        .dataSource(hikariDataSource)
        .load()
    }

    for {
      flyway <- flyway
      _ <- ZIO.attempt(flyway.migrate())
    } yield ()
  }
}
