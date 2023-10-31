package io.tuliplogic.ziotoolbox.tracing.commons

import zio.ZIO
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

trait ClientTracerBaseInterpreter[Req, Res, Transport] {
  def tracing: Tracing
  def baggage: Baggage
  def tracerAlgebra: ClientTracerAlgebra[Req, Res]

  protected val tracingPropagator: TraceContextPropagator = TraceContextPropagator.default
  protected val baggagePropagator: BaggagePropagator = BaggagePropagator.default

  protected def before(req: Req) = {

    val outgoingCarrier = OutgoingContextCarrier.default()

    ZIO.foreachDiscard(tracerAlgebra.requestAttributes(req).toVector) {
      case (k, v) => tracing.setAttribute(k, v)
    } *>
      tracing.inject(tracingPropagator, outgoingCarrier) *>
      baggage.inject(baggagePropagator, outgoingCarrier)
  }

  protected def after(res: Res) =
    ZIO.foreachDiscard(tracerAlgebra.responseAttributes(res).toVector) {
      case (k, v) => tracing.setAttribute(k, v)
    }

}
