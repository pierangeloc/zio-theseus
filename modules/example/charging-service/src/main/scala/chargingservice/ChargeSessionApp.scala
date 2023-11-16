package chargingservice

import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir._
import zio.{Cause, Scope, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer}

object ChargeSessionApp extends ZIOAppDefault {

  case class Config(
    httpPort: Long
  )

  val config = ZLayer.succeed(Config(9000))

  val zioHttpApp =
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

  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] = ???

}
