package io.tuliplogic.ziotoolbox.tracing.example

import io.tuliplogic.ziotoolbox.tracing.example.proto.status_api.{GetStatusRequest, ZioStatusApi}
import io.tuliplogic.ziotoolbox.tracing.sttp.client.OpenTelemetryTracingZioBackend
import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir._
import zio.http.{HttpApp, Server}
import zio.kafka.producer.Producer
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{Scope, ULayer, URLayer, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer}

object ProxyApp extends ZIOAppDefault {

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
    TracingInstruments.defaultBootstrap
  val port = 9003

  def performProxyCalls(parallel: Boolean) = if (parallel) {
    ZIO.logInfo("Running parallel calls") *>
    HttpBackendClient.tracingCall.timed.flatMap(o => ZIO.logInfo(s"http call - DONE - took ${o._1.toMillis} ms")) &>
      ZioStatusApi.GetStatusApiClient
        .getStatus(GetStatusRequest())
        .provideLayer(GrpcClient.clientLayer) *> ZIO.logInfo("grpc call - DONE") &>
        KafkaClient.produce *> ZIO.logInfo("kafka production - DONE")
  }
  else {
    for {
      _ <- ZIO.logInfo("Running sequential calls")
      _ <- HttpBackendClient.tracingCall.timed.flatMap(o => ZIO.logInfo(s"http call - DONE - took ${o._1.toMillis} ms"))
      _ <- ZioStatusApi.GetStatusApiClient
        .getStatus(GetStatusRequest())
        .provideLayer(GrpcClient.clientLayer) *> ZIO.logInfo("grpc call - DONE")
      _ <- KafkaClient.produce *> ZIO.logInfo("kafka production - DONE")
    } yield ()
  }

  val serverConfig: ULayer[Server.Config] = ZLayer.succeed(
    Server.Config.default.binding("localhost", port)
  )

  val zioHttpApp: HttpApp[OpenTelemetryTracingZioBackend with Tracing with Baggage with Producer, Throwable] =
    ZioHttpInterpreter().toHttp(
      StatusEndpoints.proxyStatusesEndpoint.zServerLogic { qp =>
        val parallel = qp.get("parallel").contains("true")
        for {
          tracing <- ZIO.service[Tracing]
          r <- tracing.span("proxy-call")(
            for {
              r <- performProxyCalls(parallel).orDie.timed
              _ <- ZIO.logInfo(s"performed proxy calls, it took ${r._1.toMillis} ms")
            } yield s"proxy call done"
          )
        } yield r
      }
    )

  val httpTracingLayer: URLayer[Tracing with Baggage, OpenTelemetryTracingZioBackend] = ZLayer.fromZIO {
    for {
      be <- HttpClientZioBackend().orDie
      tracing <- ZIO.service[Tracing]
      baggage <- ZIO.service[Baggage]
    } yield TracingSttpBackend.defaultTracingBackend(be, tracing, baggage)
  }

  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] = {
    ZIO.logInfo("Running PROXY app") *>
    Server
      .serve(zioHttpApp.withDefaultErrorResponse).provide(
        Server.live,
        serverConfig,
        httpTracingLayer,
        Tracing.live,
        Baggage.logAnnotated,
        TracingInstruments.fiberRefContextStorage,
        JaegerTracer.default,
        KafkaClient.producerLayer
      )
  }
}
