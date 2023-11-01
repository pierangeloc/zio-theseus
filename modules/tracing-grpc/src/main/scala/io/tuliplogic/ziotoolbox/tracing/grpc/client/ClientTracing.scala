package io.tuliplogic.ziotoolbox.tracing.grpc.client

import io.grpc.{ManagedChannelBuilder, Metadata}
import io.tuliplogic.ziotoolbox.tracing.commons.{ClientTracerAlgebra, ClientTracerBaseInterpreter}
import scalapb.zio_grpc.{ZClientInterceptor, ZManagedChannel}
import zio.{Scope, UIO, ZIO}
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import scala.collection.mutable

class ClientTracingInterpreter(
  val tracerAlgebra: ClientTracerAlgebra[Any, Any],
  val tracing: Tracing,
  val baggage: Baggage,
) extends ClientTracerBaseInterpreter[Any, Any, Map[Metadata.Key[String], String], ZClientInterceptor] {

  override def toTransport(carrier: OutgoingContextCarrier[mutable.Map[String, String]]): Map[Metadata.Key[String], String] =
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
            outgoingCarrier <- before(())
            _ <- ZIO.succeed {
              outgoingCarrier.kernel.foreach {
                case (k, v) =>
                  m.put(Metadata.Key.of(k, Metadata.ASCII_STRING_MARSHALLER), v)
              }
            }
          } yield ()
        }
    }
  )

}

object ClientTracing {

  val tracerDsl = ClientTracerAlgebra.dsl[Any, Any]

  val defaultGrpcClientTracerAlgebra: ClientTracerAlgebra[Any, Any] = {
    import tracerDsl._
      withRequestAttributes(req =>
        Map(
          "grpc.method" -> "",
        )
      )
  }

  def clientTracingInterceptor(tracing: Tracing, baggage: Baggage, tracerAlgebra: ClientTracerAlgebra[Any, Any]): UIO[ZClientInterceptor] =
    new ClientTracingInterpreter(tracerAlgebra, tracing, baggage).interpretation

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
    tracerAlgebra: ClientTracerAlgebra[Any, Any] = defaultGrpcClientTracerAlgebra
  )(
    scopedClient: ZManagedChannel => ZIO[Scope, Throwable, S]
  ): ZIO[Tracing with Baggage with Scope, Throwable, S] =
    for {
      tracing <- ZIO.service[Tracing]
      baggage <- ZIO.service[Baggage]
      tracingInterceptor <- clientTracingInterceptor(tracing, baggage, tracerAlgebra)
      channel = ZManagedChannel(
        builder = ManagedChannelBuilder
          .forAddress(host, port)
          .usePlaintext(),
        interceptors = List(tracingInterceptor),
      )
      client <- scopedClient(channel)
    } yield client
}
