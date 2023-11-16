package charginghub

import io.grpc.{ServerBuilder, StatusException}
import io.tuliplogic.ziotoolbox.tracing.commons.{Bootstrap, OTELTracer}
import charginghub.charging_hub_api.{StartSessionRequest, StartSessionResponse, StopSessionRequest, StopSessionResponse, ZioChargingHubApi}
import io.tuliplogic.ziotoolbox.tracing.grpc.server.GrpcServerTracingInterpreter
import scalapb.zio_grpc.{RequestContext, Server, ServerLayer, ServiceList}
import zio.{IO, Scope, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer}

object ChargingHubApp extends ZIOAppDefault {
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
    Bootstrap.defaultBootstrap

  class ApiImpl extends ZioChargingHubApi.ChargingHubApi {
    override def startSession(request: StartSessionRequest): IO[StatusException, StartSessionResponse] = {
      ZIO.logAnnotate("sessionId", request.requestId) {
        ZIO.logInfo(s"Received start session request $request") *>
          zio.Random.nextBoolean.zip(zio.Random.nextUUID).flatMap { case (success, uuid) =>
            (if (success) ZIO.logInfo("Session start succeeded") else ZIO.logInfo("Session start failed")).as(StartSessionResponse(uuid.toString, success))
          }
      }
    }

    override def stopSession(request: StopSessionRequest): IO[StatusException, StopSessionResponse] = {
      ZIO.logAnnotate("stopSessionId", request.sessionId) {
        ZIO.logInfo(s"Received stop session request for session ${request.sessionId}") *>
          zio.Random.nextBoolean.flatMap { case success =>
            (if (success) ZIO.logInfo("Session stop succeeded") else ZIO.logInfo("Session start failed")).as(StopSessionResponse(success))
          }
      }
    }
  }

  object ApiImpl {
    val layer = GrpcServerTracingInterpreter.serviceWithTracing[Any, ZioChargingHubApi.ChargingHubApi](_ =>
      new ApiImpl
    )
  }

  private def serverLive(
                          port: Int
                        ): ZLayer[ZioChargingHubApi.GChargingHubApi[RequestContext, StatusException], Throwable, Server] = {
    val serviceList = ServiceList.addFromEnvironment[ZioChargingHubApi.GChargingHubApi[RequestContext, StatusException]]
    ServerLayer.fromServiceList(ServerBuilder.forPort(port), serviceList)
  }

  private def makeLaunchableServer(port: Int) =
    ZLayer.make[Server](
      serverLive(port),
      ChargingHubApp.ApiImpl.layer,
      Bootstrap.tracingLayer,
      OTELTracer.default("charging-hub"),
    )


  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] =

    makeLaunchableServer(9001).launch
}
