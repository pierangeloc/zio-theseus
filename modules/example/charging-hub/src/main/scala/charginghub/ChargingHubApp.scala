package charginghub

import io.grpc.{ServerBuilder, StatusException}
import io.tuliplogic.ziotoolbox.tracing.commons.{Bootstrap, OTELTracer}
import charginghub.charging_hub_api.{StartSessionRequest, StartSessionResponse, StopSessionRequest, StopSessionResponse, ZioChargingHubApi}
import io.tuliplogic.ziotoolbox.tracing.grpc.server.{GrpcServerNoTracing, GrpcServerTracingInterpreter}
import scalapb.zio_grpc.{RequestContext, Server, ServerLayer, ServiceList}
import zio.telemetry.opentelemetry.tracing.Tracing
import zio._

object ChargingHubApp extends ZIOAppDefault {
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
    Bootstrap.defaultBootstrap

  class ApiImpl(tracing: Tracing) extends ZioChargingHubApi.ChargingHubApi {
    override def startSession(request: StartSessionRequest): IO[StatusException, StartSessionResponse] = {
      ZIO.logAnnotate("requestId", request.requestId) {
        ZIO.logInfo(s"Received start session request $request") *>
          tracing.span("Call external Charging operator") {
            ZIO.succeed(true).zip(zio.Random.nextUUID).delay(2.seconds)
          }
//          zio.Random.nextBoolean.zip(zio.Random.nextUUID).flatMap { case (success, uuid) =>
          .flatMap { case (success, uuid) =>
            (if (success) ZIO.logInfo("Session start succeeded") else ZIO.logInfo("Session start failed")).as(StartSessionResponse(uuid.toString, success))
          }
      }
    }

    override def stopSession(request: StopSessionRequest): IO[StatusException, StopSessionResponse] = {
      ZIO.logAnnotate("stopSessionId", request.sessionId) {
        ZIO.logInfo(s"Received stop session request for session ${request.sessionId}") *>
//          zio.Random.nextBoolean.flatMap { case success =>
          ZIO.succeed(true).flatMap { case success =>
            (if (success) ZIO.logInfo("Session stop succeeded") else ZIO.logInfo("Session start failed")).as(StopSessionResponse(success))
          }
      }
    }
  }

  object ApiImpl {
    val layer = GrpcServerTracingInterpreter.serviceWithTracing[Tracing, ZioChargingHubApi.ChargingHubApi](tracing =>
      new ApiImpl(tracing)
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


  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] = {
    ZIO.logInfo("Launching grpc server on port 9001") *>
    makeLaunchableServer(9001).launch
  }
}
