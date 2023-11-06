package io.tuliplogic.ziotoolbox.tracing.grpc.client

import io.grpc.{ManagedChannelBuilder, Metadata}
import io.tuliplogic.ziotoolbox.tracing.commons.{TracerAlgebra, ClientBaseTracingInterpreter}
import scalapb.zio_grpc.{ZClientInterceptor, ZManagedChannel}
import zio.{Scope, UIO, ZIO}
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import scala.collection.mutable

class GrpcClientTracingInterpreter(
                                val tracerAlgebra: TracerAlgebra[Any, Any],
                                val tracing: Tracing,
                                val baggage: Baggage,
) extends ClientBaseTracingInterpreter[Any, Any, Map[Metadata.Key[String], String], ZClientInterceptor] {

  override def carrierToTransport(carrier: OutgoingContextCarrier[mutable.Map[String, String]]): Map[Metadata.Key[String], String] =
    carrier.kernel.map(kv => Metadata.Key.of(kv._1, Metadata.ASCII_STRING_MARSHALLER) -> kv._2).toMap

  override def interpretation: UIO[ZClientInterceptor] = ZIO.succeed(
    ZClientInterceptor.headersUpdater {
      (
        _,
        _,
        metadata,
      ) =>
        metadata.wrapZIO { m =>
          for {
            outgoingCarrier <- beforeSendingRequest(())
            _ <- ZIO.succeed {
              carrierToTransport(outgoingCarrier).foreach {
                case (k, v) =>
                  m.put(k, v)
              }
            }
          } yield ()
        }
    }
  )

}

object GrpcClientTracing {

  val tracerDsl = TracerAlgebra.dsl[Any, Any]

  val defaultGrpcClientTracerAlgebra: TracerAlgebra[Any, Any] = {
    import tracerDsl._
      const(
        Map()
      )
  }

  /** In a typical situation, generate the client this way
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
    tracerAlgebra: TracerAlgebra[Any, Any] = defaultGrpcClientTracerAlgebra
  )(
    scopedClient: ZManagedChannel => ZIO[Scope, Throwable, S]
  ): ZIO[Tracing with Baggage with Scope, Throwable, S] =
    for {
      tracing <- ZIO.service[Tracing]
      baggage <- ZIO.service[Baggage]
      tracingInterceptor <- new GrpcClientTracingInterpreter(tracerAlgebra, tracing, baggage).interpretation
      channel = ZManagedChannel(
        builder = ManagedChannelBuilder
          .forAddress(host, port)
          .usePlaintext(),
        interceptors = List(tracingInterceptor),
      )
      client <- scopedClient(channel)
    } yield client
}
