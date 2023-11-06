package io.tuliplogic.ziotoolbox.tracing.example

import io.tuliplogic.ziotoolbox.tracing.example.proto.status_api.{GetStatusRequest, StatusResponse, ZioStatusApi}
import io.tuliplogic.ziotoolbox.tracing.grpc.client.GrpcClientTracing
import io.tuliplogic.ziotoolbox.tracing.grpc.server.GrpcServerTracingInterpreter
import io.grpc.{ServerBuilder, StatusException}
import scalapb.zio_grpc.{RequestContext, Server, ServerLayer, ServiceList, ZTransform}
import zio._
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing

object GrpcBackendApp {
  val serverPort = 9001
}

object GrpcBackend2 extends ZIOAppDefault {
  //TODO: fix logs
//  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
//    TracingInstruments.defaultBootstrap

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

  val live: ZLayer[ZTransform[Any, RequestContext] with CallRecordRepository, Nothing, ZioStatusApi.GGetStatusApi[RequestContext, StatusException]] = ZLayer {
    GrpcServerTracingInterpreter.serviceWithTracing[CallRecordRepository, ZioStatusApi.GetStatusApi](
      evseLookup => new GetStatusApiImpl(evseLookup)
    )
  }

  private def makeServer(port: Int) =
    ZLayer.make[Server](
      serverLive(port),
      live,
      CallRecordRepository.workingRepoLayer,
      Tracing.live,
      Baggage.logAnnotated,
      ContextStorage.fiberRef,
      JaegerTracer.default("grpc-backend-app"),
      GrpcServerTracingInterpreter.layer()
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
    GrpcClientTracing.serviceClient("localhost", GrpcBackendApp.serverPort)(ch => ZioStatusApi.GetStatusApiClient.scoped(ch))
  }
}
