package io.tuliplogic.ziotoolbox.doobie

import com.zaxxer.hikari.HikariConfig
import doobie.hikari.HikariTransactor
import doobie.util.log
import zio.interop.catz._
import zio.{Cause, Task, ZIO, ZLayer}

object TransactorLayer {

  def layer(driverClassName: String): ZLayer[DbConnectionParams, Throwable, HikariTransactor[Task]] =
    ZLayer.scoped {
      for {
        params <- ZIO.service[DbConnectionParams]
        hikariConfig <- ZIO.succeed {
          val hikariConfig = new HikariConfig()
          hikariConfig.setDriverClassName(driverClassName)
          hikariConfig.setJdbcUrl(params.url)
          hikariConfig.setUsername(params.user)
          hikariConfig.setPassword(params.password)
          hikariConfig.setMaximumPoolSize(params.maxConnections)
          hikariConfig
        }
        transactor <- HikariTransactor
          .fromHikariConfig[Task](
            hikariConfig,
            Some {
              case log.ProcessingFailure(sql, _, _, _, _, cause) =>
                ZIO.logErrorCause(s"Error processing query. SQL: \"${sql}\"", Cause.die(cause))
              case log.ExecFailure(sql, _, _, _, cause) =>
                ZIO.logErrorCause(s"Error executing query. SQL: \"${sql}\"", Cause.die(cause))
              case log.Success(_, _, _, _, _) => ZIO.unit
            }
          ).toScopedZIO
      } yield transactor
    }

}
