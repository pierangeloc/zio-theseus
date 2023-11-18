package billing

import billing.KafkaSessionConsumer.KafkaConfig
import io.tuliplogic.ziotoolbox.doobie.{DbConnectionParams, FlywayMigration, TransactorLayer}
import io.tuliplogic.ziotoolbox.tracing.commons.{Bootstrap, OTELTracer}
import io.tuliplogic.ziotoolbox.tracing.kafka.consumer.KafkaConsumerTracer
import org.apache.kafka.clients.consumer.ConsumerRecord
import zio.kafka.consumer.{Consumer, ConsumerSettings}
import zio.{Scope, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer}

object BillingServiceApp extends ZIOAppDefault {

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
    Bootstrap.defaultBootstrap

  case class Config(
    dbConnectionParams: DbConnectionParams,
    kafkaConsumerConfig: KafkaConfig
  )

  def program: ZIO[SessionConsumer, Throwable, Unit] = for {
    service <- ZIO.service[SessionConsumer]
    _ <- service.consumeSessions.drain.runDrain
  } yield ()

  val consumerLayer = ZLayer.scoped {
    for {
      kafkaConfig <- ZIO.service[KafkaConfig]
      consumer <- Consumer.make(
        ConsumerSettings(List(kafkaConfig.bootstrapServers)).withGroupId("billing-service")
      )
    } yield consumer
  }

  val configLayer = ZLayer.succeed(
    Config(
      dbConnectionParams = DbConnectionParams(
        url = "jdbc:postgresql://localhost:5412/db_billing_service",
        user = "db_billing_service",
        password = "db_billing_service",
        maxConnections = 10
      ),
      kafkaConsumerConfig = KafkaConfig(
      sessionsTopic = "session-events",
      bootstrapServers = "localhost:29092"
      )
    )
  )

  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] =
    program.provide(
      KafkaSessionConsumer.layer,
      DoobieBillableSessionRepostory.layer,
      consumerLayer,
      configLayer.project(_.kafkaConsumerConfig),
      configLayer.project(_.dbConnectionParams),
      OneCustomerCRMService.live,
      OneTariffService.live,
      TransactorLayer.Debug.withLogging,
      FlywayMigration.layer,
      KafkaConsumerTracer.layer(KafkaConsumerTracer.defaultConsumerTracingAlgebra("consume-session-event")),
      Bootstrap.tracingLayer,
      OTELTracer.default("billing-service")
    )



 }
