package io.tuliplogic.ziotoolbox.tracing.example

import doobie.DoobieTracing.syntax.TracedFragment
import doobie.Fragment
import doobie.hikari.HikariTransactor
import doobie.implicits._
import doobie.postgres.implicits._
import io.tuliplogic.ziotoolbox.doobie.{DbConnectionParams, FlywayMigration, TransactorLayer}
import io.tuliplogic.ziotoolbox.tracing.example.CallRecordRepository.CallRecord
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{Task, UIO, URLayer, ZIO, ZLayer}

import java.time.Instant

trait CallRecordRepository {
  def saveRecord(record: CallRecord): UIO[Unit]
}

object CallRecordRepository {
  object Queries {
    def insertRecord(record: CallRecord): Fragment =
      sql"""
           insert into call_records (timestamp, value)
           values (${record.timestamp}, ${record.value})
         """

  }
  case class CallRecord(
    timestamp: Instant,
    value: String
  )

  val live: URLayer[Tracing with HikariTransactor[Task], CallRecordRepository] = ZLayer.fromZIO {
    for {
      tx <- ZIO.service[HikariTransactor[Task]]
      tracing <- ZIO.service[Tracing]
    } yield new CallRecordRepository {
      override def saveRecord(record: CallRecord): UIO[Unit] = {

        ZIO.logInfo(s"saving record $record") *>
          Queries.insertRecord(record).runTraced(_.update.run)(tx, tracing).catchAllCause(cause =>
          ZIO.logErrorCause(cause)
        ).unit
      }
    }
  }

  val workingRepoLayer = ZLayer.make[CallRecordRepository](
    CallRecordRepository.live,
    FlywayMigration.layer,
    TransactorLayer.Debug.withLogging,
    ZLayer.succeed(
      DbConnectionParams(
        url = "jdbc:postgresql://localhost:5411/opentelemetry",
        user = "opentelemetry",
        password = "opentelemetry",
        maxConnections = 5
      )
    ),
    //these should come from main, but we do this this way so we don't have to change too much stuff
    Tracing.live,
    ContextStorage.fiberRef,
    JaegerTracer.default,
  )
}
