package io.tuliplogic.ziotoolbox.tracing.commons

import io.opentelemetry.api.trace.SpanKind
import zio.{IO, ZIO}
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.IncomingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

class ServerTracer[In, CarrierTransport, E, Out](tracing: Tracing, baggage: Baggage, incomingContextCarrier: IncomingContextCarrier[CarrierTransport]) {
  def extractServerSpan(spanName: String, in: In, logic: In => IO[E, Out] ): ZIO[Any, E, Out] =
    baggage.extract(BaggagePropagator.default, incomingContextCarrier) *>
      tracing.extractSpan(
        TraceContextPropagator.default,
        incomingContextCarrier,
        spanName,
        spanKind = SpanKind.SERVER
      )(logic(in))
}



