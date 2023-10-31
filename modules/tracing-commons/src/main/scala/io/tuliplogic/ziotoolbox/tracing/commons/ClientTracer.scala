package io.tuliplogic.ziotoolbox.tracing.commons

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{SpanKind, StatusCode}
import zio.{IO, ZIO}
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import zio.telemetry.opentelemetry.tracing.{StatusMapper, Tracing}
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator


//TODO: use the algebra and then make different interpreters. This component can't be solely adapted. Make a base interpreter that provides the before and after methods, and move from there. One interpreter for sttp client, one for server, one for grpc client, one for server etc...
/**
 * A Generic tracer for an RPC style interaction from the client POV.
 * Provides methods to set tracing context into the outgoing carrier
 * type params:
 * Req: the type of the request
 * Res: the type of the response
 * UniformReq: a type derived from the request type, but without too much information about its internals
 * UniformRes: a type derived from the response type, but without too much information about its internals
 */
abstract class ClientTracer[Req, Res, UniformReq, UniformRes](tracing: Tracing, baggage: Baggage, uniformReq: Req => UniformReq, uniformRes: Res => UniformRes, tracerAlgebra: ClientTracerAlgebra[UniformReq, UniformRes]) {
  protected val outgoingCarrier = OutgoingContextCarrier.default()
  protected val tracingPropagator: TraceContextPropagator = TraceContextPropagator.default
  protected val baggagePropagator: BaggagePropagator = BaggagePropagator.default

  private def before(req: UniformReq) =
    ZIO.foreachDiscard(tracerAlgebra.requestAttributes(req).toVector) {
      case (k, v) => tracing.setAttribute(k, v)
    } *>
    tracing.inject(tracingPropagator, outgoingCarrier) *>
    baggage.inject(baggagePropagator, outgoingCarrier)

  private def after(res: UniformRes) =
    ZIO.foreachDiscard(tracerAlgebra.responseAttributes(res).toVector) {
      case (k, v) => tracing.setAttribute(k, v)
    }

  //this should do request.headers(carrier.kernel.toMap) for sttp
  // and something similar for grpc
  def enrichRequestWithCarriers(req: Req, carrierMap: Map[String, String]): Req

  def traceRequest[E <: Throwable](req: Req, call: Req => IO[E, Res]): IO[E, Res] = {
    val uReq = uniformReq(req)
    tracing.span(
      spanName = tracerAlgebra.spanName(uReq),
      spanKind = SpanKind.CLIENT,
      statusMapper = StatusMapper.failureThrowable(_ => StatusCode.ERROR),
      attributes = tracerAlgebra.requestAttributes(uReq).foldLeft(Attributes.builder())((builder, kv) => builder.put(kv._1, kv._2)).build()
    )(
      for {
        _   <- before(uReq)
        res <-   call(req)
        uRes = uniformRes(res)
        _ <- after(uRes)
      } yield res
    )
  }
}

trait ClientTracerAlgebra[Req, Res] {
  def spanName(request: Req): String
  def requestAttributes(req: Req): Map[String, String]
  def responseAttributes(res: Res): Map[String, String]

  def &(other: ClientTracerAlgebra[Req, Res]): ClientTracerAlgebra[Req, Res] = ClientTracerAlgebra.Composed(this, other)
}

object ClientTracerAlgebra {
  case class Const[Req, Res](attrs: Map[String, String]) extends ClientTracerAlgebra[Req, Res] {
    override def spanName(request: Req): String = ""
    override def requestAttributes(req: Req): Map[String, String] = attrs
    override def responseAttributes(res: Res): Map[String, String] = attrs
  }

  case class Composed[Req, Res](before: ClientTracerAlgebra[Req, Res], after: ClientTracerAlgebra[Req, Res]) extends ClientTracerAlgebra[Req, Res] {
    override def requestAttributes(req: Req): Map[String, String] = before.requestAttributes(req)
    override def responseAttributes(res: Res): Map[String, String] = after.responseAttributes(res)
    override def spanName(request: Req): String = after.spanName(request)
  }

  case class SpanName[Req, Res](extractSpanName: Req => String) extends ClientTracerAlgebra[Req, Res] {
    override def requestAttributes(req: Req): Map[String, String] = Map.empty
    override def responseAttributes(res: Res): Map[String, String] = Map.empty
    override def spanName(request: Req): String = extractSpanName(request)
  }

  case class WithRequestAttributes[Req, Res](extractRequestAttributes: Req => Map[String, String]) extends ClientTracerAlgebra[Req, Res] {
    override def requestAttributes(req: Req): Map[String, String] = extractRequestAttributes(req)
    override def responseAttributes(res: Res): Map[String, String] = Map.empty
    override def spanName(request: Req): String = ""
  }

  case class WithResponseAttributes[Req, Res](extractResponseAttributes: Res => Map[String, String]) extends ClientTracerAlgebra[Req, Res] {
    override def requestAttributes(req: Req): Map[String, String] = Map.empty
    override def responseAttributes(res: Res): Map[String, String] = extractResponseAttributes(res)
    override def spanName(request: Req): String = ""
  }


  class Dsl[Req, Res] {

    def const(attrs: Map[String, String] = Map()): ClientTracerAlgebra[Req, Res] = Const[Req, Res](attrs)

    def spanName(extractSpanName: Req => String): ClientTracerAlgebra[Req, Res] = SpanName[Req, Res](extractSpanName)

    def withRequestAttributes(extractRequestAttributes: Req => Map[String, String]): ClientTracerAlgebra[Req, Res] = WithRequestAttributes[Req, Res](extractRequestAttributes)

    def withResponseAttributes(extractResponseAttributes: Res => Map[String, String]): ClientTracerAlgebra[Req, Res] = WithResponseAttributes[Req, Res](extractResponseAttributes)
  }


  def dsl[Req, Res] = new Dsl[Req, Res]
}


trait ClientTracingInfoExtractor[Req, Res] {
  def spanName(request: Req): String
  def commonAttributes: Map[String, String] = Map.empty
  def requestAttributes(request: Req): Map[String, String]
  def responseAttributes(response: Res): Map[String, String]
}

