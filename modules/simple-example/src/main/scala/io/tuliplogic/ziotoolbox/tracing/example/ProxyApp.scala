package io.tuliplogic.ziotoolbox.tracing.example

import io.tuliplogic.ziotoolbox.tracing.example.proto.status_api.{GetStatusRequest, ZioStatusApi}
import io.tuliplogic.ziotoolbox.tracing.kafka.producer.ProducerTracing
import io.tuliplogic.ziotoolbox.tracing.kafka.producer.ProducerTracing.KafkaRecordTracer
import io.tuliplogic.ziotoolbox.tracing.sttp.client.TracingSttpZioBackend
import sttp.capabilities
import sttp.capabilities.zio.ZioStreams
import sttp.client3.DelegateSttpBackend
import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir._
import zio.http.{HttpApp, Server}
import zio.kafka.producer.Producer
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{Scope, Task, ULayer, URLayer, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer}

object ProxyApp extends ZIOAppDefault {

//  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
//    TracingInstruments.defaultBootstrap
  val port = 9003

  def performProxyCalls(parallel: Boolean): ZIO[Producer with ProducerTracing.KafkaRecordTracer with Tracing with Baggage with DelegateSttpBackend[Task, ZioStreams with capabilities.WebSockets], Throwable, Unit] = if (parallel) {
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

  val zioHttpApp: HttpApp[DelegateSttpBackend[Task, ZioStreams with capabilities.WebSockets] with Tracing with Baggage with Producer with KafkaRecordTracer, Throwable] =
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

  val httpTracingLayer: ZLayer[Baggage with Tracing, Nothing, DelegateSttpBackend[Task, ZioStreams with capabilities.WebSockets]] = ZLayer.fromZIO {
    for {
      be <- HttpClientZioBackend().orDie
      tracing <- ZIO.service[Tracing]
      baggage <- ZIO.service[Baggage]
      be <- TracingSttpZioBackend(be, tracing, baggage)
    } yield be
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
        ContextStorage.fiberRef,
        JaegerTracer.default,
        KafkaClient.producerLayer,
        KafkaRecordTracer.layer(),

      )
  }
}
