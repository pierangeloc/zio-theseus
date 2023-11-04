package io.tuliplogic.ziotoolbox.tracing.example

import io.tuliplogic.ziotoolbox.tracing.sttp.client.TracingSttpZioBackend
import sttp.client3.{DelegateSttpBackend, UriContext}
import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import zio.http.{HttpApp, Server}
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{Scope, Task, ULayer, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer}
import io.tuliplogic.ziotoolbox.tracing.sttp.server.{TapirTracingInterpretation, TapirTracingInterpreter}
import sttp.capabilities
import sttp.capabilities.zio.ZioStreams
import zio.telemetry.opentelemetry.context.ContextStorage

object HttpBackendApp extends ZIOAppDefault {

  //  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
  //    TracingInstruments.defaultBootstrap
  val port = 9002

  val zioHttpApp: ZIO[TapirTracingInterpretation, Nothing, HttpApp[CallRecordRepository, Throwable]] =
    for {
      tapirTracingInterpretation <- ZIO.service[TapirTracingInterpretation]
    } yield {
      import tapirTracingInterpretation._
      ZioHttpInterpreter().toHttp(
        StatusEndpoints.backendStatusEndpoint.zServerLogicTracing("backend-app-status-endpoint") { _ =>
          for {
            repo <- ZIO.service[CallRecordRepository]
            now  <- zio.Clock.instant
            _ <- repo.saveRecord(
                   CallRecordRepository.CallRecord(now, s"Http record ${now.toEpochMilli / 100}")
                 )
          } yield s"Saved http record $now"
        }
      )
    }

  val serverConfig: ULayer[Server.Config] = ZLayer.succeed(
    Server.Config.default.binding("localhost", port)
  )

  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] =
    ZIO.logInfo("Running HTTP app") *>
      zioHttpApp
        .flatMap(httpApp => Server.serve(httpApp.withDefaultErrorResponse))
        .provide(
          Server.live,
          serverConfig,
          CallRecordRepository.workingRepoLayer.orDie,
          Tracing.live,
          Baggage.logAnnotated,
          ContextStorage.fiberRef,
          JaegerTracer.default("http-backend-app"),
          TapirTracingInterpretation.layer()
        )
}

object HttpBackendClient {
  import sttp.tapir.client.sttp.SttpClientInterpreter

  val req = SttpClientInterpreter()
    .toRequest(
      StatusEndpoints.backendStatusEndpoint,
      Some(uri"http://localhost:${HttpBackendApp.port}")
    )
    .apply(())

  def tracingCall: ZIO[DelegateSttpBackend[Task, ZioStreams with capabilities.WebSockets], Throwable, Unit] = ZIO
    .serviceWithZIO[DelegateSttpBackend[Task, ZioStreams with capabilities.WebSockets]](_.send(req))
    .unit
}


