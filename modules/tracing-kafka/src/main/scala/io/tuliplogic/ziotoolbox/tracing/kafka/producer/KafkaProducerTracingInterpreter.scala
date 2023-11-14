package io.tuliplogic.ziotoolbox.tracing.kafka.producer

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes
import io.tuliplogic.ziotoolbox.tracing.commons.{ClientBaseTracingInterpreter, TracerAlgebra}
import io.tuliplogic.ziotoolbox.tracing.kafka.producer.ProducerTracing.KafkaRecordTracer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.{Header => KafkaHeader}
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{UIO, ZIO, ZLayer}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

object ProducerTracing {

  trait KafkaRecordTracer {
    def produceTracedRecord[K, V, R, E, A](producerRecord: ProducerRecord[K, V])(produce: ProducerRecord[K, V] => ZIO[R, E, A]): ZIO[R, E, A]
  }

  object KafkaRecordTracer {
    def produceTracedRecord[K, V, R <: KafkaRecordTracer, E, A](producerRecord: ProducerRecord[K, V])(produce: ProducerRecord[K, V] => ZIO[R, E, A]): ZIO[R, E, A] =
      ZIO.serviceWithZIO[KafkaRecordTracer](tracer => tracer.produceTracedRecord(producerRecord)(produce))


    val tracerDsl = TracerAlgebra.dsl[ProducerRecord[_, _], Any]

    val defaultKafkaProducerTracerAlgebra: TracerAlgebra[ProducerRecord[_, _], Any] = {
      import tracerDsl._
      spanName(r => s"kafka.producer - ${r.topic}") &
      withRequestAttributes(req =>
        Map(
          "kafka.topic"                                                   -> req.topic(),
          SemanticAttributes.MESSAGING_DESTINATION_NAME.getKey            -> req.topic(),
          SemanticAttributes.MESSAGING_KAFKA_DESTINATION_PARTITION.getKey -> req.partition().toString
        )
      )
    }

    def layer(
      tracerAlgebra: TracerAlgebra[ProducerRecord[_, _], Any] = defaultKafkaProducerTracerAlgebra
    ): ZLayer[Baggage with Tracing, Nothing, KafkaRecordTracer] =
      ZLayer.fromZIO {
        for {
          tracing <- ZIO.service[Tracing]
          baggage <- ZIO.service[Baggage]
          tracer  <- new KafkaProducerTracingInterpreter(tracerAlgebra, tracing, baggage).interpretation
        } yield tracer
      }
  }
}

private class KafkaProducerTracingInterpreter(
  val tracerAlgebra: TracerAlgebra[ProducerRecord[_, _], Any],
  val tracing: Tracing,
  val baggage: Baggage
) extends ClientBaseTracingInterpreter[ProducerRecord[_, _], Any, List[KafkaHeader], KafkaRecordTracer] { interpreter =>
  override val spanKind: SpanKind = SpanKind.PRODUCER
  override protected def carrierToTransport(
    carrier: OutgoingContextCarrier[mutable.Map[String, String]]
  ): List[KafkaHeader] =
    carrier.kernel.toList.map { case (k, v) =>
      new RecordHeader(k, v.getBytes("UTF-8"))
    }

  override def interpretation: UIO[KafkaRecordTracer] = ZIO.succeed(
    new KafkaRecordTracer {
      def enrichWithTransport[K, V](producerRecord: ProducerRecord[K, V], transport: List[KafkaHeader]): UIO[ProducerRecord[K, V]] = {
        val existingHeders = producerRecord.headers()
        val allHeaders = transport ++ existingHeders.toArray.toList
        ZIO.succeed(new ProducerRecord[K, V](
          producerRecord.topic(),
          producerRecord.partition(),
          producerRecord.timestamp(),
          producerRecord.key(),
          producerRecord.value(),
          allHeaders.asJava
        ))
      }

      def produceTracedRecord[K, V, R, E, A](producerRecord: ProducerRecord[K, V])(produce: ProducerRecord[K, V] => ZIO[R, E, A]): ZIO[R, E, A] = {
        interpreter.spanOnRequest[ProducerRecord[K, V], R, E, A](
          record => tracerAlgebra.spanName(record),
          enrichWithTransport
        )(producerRecord, produce)
      }

    }
  )
}
