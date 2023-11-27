package io.tuliplogic.ziotoolbox.tracing.sttp.client

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{SpanKind, StatusCode}
import io.opentelemetry.semconv.{ResourceAttributes, SemanticAttributes}
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

  override val spanKind: SpanKind = SpanKind.CLIENT

  override def carrierToTransport(carrier: OutgoingContextCarrier[mutable.Map[String, String]]): List[Header] =
    carrier.kernel.map(kv => Header(kv._1, kv._2)).toList

  override def interpretation: UIO[TracingSttpBackend] =
    ZIO.succeed(
      new TracingSttpBackend(
        delegate
      ) {
        override def send[T, R >: ZioStreams with WebSockets with Effect[Task]](request: Request[T, R]): Task[Response[T]] = {
          def enrichWithTracingTransport(request: Request[T, R], headers: List[Header]): UIO[Request[T, R]] = {
            ZIO.succeed(request.headers(headers: _*))
          }

          spanOnRequest[Request[T, R], Any, Throwable, Response[T]](
            request => tracerAlgebra.spanName(request),
            enrichWithTracingTransport
          )(request, (r: Request[T, R]) => delegate.send(r), StatusMapper.failureThrowable(_ => StatusCode.ERROR))
        }
      }
    )

}

abstract class TracingSttpBackend(
  delegate: SttpBackend[Task, ZioStreams with WebSockets]
  ) extends DelegateSttpBackend[Task, ZioStreams with WebSockets](delegate){}

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
    def showShort(req: Request[_, _]) = s"${req.method} ${req.uri.copy(scheme = None, authority = None, fragmentSegment = None).toString}"

    spanName(req => s"HTTP ${showShort(req)}") &
      requestAttributes(req =>
        Map(
          SemanticAttributes.HTTP_REQUEST_METHOD.getKey -> req.method.method,
          SemanticAttributes.URL_FULL.getKey -> req.uri.toString(),
          SemanticAttributes.URL_PATH.getKey -> req.uri.path.mkString("/"),
          SemanticAttributes.SERVER_ADDRESS.getKey -> req.uri.host.getOrElse("unknown"),
          SemanticAttributes.SERVER_PORT.getKey -> req.uri.port.map(_.toString).getOrElse("unknown"),
          SemanticAttributes.HTTP_ROUTE.getKey -> req.uri.path.mkString("/"),
          ResourceAttributes.OTEL_SCOPE_NAME.getKey -> "zio-sttp-client",
          "some.custom.attribute" -> "some.custom.value"
        )
      ) & responseAttributes(res =>
      Map(
        SemanticAttributes.HTTP_RESPONSE_STATUS_CODE.getKey -> res.code.code.toString
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
