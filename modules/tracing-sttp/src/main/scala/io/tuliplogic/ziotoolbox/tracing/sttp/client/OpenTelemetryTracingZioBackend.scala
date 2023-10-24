package io.tuliplogic.ziotoolbox.tracing.sttp.client

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{SpanKind, StatusCode}
import sttp.capabilities.zio.ZioStreams
import sttp.capabilities.{Effect, WebSockets}
import sttp.client3.httpclient.zio.SttpClient
import sttp.client3.{DelegateSttpBackend, Request, Response}
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator
import zio.telemetry.opentelemetry.tracing.{StatusMapper, Tracing}
import zio.{Task, ZIO}


//TODO: check this https://discord.com/channels/629491597070827530/639825316021272602/1161591495598551061

class OpenTelemetryTracingZioBackend(
                                      delegate: SttpClient,
                                      tracer: OpenTelemetryZioTracer,
                                      tracing: Tracing,
                                      baggage: Baggage
                                    ) extends DelegateSttpBackend[Task, ZioStreams with WebSockets](delegate) {
  def send[T, R >: ZioStreams with WebSockets with Effect[Task]](request: Request[T, R]): Task[Response[T]] = {
    val carrier = OutgoingContextCarrier.default()
    val tracingPropagator: TraceContextPropagator = TraceContextPropagator.default
    val baggagePropagator: BaggagePropagator = BaggagePropagator.default

    tracing.span(
      spanName = tracer.spanName(request),
      spanKind = SpanKind.CLIENT,
      statusMapper = StatusMapper.failureThrowable(_ => StatusCode.ERROR),
      attributes = tracer.commonAttributes.foldLeft(Attributes.builder())((builder, kv) => builder.put(kv._1, kv._2)).build()
    )(
      for {
        /* on the client side, i.e. when we call a server,
        we take an outgoing ctx propagator, we inject in it the baggage and tracing data. After this operation we
        are sure the propagator contains all the data we need to propagate to the client
        Then we map them to the transport layer (http headers)
        */
        _ <- tracing.inject(tracingPropagator, carrier)
        _ <- baggage.inject(baggagePropagator, carrier)
        _ <- ZIO.foreachDiscard(tracer.requestAttributes(request).toVector) {
          case (k, v) => tracing.setAttribute(k, v)
        }
        resp <- delegate.send(request.headers(carrier.kernel.toMap))
        _ <- ZIO.foreachDiscard(tracer.responseAttributes(resp).toVector) {
          case (k, v) => tracing.setAttribute(k, v)
        }
      } yield resp
    )
  }
}

object OpenTelemetryTracingZioBackend {
  def apply(
             other: SttpClient,
             tracing: Tracing,
             baggage: Baggage,
             tracer: OpenTelemetryZioTracer = OpenTelemetryZioTracer.Default
           ): OpenTelemetryTracingZioBackend =
    new OpenTelemetryTracingZioBackend(other, tracer, tracing, baggage)


}

/**
 * Implement your version of this trait to customize the tracing behaviour, in terms of span name and extra attributes
 */
trait OpenTelemetryZioTracer {
  def spanName[T](request: Request[T, Nothing]): String

  def commonAttributes: Map[String, String] = Map.empty

  def requestAttributes[T](request: Request[T, Nothing]): Map[String, String]

  def responseAttributes[T](response: Response[T]): Map[String, String]
}

object OpenTelemetryZioTracer {
  val Default: OpenTelemetryZioTracer = new OpenTelemetryZioTracer {
    override def spanName[T](request: Request[T, Nothing]): String = s"HTTP ${request.method.method}"

    override def requestAttributes[T](request: Request[T, Nothing]): Map[String, String] =
      Map(
        "http.method" -> request.method.method,
        "http.url" -> request.uri.toString()
      )

    override def responseAttributes[T](response: Response[T]): Map[String, String] = {
      Map(
        "http.status_code" -> response.code.code.toString
      )
    }
  }
}
