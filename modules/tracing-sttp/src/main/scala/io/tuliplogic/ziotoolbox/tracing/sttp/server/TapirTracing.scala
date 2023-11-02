package io.tuliplogic.ziotoolbox.tracing.sttp.server

import cats.implicits.catsSyntaxEq
import io.opentelemetry.api.trace.SpanKind
import io.tuliplogic.ziotoolbox.tracing.commons.{TracerAlgebra, ServerTracerBaseInterpreter}
import sttp.model.Header
import sttp.tapir.Endpoint
import sttp.tapir.ztapir._
import zio.{IO, UIO, ZIO, ZLayer}
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.IncomingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

trait TapirTracingInterpretation { interpretation =>
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

object TapirTracingInterpretation {

  val tracerDsl = TracerAlgebra.dsl[Endpoint[_, _, _, _, _], Any]

  val defaultTapirServerTracerAlgebra: TracerAlgebra[Endpoint[_, _, _, _, _], Any] = {
    import tracerDsl._
    withRequestAttributes(endpoint =>
      Map(
        "http.method" -> endpoint.method.map(_.toString()).getOrElse(""),
        "http.path"    -> endpoint.showPathTemplate()
      )
    )
  }

  def layer(
    tracerAlgebra: TracerAlgebra[Endpoint[_, _, _, _, _], Any] = defaultTapirServerTracerAlgebra
  ): ZLayer[Baggage with Tracing, Nothing, TapirTracingInterpretation] =
    ZLayer.fromZIO {
      for {
        tracing        <- ZIO.service[Tracing]
        baggage        <- ZIO.service[Baggage]
        interpreter     = new TapirTracingInterpreter(tracerAlgebra, tracing, baggage)
        interpretation <- interpreter.interpretation
      } yield interpretation
    }

}

class TapirTracingInterpreter(
                               val tracerAlgebra: TracerAlgebra[Endpoint[_, _, _, _, _], Any],
                               val tracing: Tracing,
                               val baggage: Baggage
) extends ServerTracerBaseInterpreter[Endpoint[_, _, _, _, _], Any, List[Header], TapirTracingInterpretation] {

  override def transportToCarrier(headers: List[Header]): IncomingContextCarrier[Map[String, String]] =
    new IncomingContextCarrier[Map[String, String]] {
      val safeHeaders =
        headers.filter(h => !sttp.model.HeaderNames.SensitiveHeaders.contains(h.name)).map(h => h.name -> h.value).toMap

      override def getAllKeys(carrier: Map[String, String]): Iterable[String] = safeHeaders.keys

      override def getByKey(carrier: Map[String, String], key: String): Option[String] =
        safeHeaders.get(key)

      override val kernel: Map[String, String] = safeHeaders
    }

  override def interpretation: UIO[TapirTracingInterpretation] = ZIO.succeed(
    new TapirTracingInterpretation { interpretation =>
      override def zServerLogicTracing[R, SECURITY_INPUT, INPUT, ERROR_OUTPUT, OUTPUT, C](
        e: Endpoint[SECURITY_INPUT, INPUT, ERROR_OUTPUT, OUTPUT, C]
      )(spanName: String)(logic: INPUT => ZIO[R, ERROR_OUTPUT, OUTPUT])(implicit
        aIsUnit: SECURITY_INPUT =:= Unit
      ): sttp.tapir.ztapir.ZServerEndpoint[R, C] = {
        val endpointWithRequestHeaders = e.in(sttp.tapir.headers)
        endpointWithRequestHeaders.zServerLogic { case (in, headers) =>
          val carrier = transportToCarrier(headers)
          for {
            _ <- baggage.extract(baggagePropagator, carrier)
            res <- tracing.extractSpan(
                     tracingPropagator,
                     carrier,
                     spanName,
                     spanKind = SpanKind.SERVER
                   )(logic(in))
          } yield res
        }
      }

      override def serverLogicTracing[R, SECURITY_INPUT, PRINCIPAL, INPUT, ERROR_OUTPUT, OUTPUT, C](
        e: ZPartialServerEndpoint[R, SECURITY_INPUT, PRINCIPAL, INPUT, ERROR_OUTPUT, OUTPUT, C]
      )(
        spanName: String
      )(logic: PRINCIPAL => INPUT => ZIO[R, ERROR_OUTPUT, OUTPUT]): sttp.tapir.ztapir.ZServerEndpoint[R, C] = {
        val endpointWithRequestHeaders = e.in(sttp.tapir.headers)
        endpointWithRequestHeaders.serverLogic[R] { principal =>
          { case (in, headers) =>
            val carrier = transportToCarrier(headers)
            for {
              _ <- ZIO.logInfo(s"extracting from carrier with keys ${carrier.kernel}")
              _ <- baggage.extract(BaggagePropagator.default, carrier)
              res <- tracing
                       .extractSpan(
                         TraceContextPropagator.default,
                         carrier,
                         spanName,
                         spanKind = SpanKind.SERVER
                       )(logic(principal)(in))
            } yield res
          }
        }

      }

    }
  )

}

//object TapirTracing {
//
//  /*
//  On the server side, we need to build the tracing and baggage info from the incoming request.
//  For this we need an IncomingContextCarrier that populates a map from the list of headers in the request
//  //TODO: make a universal component out of this. One for kafka, one for http, one for grpc, etc.
//   */
//  private def headersCarrier(headers: List[Header]): IncomingContextCarrier[List[Header]] =
//    new IncomingContextCarrier[List[Header]] {
//      val safeHeaders =
//        headers.filter(h => !sttp.model.HeaderNames.SensitiveHeaders.contains(h.name))
//
//      override def getAllKeys(carrier: List[Header]): Iterable[String] = safeHeaders.map(_.name)
//
//      override def getByKey(carrier: List[Header], key: String): Option[String] =
//        safeHeaders.map(h => (h.name, h.value)).find(_._1 === key).map(_._2)
//
//      override val kernel: List[Header] = safeHeaders
//    }
//
//  implicit class TracingZEndpoint[SECURITY_INPUT, INPUT, ERROR_OUTPUT, OUTPUT, C](
//    e: Endpoint[SECURITY_INPUT, INPUT, ERROR_OUTPUT, OUTPUT, C]
//  ) {
//    private val endpointWithRequestHeaders = e.in(sttp.tapir.headers) // TODO: enrich with path information
//
//    /** Use this method where you would  use `zServerLogic`, you must provide the spanName
//      */
//    def zServerLogicTracing[R <: Baggage with Tracing](
//      spanName: String
//    )(
//      logic: INPUT => ZIO[R, ERROR_OUTPUT, OUTPUT]
//    )(implicit
//      aIsUnit: SECURITY_INPUT =:= Unit
//    ): ZServerEndpoint[R, C] =
//      endpointWithRequestHeaders.zServerLogic {
//        case (in, headers) =>
//          val carrier = headersCarrier(headers)
//          for {
//            baggage <- ZIO.service[Baggage]
//            tracing <- ZIO.service[Tracing]
//            _ <- ZIO.logInfo(s"extracting from carrier with keys ${carrier.kernel}")
//            _ <- baggage.extract(BaggagePropagator.default, carrier)
//            res <- tracing.extractSpan(
//              TraceContextPropagator.default,
//              carrier,
//              spanName,
//              spanKind = SpanKind.SERVER,
//            )(logic(in))
//          } yield res
//      }
//  }
//
//  implicit class TracingPartialServerEndpoint[
//    R,
//    SECURITY_INPUT,
//    PRINCIPAL,
//    INPUT,
//    ERROR_OUTPUT,
//    OUTPUT,
//    C,
//  ](
//    e: ZPartialServerEndpoint[R, SECURITY_INPUT, PRINCIPAL, INPUT, ERROR_OUTPUT, OUTPUT, C]
//  ) {
//    private val endpointWithRequestHeaders = e.in(sttp.tapir.headers)
//
//    def serverLogicTracing[R0 <: Baggage with Tracing](
//      spanName: String
//    )(
//      logic: PRINCIPAL => INPUT => ZIO[R0, ERROR_OUTPUT, OUTPUT]
//    ): ZServerEndpoint[R with R0, C] =
//      endpointWithRequestHeaders.serverLogic[R with R0] { p =>
//        {
//          case (in, headers) =>
//            val carrier = headersCarrier(headers)
//            for {
//              baggage <- ZIO.service[Baggage]
//              tracing <- ZIO.service[Tracing]
//              _ <- ZIO.logInfo(s"extracting from carrier with keys ${carrier.kernel}")
//              _ <- baggage.extract(BaggagePropagator.default, carrier)
//              res <- logic(p)(in) @@ tracing
//                .aspects
//                .extractSpan(
//                  TraceContextPropagator.default,
//                  carrier,
//                  spanName,
//                  spanKind = SpanKind.SERVER,
//                )
//            } yield res
//        }
//      }
//  }
//}
