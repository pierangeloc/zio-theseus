package io.tuliplogic.ziotoolbox.tracing.example

import io.opentelemetry.api.trace.SpanKind
import io.tuliplogic.ziotoolbox.tracing.commons.{Bootstrap, OTELTracer, TracingUtils}
import io.tuliplogic.ziotoolbox.tracing.example.proto.status_api.{GetStatusRequest, ZioStatusApi}
import io.tuliplogic.ziotoolbox.tracing.kafka.producer.ProducerTracing
import io.tuliplogic.ziotoolbox.tracing.kafka.producer.ProducerTracing.KafkaProducerTracer
import io.tuliplogic.ziotoolbox.tracing.sttp.client.{SttpClientTracingInterpreter, TracingSttpBackend}
import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.client3.logging.slf4j.Slf4jLogger
import sttp.client3.logging.{LogLevel, LoggingBackend}
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir._
import zio.http.{HttpApp, Server}
import zio.kafka.producer.Producer
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{Scope, ULayer, ZEnvironment, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer}

object ProxyApp extends ZIOAppDefault {

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
    Bootstrap.defaultBootstrap

  val port = 9003

  def performProxyCalls(parallel: Boolean): ZIO[Producer with ProducerTracing.KafkaProducerTracer with Tracing with Baggage with TracingSttpBackend, Throwable, Unit] = if (parallel) {
    ZIO.logInfo("Running parallel calls") *>
      ZIO.logAnnotate("parallel-calls", "true")(
        HttpBackendClient.tracingCall.timed.flatMap(o => ZIO.logInfo(s"http call - DONE - took ${o._1.toMillis} ms")) &>
        ZioStatusApi.GetStatusApiClient
        .getStatus(GetStatusRequest())
        .provideLayer(GrpcClient.clientLayer) *> ZIO.logInfo("grpc call - DONE") &>
        KafkaClient.produce.repeatN(5) *> ZIO.logInfo("kafka production - DONE")
      )
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

  val zioHttpApp: HttpApp[TracingSttpBackend with Tracing with Baggage with Producer with KafkaProducerTracer, Throwable] =
    ZioHttpInterpreter().toHttp(
      StatusEndpoints.proxyStatusesEndpoint.zServerLogic { qp =>
        val parallel = qp.get("parallel").contains("true")
        for {
          tracing <- ZIO.service[Tracing]
          baggage <- ZIO.service[Baggage]
          _ <- baggage.set("parallel-calls", parallel.toString)
          _ <- baggage.set("user-name", "Jack")
          r <- tracing.span("Perform-all-calls", spanKind = SpanKind.INTERNAL, attributes = TracingUtils.makeAttributes(
            "userId" -> "123",
            "username" -> "Johnny",
            "user-roles" -> "admin,superuser"
          ))(
            for {
              r <- performProxyCalls(parallel).orDie.timed
              _ <- ZIO.logInfo(s"performed proxy calls, it took ${r._1.toMillis} ms")
            } yield s"proxy call done"
          )
        } yield r
      }
    )

  val httpTracingLayer: ZLayer[Baggage with Tracing, Nothing, TracingSttpBackend] =
    ZLayer.makeSome[Baggage with Tracing, TracingSttpBackend](
      HttpClientZioBackend.layer().orDie.map(be => ZEnvironment(
        LoggingBackend(
          delegate = be.get,
          logger = new Slf4jLogger("sttp.client3.logging", be.get.responseMonad),
          logRequestHeaders = true,
          beforeRequestSendLogLevel = LogLevel.Info
        ))
      ),
      SttpClientTracingInterpreter.layer()
    )

  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] = {
    ZIO.logInfo("Running PROXY app") *>
    Server
      .serve(zioHttpApp.withDefaultErrorResponse).provide(
        Server.live,
        serverConfig,
        httpTracingLayer,
//        Tracing.live,
//        Baggage.logAnnotated,
//        ContextStorage.fiberRef,
        Bootstrap.tracingLayer,
        OTELTracer.default("proxy-app"),
        KafkaClient.producerLayer,
        KafkaProducerTracer.layer(),
      )
  }
}
