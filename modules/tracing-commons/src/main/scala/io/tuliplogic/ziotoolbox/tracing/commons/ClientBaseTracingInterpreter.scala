package io.tuliplogic.ziotoolbox.tracing.commons

import io.opentelemetry.api.trace.SpanKind
import zio.optics.opticsm.Lens
import zio.{UIO, ZIO}
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import zio.telemetry.opentelemetry.tracing.{StatusMapper, Tracing}
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import scala.collection.mutable

trait ClientBaseTracingInterpreter[Req, Res, Transport, Interpretation] {
  //TODO: make all fields protected, public only interpretation
  val spanKind: SpanKind
  def tracing: Tracing
  def baggage: Baggage
  def tracerAlgebra: TracerAlgebra[Req, Res]
//  def enrichWithTracingTransport[Request](req: Request, transport: Transport): UIO[Request]

  protected def carrierToTransport(carrier: OutgoingContextCarrier[mutable.Map[String, String]]): Transport
  protected val tracingPropagator: TraceContextPropagator = TraceContextPropagator.default

  protected val baggagePropagator: BaggagePropagator      = BaggagePropagator.default


  protected def spanOnRequest[Request, R, E, A](
    spanName: Request => String,
    enrichWithTracingTransport: (Request, Transport) => UIO[Request],
  )(request: Request, sendRequest: Request => ZIO[R, E, A], statusMapper: StatusMapper[E, A] = StatusMapper.default)
                                               (implicit ev: Request <:< Req): ZIO[R, E, A] =
    tracing.span(
      spanName = spanName(request),
      spanKind = spanKind,
      statusMapper = statusMapper,
      attributes = TracingUtils.makeAttributes(tracerAlgebra.requestAttributes(request).toList: _*)
    )(
      for {
        outgoingCarrier  <- ZIO.succeed(OutgoingContextCarrier.default())
        _                <- tracing.inject(tracingPropagator, outgoingCarrier)
        _                <- baggage.inject(baggagePropagator, outgoingCarrier)
        outgoingTransport = carrierToTransport(outgoingCarrier)
        reqWithTransport <- enrichWithTracingTransport(request, outgoingTransport)
        res              <- sendRequest(reqWithTransport)
      } yield res
    )

//    for {
//
//      _ <- ZIO.foreachDiscard(tracerAlgebra.requestAttributes(req).toVector) {
//        case (k, v) => tracing.setAttribute(k, v)
//      }
//
//      _ <- tracing.span()
//      outgoingTransport = carrierToTransport(outgoingCarrier)
//      reqWithTransport <- addTransport(req, outgoingTransport)
//      res <- effect(reqWithTransport)
//    } yield res

  //prototype:
  //for {
  //      res <- tracing.span(
  //        spanName = spanName(request),
  //        spanKind = SpanKind.CLIENT,
  //        statusMapper = StatusMapper.failureThrowable(_ => StatusCode.ERROR),
  //        attributes = attributes(request),
  //      )(
  //        for {
  //          outgoingCarrier <- beforeSendingRequest(request)
  //          res <- delegate.send(request.headers(carrierToTransport(outgoingCarrier): _*))
  //          _ <- afterReceivingResponse(res)
  //        } yield res
  //      )
  //    } yield res

  def interpretation: UIO[Interpretation]

}
