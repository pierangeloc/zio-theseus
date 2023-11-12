package io.tuliplogic.ziotoolbox.tracing.example

import io.tuliplogic.ziotoolbox.tracing.commons.{Bootstrap, OTELTracer}
import io.tuliplogic.ziotoolbox.tracing.sttp.client.TracingSttpBackend
import io.tuliplogic.ziotoolbox.tracing.sttp.server.TapirTracingEndpoint
import sttp.client3.UriContext
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import zio.http.{HttpApp, Server}
import zio.{Scope, ULayer, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer}

object HttpBackendApp extends ZIOAppDefault {

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
    Bootstrap.defaultBootstrap
  val port = 9002

  val zioHttpApp: ZIO[TapirTracingEndpoint, Nothing, HttpApp[CallRecordRepository, Throwable]] =
    for {
      tapirTracingInterpretation <- ZIO.service[TapirTracingEndpoint]
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
//          Tracing.live,
//          Baggage.logAnnotated,
//          ContextStorage.fiberRef,
          Bootstrap.tracingLayer,
          OTELTracer.default("http-backend-app"),
          TapirTracingEndpoint.layer()
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

  def tracingCall: ZIO[TracingSttpBackend, Throwable, Unit] = ZIO
    .serviceWithZIO[TracingSttpBackend](_.send(req))
    .unit
}


