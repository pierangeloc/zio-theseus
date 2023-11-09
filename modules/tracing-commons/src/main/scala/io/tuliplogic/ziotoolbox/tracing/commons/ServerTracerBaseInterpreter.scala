package io.tuliplogic.ziotoolbox.tracing.commons

import io.opentelemetry.api.trace.{SpanContext, SpanKind}
import zio.{UIO, ZIO}
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.IncomingContextCarrier
import zio.telemetry.opentelemetry.tracing.{StatusMapper, Tracing}
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

trait ServerTracerBaseInterpreter[Req, Res, Transport, Interpretation] {
  val spanKind: SpanKind
  def tracing: Tracing
  def baggage: Baggage
  def tracerAlgebra: TracerAlgebra[Req, Res]

  protected val tracingPropagator: TraceContextPropagator = TraceContextPropagator.default
  protected val baggagePropagator: BaggagePropagator = BaggagePropagator.default

  protected def spanOnRequest[R, E, A](req: Req, transport: Transport)(spanName: String)(effect: ZIO[R, E, A], statusMapper: StatusMapper[E, A] = StatusMapper.default, links: Seq[SpanContext] = Nil): ZIO[R, E, A] = {
    for {
      carrier <- transportToCarrier(transport)
      _ <- baggage.extract(baggagePropagator, carrier)
      res <- tracing.extractSpan(
        propagator = tracingPropagator,
        carrier = carrier,
        spanName = spanName,
        spanKind = spanKind,
        attributes = TracingUtils.makeAttributes(tracerAlgebra.requestAttributes(req).toList: _*),
        statusMapper,
        links
      )(effect)
    } yield res
  }

  def transportToCarrier(t: Transport): UIO[IncomingContextCarrier[Map[String, String]]]
  def interpretation: UIO[Interpretation]

}


