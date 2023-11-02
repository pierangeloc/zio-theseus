package io.tuliplogic.ziotoolbox.tracing.commons

import zio.UIO
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.IncomingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

trait ServerTracerBaseInterpreter[Req, Res, Transport, Interpretation] {
  def tracing: Tracing
  def baggage: Baggage
  def tracerAlgebra: TracerAlgebra[Req, Res]

  protected val tracingPropagator: TraceContextPropagator = TraceContextPropagator.default
  protected val baggagePropagator: BaggagePropagator = BaggagePropagator.default

  def transportToCarrier(t: Transport): IncomingContextCarrier[Map[String, String]]
  def interpretation: UIO[Interpretation]

}


