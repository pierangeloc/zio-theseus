package io.tuliplogic.ziotoolbox.tracing.sttp.client

import io.tuliplogic.ziotoolbox.tracing.commons.{ClientTracer, ClientTracerAlgebra}
import sttp.capabilities.zio.ZioStreams
import sttp.capabilities.{Effect, WebSockets}
import sttp.client3.httpclient.zio.SttpClient
import sttp.client3.{DelegateSttpBackend, Request, Response}
import zio.Task
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.tracing.Tracing


//TODO: check this https://discord.com/channels/629491597070827530/639825316021272602/1161591495598551061

class SttpClientTracer[R, T](tracing: Tracing, baggage: Baggage, tracerAlgebra: ClientTracerAlgebra[Request[_, _], Response[_]])
  extends ClientTracer[Request[T, R], Response[T], Request[_, _], Response[_]](tracing, baggage, _.asInstanceOf[Request[_, _]], _.asInstanceOf[Response[_]], tracerAlgebra) {
  override def enrichRequestWithCarriers(req: Request[T, R], carrierMap: Map[String, String]): Request[T, R] =
    req.headers(outgoingCarrier.kernel.toMap)

}

class OpenTelemetryTracingZioBackend(
                                      delegate: SttpClient,
                                      tracerAlgebra: ClientTracerAlgebra[Request[_, _], Response[_]],
                                      tracing: Tracing,
                                      baggage: Baggage
                                    ) extends DelegateSttpBackend[Task, ZioStreams with WebSockets](delegate) {
  def send[T, R >: ZioStreams with WebSockets with Effect[Task]](request: Request[T, R]): Task[Response[T]] = {

    val tracer = new SttpClientTracer[R, T](tracing, baggage, tracerAlgebra)

    tracer.traceRequest(request, request => delegate.send(request))
  }
}

object OpenTelemetryTracingZioBackend {
  def apply(
             other: SttpClient,
             tracing: Tracing,
             baggage: Baggage,
             tracerAlgebra: ClientTracerAlgebra[Request[_, _], Response[_]] = defaultTracerAlgebra,
           ): OpenTelemetryTracingZioBackend =
    new OpenTelemetryTracingZioBackend(other, tracerAlgebra, tracing, baggage)

  val tracerDsl = ClientTracerAlgebra.dsl[Request[_, _], Response[_]]

  val defaultTracerAlgebra: ClientTracerAlgebra[Request[_, _], Response[_]] = {
    import tracerDsl._
    spanName(req => s"HTTP ${req.showBasic}") &
      withRequestAttributes(req =>
        Map(
          "http.method" -> req.method.method,
          "http.url" -> req.uri.toString()
        )
      ) & withResponseAttributes(res =>
        Map(
          "http.status_code" -> res.code.code.toString
        )

    )
  }


}
