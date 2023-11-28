package io.tuliplogic.ziotoolbox.tracing.sttp.server

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.semconv.{ResourceAttributes, SemanticAttributes}
import io.tuliplogic.ziotoolbox.tracing.commons.{ServerTracerBaseInterpreter, TracerAlgebra}
import sttp.client3.UriContext
import sttp.model.{Header, Method, QueryParams, Uri}
import sttp.tapir.{AttributeKey, Endpoint}
import sttp.tapir.model.{ConnectionInfo, ServerRequest}
import sttp.tapir.ztapir._
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.context.IncomingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{UIO, ZIO, ZLayer}

trait TapirServerTracer { interpretation =>
  protected def zServerLogicTracing[
    R,
    SECURITY_INPUT,
    INPUT,
    ERROR_OUTPUT,
    OUTPUT,
    C
  ](
    e: Endpoint[SECURITY_INPUT, INPUT, ERROR_OUTPUT, OUTPUT, C]
  )(
    spanName: String
  )(
    logic: INPUT => ZIO[R, ERROR_OUTPUT, OUTPUT]
  )(implicit
    aIsUnit: SECURITY_INPUT =:= Unit
  ): ZServerEndpoint[R, C]

  protected def serverLogicTracing[R, SECURITY_INPUT, PRINCIPAL, INPUT, ERROR_OUTPUT, OUTPUT, C](
    e: ZPartialServerEndpoint[R, SECURITY_INPUT, PRINCIPAL, INPUT, ERROR_OUTPUT, OUTPUT, C]
  )(spanName: String)(
    logic: PRINCIPAL => INPUT => ZIO[R, ERROR_OUTPUT, OUTPUT]
  ): sttp.tapir.ztapir.ZServerEndpoint[R, C]

  implicit class TracingZEndpoint[SECURITY_INPUT, INPUT, ERROR_OUTPUT, OUTPUT, C](
    e: Endpoint[SECURITY_INPUT, INPUT, ERROR_OUTPUT, OUTPUT, C]
  ) {
    def zServerLogicTracing[R](
      spanName: String
    )(
      logic: INPUT => ZIO[R, ERROR_OUTPUT, OUTPUT]
    )(implicit
      aIsUnit: SECURITY_INPUT =:= Unit
    ): ZServerEndpoint[R, C] =
      interpretation.zServerLogicTracing[R, SECURITY_INPUT, INPUT, ERROR_OUTPUT, OUTPUT, C](e)(spanName)(logic)
  }

  implicit class TracingPartialServerEndpoint[
    R,
    SECURITY_INPUT,
    PRINCIPAL,
    INPUT,
    ERROR_OUTPUT,
    OUTPUT,
    C
  ](
    e: ZPartialServerEndpoint[R, SECURITY_INPUT, PRINCIPAL, INPUT, ERROR_OUTPUT, OUTPUT, C]
  ) {
    def serverLogicTracing(
      spanName: String
    )(
      logic: PRINCIPAL => INPUT => ZIO[R, ERROR_OUTPUT, OUTPUT]
    ): ZServerEndpoint[R, C] =
      interpretation.serverLogicTracing(e)(spanName)(logic)
  }

}

object TapirServerTracer {

  val tracerDsl = TracerAlgebra.dsl[ServerRequest, Any]

  val defaultTapirServerTracerAlgebra: TracerAlgebra[ServerRequest, Any] = {
    import tracerDsl._
    spanName(serverRequest => serverRequest.showShort) &
    requestAttributes(req =>
      Map(
        SemanticAttributes.HTTP_REQUEST_METHOD.getKey -> req.method.method,
        SemanticAttributes.URL_FULL.getKey -> req.uri.toString(),
        SemanticAttributes.URL_PATH.getKey -> req.uri.path.mkString("/"),
        SemanticAttributes.SERVER_ADDRESS.getKey -> req.uri.host.getOrElse("unknown"),
        SemanticAttributes.SERVER_PORT.getKey -> req.uri.port.map(_.toString).getOrElse("unknown"),
        SemanticAttributes.HTTP_ROUTE.getKey -> req.uri.path.mkString("/"),
        ResourceAttributes.OTEL_SCOPE_NAME.getKey -> "zio-http-server",

      )
    )
  }

