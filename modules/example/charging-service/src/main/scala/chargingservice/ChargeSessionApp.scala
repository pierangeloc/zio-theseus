package chargingservice

import io.tuliplogic.ziotoolbox.doobie.{DbConnectionParams, TransactorLayer}
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir._
import zio.http.{HttpApp, Server}
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.{Cause, Scope, ULayer, URLayer, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer}

object ChargeSessionApp extends ZIOAppDefault {

  case class Config(
    httpPort: Int,
    dbConnectionParams: DbConnectionParams,
    kafkaPublisherConfig: KafkaPublisher.Config
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
        topic = "charging-service",
        bootstrapServers = "localhost:29092"
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

  val zioHttpApp: HttpApp[Any with ChargeSessionHandler, Throwable] =
    ZioHttpInterpreter().toHttp(
      List(
        ChargeSessionApi.startSessionEndpoint.zServerLogic { chargingRequest =>
          for {
            csh <- ZIO.service[ChargeSessionHandler]
            resp <- csh
                      .startSession(chargingRequest)
                      .flatMapError(t =>
                        ZIO.logErrorCause("Error handling start charging request", Cause.die(t)).as(s"error ${t.getMessage}")
                      )
          } yield resp
        },

        ChargeSessionApi.stopSessionEndpoint.zServerLogic { stopChargeSessionRequest =>
          for {
            csh <- ZIO.service[ChargeSessionHandler]
            resp <- csh
              .stopSession(stopChargeSessionRequest)
              .flatMapError(t =>
                ZIO.logErrorCause("Error handling stop charging request", Cause.die(t)).as(s"error ${t.getMessage}")
              )
          } yield resp
        },
    )
  )

  val zioHttpServerConfig: URLayer[Config, Server.Config] =
    ZLayer.fromZIO {
      for {
        config <- ZIO.service[Config]
      } yield Server.Config.default.binding("localhost", config.httpPort)

    }

  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] =
    ZIO.logInfo("Running HTTP app") *>
      Server.serve(zioHttpApp.withDefaultErrorResponse)
        .provide(
          Server.live,
          zioHttpServerConfig,
          configLayer,
          configLayer.project(_.dbConnectionParams),
          configLayer.project(_.kafkaPublisherConfig),
          LiveChargeSessionHandler.layer,
          DoobieChargeSessionRepository.live,
          TransactorLayer.Debug.withLogging,
          KafkaPublisher.layer,
          kafkaProducerLayer
        )
}
