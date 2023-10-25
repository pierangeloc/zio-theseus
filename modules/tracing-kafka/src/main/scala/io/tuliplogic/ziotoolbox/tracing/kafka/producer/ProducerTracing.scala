package io.tuliplogic.ziotoolbox.tracing.kafka.producer


import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.{Header => KafkaHeader}
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import zio.{Trace, URIO, ZIO, ZIOAspect}
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import scala.jdk.CollectionConverters._

object ProducerTracing {

  /**
   * When producing a kafka producer record, enrich it with the headers coming fro this method
   * to carry along the tracing information.
   * NOTE: If you open a span around the production of the record into kafka, the span kind should be `SpanKind.PRODUCER`,
   */
  private def withTracingInfo: URIO[Baggage with Tracing , List[KafkaHeader]] = {
    val carrier = OutgoingContextCarrier.default()
    val tracingPropagator: TraceContextPropagator = TraceContextPropagator.default
    val baggagePropagator: BaggagePropagator = BaggagePropagator.default

    for {
      tracing <- ZIO.service[Tracing]
      baggage <- ZIO.service[Baggage]
      _ <- tracing.inject(tracingPropagator, carrier)
      _ <- baggage.inject(baggagePropagator, carrier)
      res = carrier.kernel.toList.map {
        case (k, v) => new RecordHeader(k, v.getBytes("UTF-8"))
      }
    } yield res
  }

  def tracedRecord[K, V](producerRecord: ProducerRecord[K, V]): URIO[Tracing with Baggage, ProducerRecord[K, V]] = for {
    headers <- withTracingInfo
    existingHeders = producerRecord.headers()
    allHeaders = headers ++ existingHeders.toArray.toList
    _ <- ZIO.logInfo(s"Producing to kafka with headers: $allHeaders")
  } yield new ProducerRecord[K, V](
    producerRecord.topic(),
    producerRecord.partition(),
    producerRecord.timestamp(),
    producerRecord.key(),
    producerRecord.value(),
    allHeaders.asJava
  )

  def traced[K, V]: ZIOAspect[Nothing, Baggage with Tracing, Nothing, Any, ProducerRecord[K, V], ProducerRecord[K, V]] =
    new ZIOAspect[Nothing, Baggage with Tracing, Nothing, Any, ProducerRecord[K, V], ProducerRecord[K, V]] {
      override def apply[R >: Nothing <: Baggage with Tracing, E >: Nothing <: Any, A >: ProducerRecord[K, V] <: ProducerRecord[K, V]](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        zio.flatMap(tracedRecord)
    }
}

