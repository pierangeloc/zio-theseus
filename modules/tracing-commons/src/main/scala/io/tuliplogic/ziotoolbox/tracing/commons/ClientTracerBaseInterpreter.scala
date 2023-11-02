package io.tuliplogic.ziotoolbox.tracing.commons

import zio.{UIO, ZIO}
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import scala.collection.mutable

trait ClientTracerBaseInterpreter[Req, Res, Transport, Interpretation] {
  def tracing: Tracing
  def baggage: Baggage
  def tracerAlgebra: TracerAlgebra[Req, Res]

  protected val tracingPropagator: TraceContextPropagator = TraceContextPropagator.default
  protected val baggagePropagator: BaggagePropagator = BaggagePropagator.default

  protected def beforeRequest(req: Req) = {

    val outgoingCarrier = OutgoingContextCarrier.default()

    ZIO.foreachDiscard(tracerAlgebra.requestAttributes(req).toVector) {
      case (k, v) => tracing.setAttribute(k, v)
    } *>
      tracing.inject(tracingPropagator, outgoingCarrier) *>
      baggage.inject(baggagePropagator, outgoingCarrier) *> ZIO.succeed(outgoingCarrier)
  }

  protected def afterResponse(res: Res) =
    ZIO.foreachDiscard(tracerAlgebra.responseAttributes(res).toVector) {
      case (k, v) => tracing.setAttribute(k, v)
    }

  protected def carrierToTransport(carrier: OutgoingContextCarrier[mutable.Map[String, String]]): Transport

  def interpretation: UIO[Interpretation]

}
