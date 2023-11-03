package io.tuliplogic.ziotoolbox.tracing.sttp.client

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{SpanKind, StatusCode}
import io.tuliplogic.ziotoolbox.tracing.commons.{TracerAlgebra, ClientTracerBaseInterpreter}
import sttp.capabilities.zio.ZioStreams
import sttp.capabilities.{Effect, WebSockets}
import sttp.client3.httpclient.zio.SttpClient
import sttp.client3.{DelegateSttpBackend, Request, Response}
import sttp.model.Header
import zio.{Task, UIO, ZIO}
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import zio.telemetry.opentelemetry.tracing.{StatusMapper, Tracing}

import scala.collection.mutable

//TODO: check this https://discord.com/channels/629491597070827530/639825316021272602/1161591495598551061

class TracingSttpZioBackend(
                             delegate: SttpClient,
                             val tracerAlgebra: TracerAlgebra[Request[_, _], Response[_]],
                             val tracing: Tracing,
                             val baggage: Baggage,
  ) extends ClientTracerBaseInterpreter[Request[_, _], Response[_], List[Header], DelegateSttpBackend[Task, ZioStreams with WebSockets]]  {

  override def carrierToTransport(carrier: OutgoingContextCarrier[mutable.Map[String, String]]): List[Header] =
    carrier.kernel.map(kv => Header(kv._1, kv._2)).toList

  override def interpretation: UIO[DelegateSttpBackend[Task, ZioStreams with WebSockets]] =
    ZIO.succeed(
      new DelegateSttpBackend[Task, ZioStreams with WebSockets](delegate) {


        override def send[T, R >: ZioStreams with WebSockets with Effect[Task]](
                                                                                 request: Request[T, R]
                                                                               ): Task[Response[T]] = {
          for {
            outgoingCarrier <- beforeSendingRequest(request)
            res <- tracing.span(
              spanName = tracerAlgebra.spanName(request),
              spanKind = SpanKind.CLIENT,
              statusMapper = StatusMapper.failureThrowable(_ => StatusCode.ERROR),
              attributes = tracerAlgebra
                .requestAttributes(request.headers(carrierToTransport(outgoingCarrier): _*))
                .foldLeft(Attributes.builder())((builder, kv) => builder.put(kv._1, kv._2))
                .build(),
            )(
              for {
                res <- delegate.send(request)
                _ <- afterReceivingResponse(res)
              } yield res
            )
          } yield res
        }
      }
    )

}

object TracingSttpZioBackend {
  def apply(
             other: SttpClient,
             tracing: Tracing,
             baggage: Baggage,
             tracerAlgebra: TracerAlgebra[Request[_, _], Response[_]] = defaultSttpClientTracerAlgebra,
           ): UIO[DelegateSttpBackend[Task, ZioStreams with WebSockets]] =
    new TracingSttpZioBackend(other, tracerAlgebra, tracing, baggage)
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
}
