package io.tuliplogic.ziotoolbox.tracing.commons

import io.opentelemetry.api.baggage.BaggageEntryMetadata
import io.opentelemetry.api.trace.SpanKind
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.IncomingContextCarrier
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator
import zio.telemetry.opentelemetry.tracing.{StatusMapper, Tracing}
import zio.{LogAnnotation, Trace, UIO, ZIO}

trait ServerTracerBaseInterpreter[Req, Res, Transport, Interpretation] {
  val spanKind: SpanKind
  def tracing: Tracing
  def baggage: Baggage
  def tracerAlgebra: TracerAlgebra[Req, Res]

  protected val tracingPropagator: TraceContextPropagator = TraceContextPropagator.default
  protected val baggagePropagator: BaggagePropagator = BaggagePropagator.default

  /*
   * This fixes a bug in zio-opentelemetry where the baggage propagator doesn't extract the baggage into log annotations
   */
  private def extractLogAnnotations(baggage: Baggage)(implicit trace: Trace) =
      for {
        baggageEntries <- baggage.getAllWithMetadata
        logEntries = baggageEntries
          .collect {
            case (k, (v, metadata)) if metadata == BaggageEntryMetadata.create("zio log annotation").getValue =>
              (k, v)
          }
      } yield logEntries.toVector.map { case (k, v) => LogAnnotation(k, v) }.toSet

  protected def spanOnRequest[R, E, A](req: Req, transport: Transport)(spanName: String)(effect: ZIO[R, E, A], statusMapper: StatusMapper[E, A] = StatusMapper.default): ZIO[R, E, A] = {
    for {
      carrier <- transportToCarrier(transport)
      _ <- baggage.extract(baggagePropagator, carrier)
      logAnnotations <- extractLogAnnotations(baggage)
      res <- tracing.extractSpan(
        propagator = tracingPropagator,
        carrier = carrier,
        spanName = spanName,
        spanKind = spanKind,
        attributes = TracingUtils.makeAttributes(tracerAlgebra.requestAttributes(req).toList: _*),
        statusMapper
      )(ZIO.logAnnotate(logAnnotations)(effect))
    } yield res
  }

  def transportToCarrier(t: Transport): UIO[IncomingContextCarrier[Map[String, String]]]
  def interpretation: UIO[Interpretation]

}


