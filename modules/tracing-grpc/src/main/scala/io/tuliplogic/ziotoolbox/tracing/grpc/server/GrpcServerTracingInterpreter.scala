package io.tuliplogic.ziotoolbox.tracing.grpc.server

import io.grpc.{Metadata, Status, StatusException}
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.semconv.{ResourceAttributes, SemanticAttributes}
import io.tuliplogic.ziotoolbox.tracing.commons.{ServerTracerBaseInterpreter, TracerAlgebra}
import scalapb.zio_grpc.{GeneratedService, RequestContext, ZTransform}
import zio.{Tag, UIO, ZIO, ZLayer}
import zio.stream.ZStream
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.IncomingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing

import scala.jdk.CollectionConverters._

class GrpcServerTracingInterpreter(
  val tracerAlgebra: TracerAlgebra[RequestContext, Any],
  val tracing: Tracing,
  val baggage: Baggage
) extends ServerTracerBaseInterpreter[RequestContext, Any, Metadata, ZTransform[Any, RequestContext]] {
  override val spanKind: SpanKind = SpanKind.SERVER
  override def transportToCarrier(metadata: Metadata): UIO[IncomingContextCarrier[Map[String, String]]] =
    ZIO.succeed(
      new IncomingContextCarrier[Map[String, String]] {
        override val kernel: Map[String, String] =
          metadata
            .keys()
            .asScala
            .map(k => k -> metadata.get(Metadata.Key.of(k, Metadata.ASCII_STRING_MARSHALLER)))
            .toMap

        override def getAllKeys(carrier: Map[String, String]): Iterable[String] =
          kernel.keys

        override def getByKey(carrier: Map[String, String], key: String): Option[String] =
          carrier.get(key)
      }
    )

  //TODO: find a way to process success and failure by describing in the algebra what to do
//  private def withSemanticAttributes[R, A](
//                                            tracing: Tracing
//                                          )(effect: ZIO[R, StatusException, A]): ZIO[R, StatusException, A] =
//    tracing.setAttribute(SemanticAttributes.RPC_SYSTEM.getKey, "grpc") *>
//      effect
//        .tapBoth(
//          statusException =>
//            tracing.setAttribute(
//              SemanticAttributes.RPC_GRPC_STATUS_CODE.getKey,
//              statusException.getStatus.getCode.value().toString
//            ),
//          _ => tracing.setAttribute(SemanticAttributes.RPC_GRPC_STATUS_CODE.getKey, Status.OK.getCode.value().toString)
//        )
//
//  private def withSemanticAttributesStream[R, A](
//                                                  tracing: Tracing
//                                                )(effect: ZStream[R, StatusException, A]): ZStream[R, StatusException, A] =
//    ZStream.fromZIO(tracing.setAttribute(SemanticAttributes.RPC_SYSTEM.getKey, "grpc")) *>
//      effect
//        .tapBoth(
//          status =>
//            tracing.setAttribute(
//              SemanticAttributes.RPC_GRPC_STATUS_CODE.getKey,
//              status.getStatus.getCode.value().toString
//            ),
//          _ => tracing.setAttribute(SemanticAttributes.RPC_GRPC_STATUS_CODE.getKey, Status.OK.getCode.value().toString)
//        )

  override def interpretation: UIO[ZTransform[Any, RequestContext]] = ZIO.succeed(
    new ZTransform[Any, RequestContext] {
      override def effect[A](
        io: Any => ZIO[Any, StatusException, A]
      ): RequestContext => ZIO[Any, StatusException, A] = { reqCtx =>
        for {
          metadata <- reqCtx.metadata.wrap(identity)
          carrier  <- transportToCarrier(metadata)
          _        <- baggage.extract(baggagePropagator, carrier)
          res      <- spanOnRequest(reqCtx, metadata)(reqCtx.methodDescriptor.getFullMethodName)(io(()))
        } yield res
      }

      override def stream[A](
        io: Any => ZStream[Any, StatusException, A]
      ): RequestContext => ZStream[Any, StatusException, A] = { reqCtx =>
        val r: ZIO[Any, Nothing, ZStream[Any, StatusException, A]] = for {
          metadata <- reqCtx.metadata.wrap(identity)
          carrier  <- transportToCarrier(metadata)
          _        <- baggage.extract(BaggagePropagator.default, carrier)
          res <- spanOnRequest(reqCtx, metadata)(reqCtx.methodDescriptor.getFullMethodName)(
                   ZIO.succeed(io(()))
                 )
        } yield res
        ZStream.fromZIO(r).flatten
      }
    }
  )

}

object GrpcServerTracingInterpreter {

  val tracerDsl = TracerAlgebra.dsl[RequestContext, Any]
  val defaultGrpcServerTracerAlgebra: TracerAlgebra[RequestContext, Any] = {
    import tracerDsl._
    withRequestAttributes(reqCtx =>
      Map(
        SemanticAttributes.RPC_SYSTEM.getKey  -> "grpc-backend-app", //TODO make dynamic
        SemanticAttributes.RPC_METHOD.getKey  -> reqCtx.methodDescriptor.getBareMethodName,
        SemanticAttributes.RPC_SERVICE.getKey -> reqCtx.methodDescriptor.getServiceName,
        ResourceAttributes.OTEL_SCOPE_NAME.getKey -> "zio-grpc-server",

    )
    )
  }

  /**
   * Given a generated service, makes a traced service that traces the call
   * using Tracing and Baggage coming from the headers in the message context
   */
  def serviceWithTracing[R: Tag, Service <: GeneratedService](
    f: R => Service,
    grpcServerTracerAlgebra: TracerAlgebra[RequestContext, Any] = defaultGrpcServerTracerAlgebra
  )(implicit
    serviceTag: Tag[Service#Generic[RequestContext, StatusException]]
  ): ZLayer[Baggage with Tracing with R, Nothing, Service#Generic[RequestContext, StatusException]] =
    ZLayer.fromZIO {
      for {
        tracing    <- ZIO.service[Tracing]
        baggage    <- ZIO.service[Baggage]
        env        <- ZIO.service[R]
        interpreter = new GrpcServerTracingInterpreter(grpcServerTracerAlgebra, tracing, baggage)
        zTransform <- interpreter.interpretation
      } yield f(env).transform(zTransform)
    }



}
