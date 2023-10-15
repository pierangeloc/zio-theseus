package io.tuliplogic.ziotoolbox.doobie

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import doobie.hikari.HikariTransactor
import doobie.util.log
import doobie.util.transactor.Transactor
import doobie.{ExecutionContexts, LogHandler}
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.jdbc.datasource.OpenTelemetryDataSource
import zio.interop.catz._
import zio.{Cause, Task, ZIO, ZLayer}

object TransactorLayer {

  val logHandler: LogHandler[Task] = {
    case log.ProcessingFailure(sql, _, _, _, _, cause) =>
      ZIO.logErrorCause(s"Error processing query. SQL: \"${sql}\"", Cause.die(cause))
    case log.ExecFailure(sql, _, _, _, cause) =>
      ZIO.logErrorCause(s"Error executing query. SQL: \"${sql}\"", Cause.die(cause))
    case log.Success(_, _, _, _, _) => ZIO.unit
  }

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
            Some(logHandler)
          ).toScopedZIO
      } yield transactor
    }


  /**
   * Builds a transactor using a OpenTelemetryDatasource wrapping a hikari datasource
   *
   * NOTE: This should not work at all, given the context storage is ThreadLocal based while we use ZIO fibers.
   */
  def otelLayer(driverClassName: String): ZLayer[DbConnectionParams with OpenTelemetry, Throwable, Transactor[Task]] =
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
        _ <- ZIO.attempt(hikariConfig.validate())
        connectEC <- ExecutionContexts.fixedThreadPool[Task](hikariConfig.getMaximumPoolSize).toScopedZIO
        openTelemetry <- ZIO.service[OpenTelemetry]
        ds <- ZIO.fromAutoCloseable(ZIO.attempt(new OpenTelemetryDataSource(new HikariDataSource(hikariConfig), openTelemetry)))
      } yield Transactor.fromDataSource[Task](ds, connectEC, Some(logHandler))
    }

}
