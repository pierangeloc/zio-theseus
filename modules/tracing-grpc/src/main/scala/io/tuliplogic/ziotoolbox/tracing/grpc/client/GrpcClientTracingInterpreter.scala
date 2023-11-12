package io.tuliplogic.ziotoolbox.tracing.grpc.client

import io.grpc.ClientCall.Listener
import io.grpc.{CallOptions, ManagedChannelBuilder, Metadata, MethodDescriptor, StatusException}
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.semconv.ResourceAttributes
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes
import io.tuliplogic.ziotoolbox.tracing.commons.{ClientBaseTracingInterpreter, TracerAlgebra}
import scalapb.zio_grpc.client.ZClientCall
import scalapb.zio_grpc.client.ZClientCall.ForwardingZClientCall
import scalapb.zio_grpc.{SafeMetadata, ZClientInterceptor, ZManagedChannel}
import zio.{IO, Scope, UIO, ZIO}
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import scala.collection.mutable

class GrpcClientTracingInterpreter(
  val tracerAlgebra: TracerAlgebra[MethodDescriptor[_, _], Any],
  val tracing: Tracing,
  val baggage: Baggage
) extends ClientBaseTracingInterpreter[
      MethodDescriptor[_, _],
      Any,
      Map[Metadata.Key[String], String],
      ZClientInterceptor
    ] {

  override def carrierToTransport(
    carrier: OutgoingContextCarrier[mutable.Map[String, String]]
  ): Map[Metadata.Key[String], String] =
    carrier.kernel.map(kv => Metadata.Key.of(kv._1, Metadata.ASCII_STRING_MARSHALLER) -> kv._2).toMap

  override def interpretation: UIO[ZClientInterceptor] = ZIO.succeed(
    new ZClientInterceptor {
      override def interceptCall[Req, Res](
        methodDescriptor: MethodDescriptor[Req, Res],
        call: CallOptions,
        clientCall: ZClientCall[Req, Res]
      ): ZClientCall[Req, Res] =
        new ForwardingZClientCall[Req, Res](clientCall) {
          override def start(
            responseListener: Listener[Res],
            md: SafeMetadata
          ): IO[StatusException, Unit] =
            md.wrapZIO { m =>
              tracing.span(
                spanName = methodDescriptor.getFullMethodName,
                spanKind = SpanKind.CLIENT
              )(for {
                outgoingCarrier <- beforeSendingRequest(methodDescriptor)
                _ <- ZIO.succeed {
                       carrierToTransport(outgoingCarrier).foreach { case (k, v) =>
                         m.put(k, v)
                       }
                     }
                res <- delegate.start(responseListener, md) &> ZIO.logInfo("*******GRPC start")
              } yield res)
            }
        }
    }
  )

}

object GrpcClientTracing {

  val tracerDsl = TracerAlgebra.dsl[MethodDescriptor[_, _], Any]

  val defaultGrpcClientTracerAlgebra: TracerAlgebra[MethodDescriptor[_, _], Any] = {
    import tracerDsl._
    spanName(_.getFullMethodName) &
      withRequestAttributes(md =>
        Map(
          SemanticAttributes.RPC_METHOD.getKey  -> md.getBareMethodName,
          SemanticAttributes.RPC_SERVICE.getKey -> md.getServiceName,
          ResourceAttributes.OTEL_SCOPE_NAME.getKey -> "zio-grpc-client",

        )
      )
  }

  /**
   * In a typical situation, generate the client this way
   * {{{
   * val clientLayer: ZLayer[Tracing with Baggage, Throwable, ZioStatusApi.GetStatusApiClient] =
   *   ZLayer.scoped {
   *     ClientGrpcTracing.serviceClient("localhost", GrpcBackendApp.serverPort)(ch => ZioStatusApi.GetStatusApiClient.scoped(ch))
   *   }
   * }}}
   */
  def serviceClient[S](
    host: String,
    port: Int,
    tracerAlgebra: TracerAlgebra[MethodDescriptor[_, _], Any] = defaultGrpcClientTracerAlgebra
  )(
    scopedClient: ZManagedChannel => ZIO[Scope, Throwable, S]
  ): ZIO[Tracing with Baggage with Scope, Throwable, S] =
    for {
      tracing            <- ZIO.service[Tracing]
      baggage            <- ZIO.service[Baggage]
      tracingInterceptor <- new GrpcClientTracingInterpreter(tracerAlgebra, tracing, baggage).interpretation
      channel = ZManagedChannel(
                  builder = ManagedChannelBuilder
                    .forAddress(host, port)
                    .usePlaintext(),
                  interceptors = List(tracingInterceptor)
                )
      client <- scopedClient(channel)
    } yield client
}
