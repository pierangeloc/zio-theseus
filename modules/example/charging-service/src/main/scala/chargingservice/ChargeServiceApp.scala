package chargingservice

import charginghub.charging_hub_api.ZioChargingHubApi
import io.grpc.ManagedChannelBuilder
import io.tuliplogic.ziotoolbox.doobie.{DbConnectionParams, FlywayMigration, TransactorLayer}
import io.tuliplogic.ziotoolbox.tracing.commons.{Bootstrap, OTELTracer}
import io.tuliplogic.ziotoolbox.tracing.grpc.client.GrpcClientTracing
import io.tuliplogic.ziotoolbox.tracing.kafka.producer.ProducerTracing.KafkaProducerTracer
import io.tuliplogic.ziotoolbox.tracing.sttp.server.TapirServerTracer
import scalapb.zio_grpc.ZManagedChannel
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir._
import zio.http.{HttpApp, Server}
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{Cause, LogAnnotation, Scope, URLayer, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer}

object ChargeServiceApp extends ZIOAppDefault {

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
    Bootstrap.defaultBootstrap

  case class ChargingHubClientConfig(
    host: String,
    port: Int
  )

  case class Config(
    httpPort: Int,
    dbConnectionParams: DbConnectionParams,
    kafkaPublisherConfig: KafkaPublisher.Config,
    chargingHubClientConfig: ChargingHubClientConfig
  )

  val configLayer = ZLayer.succeed(
    Config(
      httpPort = 9000,
      dbConnectionParams = DbConnectionParams(
        url = "jdbc:postgresql://localhost:5411/db_charging_service",
        user = "db_charging_service",
        password = "db_charging_service",
        maxConnections = 10
      ),
      kafkaPublisherConfig = KafkaPublisher.Config(
        topic = "session-events",
        bootstrapServers = "localhost:29092"
      ),
      chargingHubClientConfig = ChargingHubClientConfig(
        host = "localhost",
        port = 9001
      )
    )
  )

  val kafkaProducerLayer =
    ZLayer.scoped(
      for {
        kafkaPublisherConfig <- ZIO.service[KafkaPublisher.Config]
        p <- Producer.make(
               settings = ProducerSettings(List(kafkaPublisherConfig.bootstrapServers))
             )
      } yield p
    )

  val zioHttpApp: ZIO[TapirServerTracer with Tracing with Baggage, Nothing, HttpApp[Any with ChargeSessionHandler, Throwable]] =
    for {
      tapirServerTracer <- ZIO.service[TapirServerTracer]
    } yield {
      import tapirServerTracer._

      //some log annotations for test. In reality they will be dynamic values
      val logAnnotations = Set(
        LogAnnotation("user-id", "test-user-id"),
        LogAnnotation("frontend-correlation-id", "correlation-id-123")
      )


      ZioHttpInterpreter().toHttp(
        List(
          ChargeSessionApi.startSessionEndpoint.zServerLogicTracing("start-session") { chargingRequest =>
            ZIO.logAnnotate(logAnnotations)(
              for {
                _ <- ZIO.logInfo("Handling start charging request")
                csh <- ZIO.service[ChargeSessionHandler]
                resp <-
                  csh
                    .startSession(chargingRequest)
                    .flatMapError(t =>
                      ZIO.logErrorCause("Error handling start charging request", Cause.die(t)).as(s"error ${t.getMessage}")
                    )
              } yield resp
            )
          },

          ChargeSessionApi.stopSessionEndpoint.zServerLogicTracing("stop-session") { stopChargeSessionRequest =>
            ZIO.logAnnotate(logAnnotations)(
              for {
                csh <- ZIO.service[ChargeSessionHandler]
                _ <- ZIO.logInfo("Handling stop charging request")
                resp <-
                  csh
                    .stopSession(stopChargeSessionRequest)
                    .flatMapError(t =>
                      ZIO.logErrorCause("Error handling stop charging request", Cause.die(t)).as(s"error ${t.getMessage}")
                    )
              } yield resp
            )
          }
        )
      )
  }

  val zioHttpServerConfig: URLayer[Config, Server.Config] =
    ZLayer.fromZIO {
      for {
        config <- ZIO.service[Config]
      } yield Server.Config.default.binding("localhost", config.httpPort)

    }

  val grpcClientLayer: ZLayer[ChargingHubClientConfig with Tracing with Baggage, Throwable, ZioChargingHubApi.ChargingHubApiClient] =
    ZLayer.scoped {
      for {
        config <- ZIO.service[ChargingHubClientConfig]
        client <- GrpcClientTracing.tracedClient(config.host, config.port)(ch => ZioChargingHubApi.ChargingHubApiClient.scoped(ch))
      } yield client
    }

  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] =
    ZIO.logInfo("Running HTTP app") *>
      zioHttpApp.flatMap(httpApp => Server.serve(httpApp.withDefaultErrorResponse))
        .provide(
          Server.live,
          zioHttpServerConfig,
          configLayer,
          configLayer.project(_.dbConnectionParams),
          configLayer.project(_.kafkaPublisherConfig),
          configLayer.project(_.chargingHubClientConfig),
          LiveChargeSessionHandler.layer,
          DoobieChargeSessionRepository.live,
          FlywayMigration.layer,
          TransactorLayer.Debug.withLogging,
          KafkaPublisher.layer,
          kafkaProducerLayer,
          KafkaProducerTracer.layer(),
          grpcClientLayer,
          Bootstrap.tracingLayer,
          OTELTracer.default("charging-service"),
          TapirServerTracer.layer()
        )
}
