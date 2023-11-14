package charginghub

import io.grpc.{ServerBuilder, StatusException}
import io.tuliplogic.ziotoolbox.tracing.commons.{Bootstrap, OTELTracer}
import io.tuliplogic.ziotoolbox.tracing.example.proto.charging_hub_api.{SessionDetails, StartSessionResponse, ZioChargingHubApi}
import io.tuliplogic.ziotoolbox.tracing.grpc.server.GrpcServerTracingInterpreter
import scalapb.zio_grpc.{RequestContext, Server, ServerLayer, ServiceList}
import zio.{IO, Scope, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer}

object ChargingHub extends ZIOAppDefault {
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
    Bootstrap.defaultBootstrap

  class ApiImpl extends ZioChargingHubApi.ChargingHubApi {
    override def startSession(request: SessionDetails): IO[StatusException, StartSessionResponse] =
    zio.Random.nextBoolean.map(StartSessionResponse(_))
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
      ChargingHub.ApiImpl.layer,
      Bootstrap.tracingLayer,
      OTELTracer.default("charging-hub"),
    )


  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] =
    makeLaunchableServer(9001).launch
}
