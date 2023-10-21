package io.tuliplogic.ziotoolbox.sttp.server

import cats.implicits.catsSyntaxEq
import io.opentelemetry.api.trace.SpanKind
import sttp.model.Header
import sttp.tapir.Endpoint
import sttp.tapir.ztapir._
import zio.ZIO
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.IncomingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

object TapirTracing {

  /*
  On the server side, we need to build the tracing and baggage info from the incoming request.
  For this we need an IncomingContextCarrier that populates a map from the list of headers in the request
  //TODO: make a universal component out of this. One for kafka, one for http, one for grpc, etc.
   */
  private def headersCarrier(headers: List[Header]): IncomingContextCarrier[List[Header]] = new IncomingContextCarrier[List[Header]] {
    val safeHeaders = headers.filter(h => !sttp.model.HeaderNames.SensitiveHeaders.contains(h.name))

    override def getAllKeys(carrier: List[Header]): Iterable[String] = safeHeaders.map(_.name)

    override def getByKey(carrier: List[Header], key: String): Option[String] =
      safeHeaders.map(h => (h.name, h.value)).find(_._1 === key).map(_._2)

    override val kernel: List[Header] = safeHeaders
  }

  implicit class TracingZEndpoint[SECURITY_INPUT, INPUT, ERROR_OUTPUT, OUTPUT, C](
                                                                                   e: Endpoint[SECURITY_INPUT, INPUT, ERROR_OUTPUT, OUTPUT, C]
                                                                                 ) {
    private val endpointWithRequestHeaders = e.in(sttp.tapir.headers)

    /**
     * Use this method where you would  use `zServerLogic`, you must provide the spanName
     */
    def zServerLogicTracing[R <: Baggage with Tracing](spanName: String)(logic: INPUT => ZIO[R, ERROR_OUTPUT, OUTPUT])(implicit aIsUnit: SECURITY_INPUT =:= Unit): ZServerEndpoint[R, C] = {
      endpointWithRequestHeaders.zServerLogic { case (in, headers) =>
        val carrier = headersCarrier(headers)
        for {
          baggage <- ZIO.service[Baggage]
          tracing <- ZIO.service[Tracing]
          _ <- ZIO.logInfo(s"extracting from carrier with keys ${carrier.kernel}")
          _ <- baggage.extract(BaggagePropagator.default, carrier)
          res <- tracing.extractSpan(
            TraceContextPropagator.default,
            carrier,
            spanName,
            spanKind = SpanKind.SERVER
          )(logic(in))
        } yield res
      }
    }
  }

  implicit class TracingPartialServerEndpoint[R, SECURITY_INPUT, PRINCIPAL, INPUT, ERROR_OUTPUT, OUTPUT, C](e: ZPartialServerEndpoint[R, SECURITY_INPUT, PRINCIPAL, INPUT, ERROR_OUTPUT, OUTPUT, C]) {
    private val endpointWithRequestHeaders = e.in(sttp.tapir.headers)

    def serverLogicTracing[R0 <: Baggage with Tracing](spanName: String)(logic: PRINCIPAL => INPUT => ZIO[R0, ERROR_OUTPUT, OUTPUT]): ZServerEndpoint[R with R0, C] = {
      endpointWithRequestHeaders.serverLogic[R with R0] { p => {
        case (in, headers) =>
          val carrier = headersCarrier(headers)
          for {
            baggage <- ZIO.service[Baggage]
            tracing <- ZIO.service[Tracing]
            _ <- ZIO.logInfo(s"extracting from carrier with keys ${carrier.kernel}")
            _ <- baggage.extract(BaggagePropagator.default, carrier)
            res <- logic(p)(in) @@ tracing.aspects.extractSpan(
              TraceContextPropagator.default,
              carrier,
              spanName,
              spanKind = SpanKind.SERVER
            )
          } yield res
      }
      }
    }
  }
}
