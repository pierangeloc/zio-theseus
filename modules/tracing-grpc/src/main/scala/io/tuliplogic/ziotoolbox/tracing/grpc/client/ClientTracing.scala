package io.tuliplogic.ziotoolbox.tracing.grpc.client

import io.grpc.{ManagedChannelBuilder, Metadata}
import scalapb.zio_grpc.{ZClientInterceptor, ZManagedChannel}
import zio.{Scope, ZIO}
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

object ClientTracing {
  def clientTracingInterceptor(tracing: Tracing, baggage: Baggage): ZClientInterceptor =
    ZClientInterceptor.headersUpdater { (_, _, metadata) =>
      val carrier = OutgoingContextCarrier.default()
      val propagator: TraceContextPropagator = TraceContextPropagator.default
      metadata.wrapZIO { m =>
        tracing.inject(propagator, carrier) *>
          baggage.inject(BaggagePropagator.default, carrier) *>
          ZIO.succeed {
            carrier.kernel.foreach { case (k, v) =>
              m.put(Metadata.Key.of(k, Metadata.ASCII_STRING_MARSHALLER), v)
            }
          }
      }
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
  def serviceClient[S](host: String, port: Int)(scopedClient: ZManagedChannel => ZIO[Scope, Throwable, S]): ZIO[Tracing with Baggage with Scope, Throwable, S] =
    for {
      tracing <- ZIO.service[Tracing]
      baggage <- ZIO.service[Baggage]
      channel = ZManagedChannel(
        builder = ManagedChannelBuilder
          .forAddress(host, port)
          .usePlaintext(),
        interceptors = List(clientTracingInterceptor(tracing, baggage))
      )
      client <- scopedClient(channel)
    } yield client
}
