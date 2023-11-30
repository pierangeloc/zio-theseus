package io.tuliplogic.ziotoolbox.tracing.kafka.consumer

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes
import io.tuliplogic.ziotoolbox.tracing.commons.{ServerBaseTracingInterpreter, TracerAlgebra}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.{Header => KafkaHeader}
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.context.IncomingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{Trace, UIO, ZIO, ZIOAspect, ZLayer}

import java.nio.charset.StandardCharsets

trait KafkaConsumerTracer {
  def spanProcessing[K, V, R, E, A](record: ConsumerRecord[K, V])(effect: ZIO[R, E, A]): ZIO[R, E, A]
}

//TODO: find a way to specify the span name not only through the algebra. Like I want different consumers to use different span names
object KafkaConsumerTracer {

  def defaultConsumerTracingAlgebra(consumerSpanName: String): TracerAlgebra[ConsumerRecord[_, _], Any] = {
    val tracerDsl = TracerAlgebra.dsl[ConsumerRecord[_, _], Any]

    import tracerDsl._
    requestAttributes(req =>
      Map(
        SemanticAttributes.MESSAGING_SOURCE_NAME.getKey            -> req.topic(),
        "kafka.topic"                                              -> req.topic(),
        SemanticAttributes.MESSAGING_KAFKA_SOURCE_PARTITION.getKey -> req.partition().toString
      )
    ) & spanName(_ => consumerSpanName)
  }
  def layer(
    algebra: TracerAlgebra[ConsumerRecord[_, _], Any]
  ): ZLayer[Baggage with Tracing, Nothing, KafkaConsumerTracer] =
    ZLayer.fromZIO {
      for {
        tracing <- ZIO.service[Tracing]
        baggage <- ZIO.service[Baggage]
        tracer  <- new ConsumerTracingInterpreter(algebra, tracing, baggage).interpretation
      } yield tracer
    }

  object aspects {
    def kafkaTraced[K, V](
      record: ConsumerRecord[K, V]
    ): ZIOAspect[Nothing, KafkaConsumerTracer, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, KafkaConsumerTracer, Nothing, Any, Nothing, Any] {
        override def apply[R >: Nothing <: KafkaConsumerTracer, E >: Nothing <: Any, A >: Nothing <: Any](
          zio: ZIO[R, E, A]
        )(implicit trace: Trace): ZIO[R, E, A] =
          ZIO.serviceWithZIO[KafkaConsumerTracer](_.spanProcessing(record)(zio))
      }
  }
}

private class ConsumerTracingInterpreter(
  val tracerAlgebra: TracerAlgebra[ConsumerRecord[_, _], Any],
  val tracing: Tracing,
  val baggage: Baggage
) extends ServerBaseTracingInterpreter[ConsumerRecord[_, _], Any, List[KafkaHeader], KafkaConsumerTracer] {
  interpreter =>

  override val spanKind: SpanKind = SpanKind.CONSUMER

  override def transportToCarrier(headers: List[KafkaHeader]): UIO[IncomingContextCarrier[Map[String, String]]] =
    ZIO.succeed(
      new IncomingContextCarrier[Map[String, String]] {
        override def getAllKeys(carrier: Map[String, String]): Iterable[String] = carrier.keys

        override def getByKey(carrier: Map[String, String], key: String): Option[String] =
          carrier.get(key)

        override val kernel: Map[String, String] =
          headers.map(h => (h.key, new String(h.value, StandardCharsets.UTF_8))).toMap
      }
    )

  override def interpretation: UIO[KafkaConsumerTracer] =
    ZIO.succeed(
      new KafkaConsumerTracer {
        override def spanProcessing[K, V, R, E, A](record: ConsumerRecord[K, V])(effect: ZIO[R, E, A]): ZIO[R, E, A] =
          spanOnRequest(record, record.headers.toArray.toList)(tracerAlgebra.spanName(record))(effect)
      }
    )
}

//
//object ConsumerTracing {
//  private def headersCarrier(headers: List[KafkaHeader]): IncomingContextCarrier[List[KafkaHeader]] =
//    new IncomingContextCarrier[List[KafkaHeader]] {
//      override def getAllKeys(carrier: List[KafkaHeader]): Iterable[String] = carrier.map(_.key)
//      override def getByKey(carrier: List[KafkaHeader], key: String): Option[String] = {
//        val res =
//          carrier.map(h => (h.key, h.value)).find(_._1 == key).map(kv => new String(kv._2, StandardCharsets.UTF_8))
//        res
//      }
//      override val kernel: List[KafkaHeader] = headers
//    }
//
//  type KafkaTracer = OpenTelemetryTracer[ConsumerRecord[_, _], Any]
//  def defaultKafkaTracer(span: String): KafkaTracer = new KafkaTracer {
//    override def spanName(request: ConsumerRecord[_, _]): String = span
//    override def before(record: ConsumerRecord[_, _]): URIO[Tracing, Unit] =
//      ZIO.serviceWithZIO[Tracing](_.setAttribute("kafka.topic", record.topic())) *>
//        ZIO.serviceWithZIO[Tracing](_.setAttribute("kafka.partition", record.partition().toString))
//    override def after(response: Any): URIO[Tracing, Unit] = ZIO.unit
//  }
//
//  /** Annotate the effect that processes the consumer record to have the processing wrapped in a span carrying along the
//   * tracing information
//   */
//  def kafkaTraced[K, V](
//                         kafkaTracer: KafkaTracer
//                       )(record: ConsumerRecord[K, V]): ZIOAspect[Nothing, Baggage with Tracing, Nothing, Any, Nothing, Any] =
//    new ZIOAspect[Nothing, Baggage with Tracing, Nothing, Any, Nothing, Any] {
//      override def apply[R >: Nothing <: Baggage with Tracing, E >: Nothing <: Any, A >: Nothing <: Any](
//                                                                                                          zio: ZIO[R, E, A]
//                                                                                                        )(implicit trace: Trace): ZIO[R, E, A] = {
//        val carrier = headersCarrier(record.headers.toArray.toList)
//        for {
//          baggage <- ZIO.service[Baggage]
//          tracing <- ZIO.service[Tracing]
//          _       <- baggage.extract(BaggagePropagator.default, carrier)
//          res <- (kafkaTracer.before(record) *>
//            zio.tap(_ => kafkaTracer.after(()))) @@ tracing.aspects.extractSpan(
//            TraceContextPropagator.default,
//            carrier,
//            kafkaTracer.spanName(record),
//            spanKind = SpanKind.CONSUMER
//          )
//        } yield res
//      }
//    }
//
//  def kafkaTracedSpan[K, V](spanName: String)(
//    record: ConsumerRecord[K, V]
//  ): ZIOAspect[Nothing, Baggage with Tracing, Nothing, Any, Nothing, Any] =
//    kafkaTraced(defaultKafkaTracer(spanName))(record)
//
//}
