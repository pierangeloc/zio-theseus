package io.tuliplogic.ziotoolbox.tracing.kafka.producer

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes
import io.tuliplogic.ziotoolbox.tracing.commons.{ClientBaseTracingInterpreter, TracerAlgebra}
import io.tuliplogic.ziotoolbox.tracing.kafka.producer.ProducerTracing.KafkaProducerTracer
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

  trait KafkaProducerTracer {
    def produceTracedRecord[K, V, R, E, A](producerRecord: ProducerRecord[K, V])(produce: ProducerRecord[K, V] => ZIO[R, E, A]): ZIO[R, E, A]
  }

  object KafkaProducerTracer {
    def produceTracedRecord[K, V, R <: KafkaProducerTracer, E, A](producerRecord: ProducerRecord[K, V])(produce: ProducerRecord[K, V] => ZIO[R, E, A]): ZIO[R, E, A] =
      ZIO.serviceWithZIO[KafkaProducerTracer](tracer => tracer.produceTracedRecord(producerRecord)(produce))


    val tracerDsl = TracerAlgebra.dsl[ProducerRecord[_, _], Any]

    val defaultKafkaProducerTracerAlgebra: TracerAlgebra[ProducerRecord[_, _], Any] = {
      import tracerDsl._
      spanName(r => s"kafka.producer - ${r.topic}") &
      requestAttributes(req =>
        Map(
          "kafka.topic"                                                   -> req.topic(),
          SemanticAttributes.MESSAGING_DESTINATION_NAME.getKey            -> req.topic(),
          SemanticAttributes.MESSAGING_KAFKA_DESTINATION_PARTITION.getKey -> req.partition().toString
        )
      )
    }

    def layer(
      tracerAlgebra: TracerAlgebra[ProducerRecord[_, _], Any] = defaultKafkaProducerTracerAlgebra
    ): ZLayer[Baggage with Tracing, Nothing, KafkaProducerTracer] =
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
) extends ClientBaseTracingInterpreter[ProducerRecord[_, _], Any, List[KafkaHeader], KafkaProducerTracer] { interpreter =>
  override val spanKind: SpanKind = SpanKind.PRODUCER
  override protected def carrierToTransport(
    carrier: OutgoingContextCarrier[mutable.Map[String, String]]
  ): List[KafkaHeader] =
    carrier.kernel.toList.map { case (k, v) =>
      new RecordHeader(k, v.getBytes("UTF-8"))
    }

  override def interpretation: UIO[KafkaProducerTracer] = ZIO.succeed(
    new KafkaProducerTracer {
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