  def layer(
    tracerAlgebra: TracerAlgebra[ServerRequest, Any] = defaultTapirServerTracerAlgebra
  ): ZLayer[Baggage with Tracing, Nothing, TapirServerTracer] =
    ZLayer.fromZIO {
      for {
        tracing        <- ZIO.service[Tracing]
        baggage        <- ZIO.service[Baggage]
        interpreter     = new TapirServerTracingInterpreter(tracerAlgebra, tracing, baggage)
        interpretation <- interpreter.interpretation
      } yield interpretation
    }

}

class TapirServerTracingInterpreter(
  val tracerAlgebra: TracerAlgebra[ServerRequest, Any],
  val tracing: Tracing,
  val baggage: Baggage
) extends ServerTracerBaseInterpreter[ServerRequest, Any, List[Header], TapirServerTracer] {

  override val spanKind: SpanKind = SpanKind.SERVER
  override val title: String = "Tapir Server"
  override val description: String = "Traces the processing of a Tapir server endpoint"
  override val exampleRequest: ServerRequest = new  ServerRequest {
    override def protocol: String = "https"

    override def connectionInfo: ConnectionInfo = ConnectionInfo(None, None, None)

    override def underlying: Any = ()

    override def pathSegments: List[String] = "api" :: "v1" :: Nil

    override def queryParameters: QueryParams = QueryParams.fromSeq(List(("param1", "value1"), ("param2", "value2")))

    override def attribute[T](k: AttributeKey[T]): Option[T] = None

    override def attribute[T](k: AttributeKey[T], v: T): ServerRequest = this

    override def withUnderlying(underlying: Any): ServerRequest = this

    override def method: Method = Method.GET

    override def uri: Uri = uri"https://example.com/api/v1?param1=value1&param2=value2"

    override def headers: Seq[Header] = Nil
  }
  override val exampleResponse: Any = ()

  override def transportToCarrier(headers: List[Header]): UIO[IncomingContextCarrier[Map[String, String]]] =
    ZIO.succeed(
      new IncomingContextCarrier[Map[String, String]] {
        val safeHeaders =
          headers
            .filter(h => !sttp.model.HeaderNames.SensitiveHeaders.contains(h.name))
            .map(h => h.name -> h.value)
            .toMap

        override def getAllKeys(carrier: Map[String, String]): Iterable[String] = safeHeaders.keys

        override def getByKey(carrier: Map[String, String], key: String): Option[String] =
          safeHeaders.get(key)

        override val kernel: Map[String, String] = safeHeaders
      }
    )

  override def interpretation: UIO[TapirServerTracer] = ZIO.succeed(
    new TapirServerTracer {
      override def zServerLogicTracing[R, SECURITY_INPUT, INPUT, ERROR_OUTPUT, OUTPUT, C](
        e: Endpoint[SECURITY_INPUT, INPUT, ERROR_OUTPUT, OUTPUT, C]
      )(spanName: String)(logic: INPUT => ZIO[R, ERROR_OUTPUT, OUTPUT])(implicit
        aIsUnit: SECURITY_INPUT =:= Unit
      ): sttp.tapir.ztapir.ZServerEndpoint[R, C] = {
        val endpointWithRequest = e.in(sttp.tapir.extractFromRequest(identity))
        endpointWithRequest.zServerLogic { case (in, serverRequest) =>
          spanOnRequest[R, ERROR_OUTPUT, OUTPUT](serverRequest, serverRequest.headers.toList)(tracerAlgebra.spanName(serverRequest))(logic(in))
        }
      }

      override def serverLogicTracing[R, SECURITY_INPUT, PRINCIPAL, INPUT, ERROR_OUTPUT, OUTPUT, C](
        e: ZPartialServerEndpoint[R, SECURITY_INPUT, PRINCIPAL, INPUT, ERROR_OUTPUT, OUTPUT, C]
      )(
        spanName: String
      )(logic: PRINCIPAL => INPUT => ZIO[R, ERROR_OUTPUT, OUTPUT]): sttp.tapir.ztapir.ZServerEndpoint[R, C] = {
        val endpointWithRequest = e.in(sttp.tapir.extractFromRequest(identity))
        endpointWithRequest.serverLogic[R] { principal =>
          { case (in, serverRequest) =>
            spanOnRequest[R, ERROR_OUTPUT, OUTPUT](serverRequest, serverRequest.headers.toList)(tracerAlgebra.spanName(serverRequest))(
              logic(principal)(in)
            )
          }
        }

      }

    }
  )
}
