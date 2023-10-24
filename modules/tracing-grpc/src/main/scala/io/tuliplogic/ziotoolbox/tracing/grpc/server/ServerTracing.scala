package io.tuliplogic.ziotoolbox.tracing.grpc.server

import io.grpc.{Metadata, Status, StatusException}
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.semconv.SemanticAttributes
import scalapb.zio_grpc.{GeneratedService, RequestContext, ZTransform}
import zio.{Tag, ZIO}
import zio.stream.ZStream
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.IncomingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import scala.jdk.CollectionConverters._

object ServerTracing {
  private def metadataCarrier(initial: Metadata): IncomingContextCarrier[Metadata] =
    new IncomingContextCarrier[Metadata] {
      override val kernel: Metadata = initial

      override def getAllKeys(carrier: Metadata): Iterable[String] =
        carrier.keys().asScala

      override def getByKey(carrier: Metadata, key: String): Option[String] =
        Option.apply(carrier.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)))
    }

  //also open a child span as we do for http servers
  private def withSemanticAttributes[R, A](tracing: Tracing)(effect: ZIO[R, StatusException, A]): ZIO[R, StatusException, A] =
    tracing.setAttribute(SemanticAttributes.RPC_SYSTEM.getKey, "grpc") *>
      effect
        .tapBoth(
          statusException =>
            tracing.setAttribute(
              SemanticAttributes.RPC_GRPC_STATUS_CODE.getKey,
              statusException.getStatus.getCode.value().toString
            ),
          _ =>
            tracing.setAttribute(SemanticAttributes.RPC_GRPC_STATUS_CODE.getKey, Status.OK.getCode.value().toString)
        )

  private def withSemanticAttributesStream[R, A](tracing: Tracing)(effect: ZStream[R, StatusException, A]): ZStream[R, StatusException, A] =
    ZStream.fromZIO(tracing.setAttribute(SemanticAttributes.RPC_SYSTEM.getKey, "grpc")) *>
      effect
        .tapBoth(
          status =>
            tracing.setAttribute(
              SemanticAttributes.RPC_GRPC_STATUS_CODE.getKey,
              status.getStatus.getCode.value().toString
            ),
          _ =>
            tracing.setAttribute(SemanticAttributes.RPC_GRPC_STATUS_CODE.getKey, Status.OK.getCode.value().toString)
        )

  private def serverTracingTransform(baggage: Baggage, tracing: Tracing): ZTransform[Any, RequestContext] = new ZTransform[Any, RequestContext] {
    override def effect[A](io: Any => ZIO[Any, StatusException, A]): RequestContext => ZIO[Any, StatusException, A] = {
      reqCtx =>
        for {
          metadata <- reqCtx.metadata.wrap(identity)
          carrier = metadataCarrier(metadata)
          _ <- baggage.extract(BaggagePropagator.default, carrier)
          res <- tracing.extractSpan(
            propagator = TraceContextPropagator.default,
            carrier = carrier,
            spanName = reqCtx.methodDescriptor.getFullMethodName,
            spanKind = SpanKind.SERVER,
          )(withSemanticAttributes(tracing)(io(())))
        } yield res
    }

    override def stream[A](io: Any => ZStream[Any, StatusException, A]): RequestContext => ZStream[Any, StatusException, A] = {
      reqCtx =>
        val r: ZIO[Any, Nothing, ZStream[Any, StatusException, A]] = for {
          metadata <- reqCtx.metadata.wrap(identity)
          carrier = metadataCarrier(metadata)
          _ <- baggage.extract(BaggagePropagator.default, carrier)
          res <- ZIO.succeed(withSemanticAttributesStream(tracing)(io(()))) @@ tracing.aspects.extractSpan(
            propagator = TraceContextPropagator.default,
            carrier = carrier,
            spanName = reqCtx.methodDescriptor.getFullMethodName,
            spanKind = SpanKind.SERVER,
          )
        } yield res
        ZStream.fromZIO(r).flatten
    }
  }


  /**
   * Given a generated service, makes a traced service that traces the call using Tracing and Baggage
   * coming from the headers in the message context
   */
  def serviceWithTracing[R: Tag, Service <: GeneratedService](f: R => Service): ZIO[Baggage with Tracing with R, Nothing, Service#Generic[RequestContext, StatusException]] =
    for {
      evseLookup <- ZIO.service[R]
      tracing <- ZIO.service[Tracing]
      baggage <- ZIO.service[Baggage]
    } yield f(evseLookup).transform(serverTracingTransform(baggage, tracing))
}
