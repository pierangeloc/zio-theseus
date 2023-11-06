package io.tuliplogic.ziotoolbox.tracing.sttp.client

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{SpanKind, StatusCode}
import io.tuliplogic.ziotoolbox.tracing.commons.{ClientBaseTracingInterpreter, TracerAlgebra}
import sttp.capabilities.zio.ZioStreams
import sttp.capabilities.{Effect, WebSockets}
import sttp.client3.httpclient.zio.SttpClient
import sttp.client3.{DelegateSttpBackend, Request, Response, SttpBackend}
import sttp.model.Header
import zio.{Task, UIO, ZIO, ZLayer}
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import zio.telemetry.opentelemetry.tracing.{StatusMapper, Tracing}

import scala.collection.mutable

//TODO: check this https://discord.com/channels/629491597070827530/639825316021272602/1161591495598551061

class SttpClientTracingInterpreter(
                             delegate: SttpClient,
                             val tracerAlgebra: TracerAlgebra[Request[_, _], Response[_]],
                             val tracing: Tracing,
                             val baggage: Baggage,
  ) extends ClientBaseTracingInterpreter[Request[_, _], Response[_], List[Header], TracingSttpBackend]  {

  override def carrierToTransport(carrier: OutgoingContextCarrier[mutable.Map[String, String]]): List[Header] =
    carrier.kernel.map(kv => Header(kv._1, kv._2)).toList

  override def interpretation: UIO[TracingSttpBackend] =
    ZIO.succeed(
      new TracingSttpBackend(
        delegate,
        tracerAlgebra.spanName(_),
        beforeSendingRequest,
        afterReceivingResponse,
        tracerAlgebra.requestAttributes(_).foldLeft(Attributes.builder())((builder, kv) => builder.put(kv._1, kv._2)).build(),
        enrichRequestHeaders = identity,
        carrierToTransport,
        tracing,
        baggage
      )
    )

}

class TracingSttpBackend(
  delegate: SttpBackend[Task, ZioStreams with WebSockets],
  spanName: Request[_, _] => String,
  beforeSendingRequest: Request[_, _] => UIO[OutgoingContextCarrier[mutable.Map[String, String]]],
  afterReceivingResponse: Response[_] => UIO[Unit],
  attributes: Request[_, _] => Attributes,
  enrichRequestHeaders: Request[_, _] => Request[_, _],
  carrierToTransport: OutgoingContextCarrier[mutable.Map[String, String]] => List[Header],
  val tracing: Tracing,
  val baggage: Baggage) extends DelegateSttpBackend[Task, ZioStreams with WebSockets](delegate) {
  override def send[T, R >: ZioStreams with WebSockets with Effect[Task]](
                                                                           request: Request[T, R]
                                                                         ): Task[Response[T]] = {
    for {
      _ <- ZIO.logInfo(s"TRACING_TROUBLESHOOTING: about to send request ${request.showBasic}")
      outgoingCarrier <- beforeSendingRequest(request)
      res <- tracing.span(
        spanName = spanName(request),
        spanKind = SpanKind.CLIENT,
        statusMapper = StatusMapper.failureThrowable(_ => StatusCode.ERROR),
        attributes = attributes(request),
      )(
        for {
          res <- delegate.send(request.headers(carrierToTransport(outgoingCarrier): _*))
          _ <- afterReceivingResponse(res)
        } yield res
      )
    } yield res
  }
}

object SttpClientTracingInterpreter {
  def make(
             other: SttpClient,
             tracing: Tracing,
             baggage: Baggage,
             tracerAlgebra: TracerAlgebra[Request[_, _], Response[_]],
           ): UIO[TracingSttpBackend] =
    new SttpClientTracingInterpreter(other, tracerAlgebra, tracing, baggage)
      .interpretation

  val tracerDsl = TracerAlgebra.dsl[Request[_, _], Response[_]]

  val defaultSttpClientTracerAlgebra: TracerAlgebra[Request[_, _], Response[_]] = {
    import tracerDsl._
    spanName(req => s"HTTP ${req.showBasic}") &
      withRequestAttributes(req =>
        Map(
          "http.method" -> req.method.method,
          "http.url" -> req.uri.toString(),
        )
      ) & withResponseAttributes(res =>
      Map(
        "http.status_code" -> res.code.code.toString
      )
    )
  }

  def layer(tracerAlgebra: TracerAlgebra[Request[_, _], Response[_]] = defaultSttpClientTracerAlgebra): ZLayer[Baggage with Tracing with SttpClient, Nothing, TracingSttpBackend] =
    ZLayer.fromZIO {
      for {
        underlyingClient <- ZIO.service[SttpClient]
        tracing <- ZIO.service[Tracing]
        baggage <- ZIO.service[Baggage]
        backend <- make(underlyingClient, tracing, baggage, tracerAlgebra)
      } yield backend
    }
}
