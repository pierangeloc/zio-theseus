package io.tuliplogic.ziotoolbox.tracing.example

import io.grpc.{ServerBuilder, StatusException}
import io.tuliplogic.ziotoolbox.tracing.commons.{Bootstrap, OTELTracer}
import io.tuliplogic.ziotoolbox.tracing.example.proto.status_api.{GetStatusRequest, StatusResponse, ZioStatusApi}
import io.tuliplogic.ziotoolbox.tracing.grpc.client.GrpcClientTracing
import io.tuliplogic.ziotoolbox.tracing.grpc.server.GrpcServerTracingInterpreter
import scalapb.zio_grpc.{RequestContext, Server, ServerLayer, ServiceList}
import zio._
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.tracing.Tracing

object GrpcBackendApp {
  val serverPort = 9001
}

object GrpcBackend2 extends ZIOAppDefault {
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
    Bootstrap.defaultBootstrap

  class GetStatusApiImpl(repo: CallRecordRepository) extends ZioStatusApi.GetStatusApi {

    override def getStatus(request: GetStatusRequest): IO[StatusException, StatusResponse] =
      for {
        _ <- ZIO.logInfo("Getting status...")
        now <- zio.Clock.instant
        res <- repo
          .saveRecord(CallRecordRepository.CallRecord(now, s"Grpc record ${now.toEpochMilli / 100}"))
          .catchAllCause(cause => ZIO.logErrorCause(cause))
          .as(StatusResponse(value = "OK"))
        _ <- zio.Console.printLine("Status retrieved and operation saved").orDie
      } yield res
  }

  private def serverLive(
    port: Int
  ): ZLayer[ZioStatusApi.GGetStatusApi[RequestContext, StatusException], Throwable, Server] = {
    val serviceList = ServiceList.addFromEnvironment[ZioStatusApi.GGetStatusApi[RequestContext, StatusException]]
    ServerLayer.fromServiceList(ServerBuilder.forPort(port), serviceList)
  }

  val serviceLayer = GrpcServerTracingInterpreter.serviceWithTracing[CallRecordRepository, ZioStatusApi.GetStatusApi](
    callRecordRepository => new GetStatusApiImpl(callRecordRepository)
  )

  private def makeServer(port: Int) =
    ZLayer.make[Server](
      serverLive(port),
      serviceLayer,
      CallRecordRepository.workingRepoLayer,
//      Tracing.live,
//      Baggage.logAnnotated,
//      ContextStorage.fiberRef,
      Bootstrap.tracingLayer,
      OTELTracer.default("grpc-backend-app"),
    )

  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] = {
    ZIO.logInfo("Running GRPC app") *>
    makeServer(GrpcBackendApp.serverPort).launch
  }
}

object GrpcClient {
  def getGrpcStatus(): ZIO[ZioStatusApi.GetStatusApiClient, StatusException, StatusResponse] =
    ZioStatusApi.GetStatusApiClient.getStatus(GetStatusRequest())

  val clientLayer: ZLayer[Tracing with Baggage, Throwable, ZioStatusApi.GetStatusApiClient]= ZLayer.scoped {
    GrpcClientTracing.tracedClient("localhost", GrpcBackendApp.serverPort)(ch => ZioStatusApi.GetStatusApiClient.scoped(ch))
  }
}
