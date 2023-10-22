package io.tuliplogic.ziotoolbox.kafka.producer

import io.opentelemetry.api.trace.SpanKind
import io.tuliplogic.ziotoolbox.tracing.commons.OpenTelemetryTracer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.{Header => KafkaHeader}
import zio._
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.IncomingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import java.nio.charset.StandardCharsets
class ProducerTracing {
  private def headersCarrier(headers: List[KafkaHeader]): IncomingContextCarrier[List[KafkaHeader]] =
    new IncomingContextCarrier[List[KafkaHeader]] {
      override def getAllKeys(carrier: List[KafkaHeader]): Iterable[String] = carrier.map(_.key)

      override def getByKey(carrier: List[KafkaHeader], key: String): Option[String] = {
        val res =
          carrier.map(h => (h.key, h.value)).find(_._1 == key).map(kv => new String(kv._2, StandardCharsets.UTF_8))
        res
      }

      override val kernel: List[KafkaHeader] = headers
    }

  type KafkaTracer = OpenTelemetryTracer[ConsumerRecord[_, _], Any]

  def defaultKafkaTracer(span: String): KafkaTracer = new KafkaTracer {
    override def spanName(request: ConsumerRecord[_, _]): String = span

    override def before(record: ConsumerRecord[_, _]): URIO[Tracing, Unit] =
      ZIO.serviceWithZIO[Tracing](_.setAttribute("kafka.topic", record.topic())) *>
        ZIO.serviceWithZIO[Tracing](_.setAttribute("kafka.partition", record.partition().toString))

    override def after(response: Any): URIO[Tracing, Unit] = ZIO.unit
  }

  /** Annotate the effect that processes the consumer record to have the processing wrapped in a span carrying along the
   * tracing information
   */
  def kafkaTraced[K, V](
                         kafkaTracer: KafkaTracer
                       )(record: ConsumerRecord[K, V]): ZIOAspect[Nothing, Baggage with Tracing, Nothing, Any, Nothing, Any] =
    new ZIOAspect[Nothing, Baggage with Tracing, Nothing, Any, Nothing, Any] {
      override def apply[R >: Nothing <: Baggage with Tracing, E >: Nothing <: Any, A >: Nothing <: Any](
                                                                                                          zio: ZIO[R, E, A]
                                                                                                        )(implicit trace: Trace): ZIO[R, E, A] = {
        val carrier = headersCarrier(record.headers.toArray.toList)
        for {
          baggage <- ZIO.service[Baggage]
          tracing <- ZIO.service[Tracing]
          _ <- baggage.extract(BaggagePropagator.default, carrier)
          res <- (kafkaTracer.before(record) *>
            zio.tap(_ => kafkaTracer.after(()))) @@ tracing.aspects.extractSpan(
            TraceContextPropagator.default,
            carrier,
            kafkaTracer.spanName(record),
            spanKind = SpanKind.CONSUMER
          )
        } yield res
      }
    }

  def kafkaTracedSpan[K, V](spanName: String)(
    record: ConsumerRecord[K, V]
  ): ZIOAspect[Nothing, Baggage with Tracing, Nothing, Any, Nothing, Any] =
    kafkaTraced(defaultKafkaTracer(spanName))(record)
}
