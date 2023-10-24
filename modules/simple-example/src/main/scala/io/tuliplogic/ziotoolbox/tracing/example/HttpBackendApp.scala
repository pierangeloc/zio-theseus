package io.tuliplogic.ziotoolbox.tracing.example

import io.tuliplogic.ziotoolbox.tracing.sttp.client.OpenTelemetryTracingZioBackend
import sttp.client3.UriContext
import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import zio.http.{HttpApp, Server}
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{Scope, Task, ULayer, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer}
import io.tuliplogic.ziotoolbox.tracing.sttp.server.TapirTracing._
import zio.telemetry.opentelemetry.context.ContextStorage

object HttpBackendApp extends ZIOAppDefault {

//  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
//    TracingInstruments.defaultBootstrap

  val port = 9002

  val zioHttpApp: HttpApp[CallRecordRepository with Baggage with Tracing, Throwable] =
    ZioHttpInterpreter().toHttp(
      StatusEndpoints.backendStatusEndpoint.zServerLogicTracing("backend-app-status-endpoint") { _ =>
        for {
          repo <- ZIO.service[CallRecordRepository]
          now <- zio.Clock.instant
          _ <- repo.saveRecord(CallRecordRepository.CallRecord(now, s"Http record ${now.toEpochMilli / 100}"))
        } yield s"Saved http record $now"
      }
    )

  val serverConfig: ULayer[Server.Config] = ZLayer.succeed(
    Server.Config.default.binding("localhost", port)
  )

  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] =
    ZIO.logInfo("Running HTTP app") *>
    Server
      .serve(zioHttpApp.withDefaultErrorResponse).provide(
        Server.live,
        serverConfig,
        CallRecordRepository.workingRepoLayer.orDie,
        Tracing.live,
        Baggage.logAnnotated,
        ContextStorage.fiberRef,
        JaegerTracer.default,
      )
}

object HttpBackendClient {
  import sttp.tapir.client.sttp.SttpClientInterpreter

  val req = SttpClientInterpreter().toRequest(StatusEndpoints.backendStatusEndpoint, Some(uri"http://localhost:${HttpBackendApp.port}"))
    .apply(())

  def call: Task[Unit] = HttpClientZioBackend().flatMap(
    be => be.send(req)
  ).unit

  def tracingCall: ZIO[OpenTelemetryTracingZioBackend, Throwable, Unit] = ZIO.serviceWithZIO[OpenTelemetryTracingZioBackend](_.send(req)).unit

}

object TestHttpClient extends ZIOAppDefault {
  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] = HttpBackendClient.call
}
