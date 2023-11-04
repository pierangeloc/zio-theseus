package io.tuliplogic.ziotoolbox.tracing.kafka.producer


import io.tuliplogic.ziotoolbox.tracing.commons.{ClientTracerBaseInterpreter, TracerAlgebra}
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.{Header => KafkaHeader}
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{Trace, UIO, ZIO, ZIOAspect, ZLayer}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

object ProducerTracing {

  trait KafkaRecordTracer {
    def tracedRecord[K, V](producerRecord: ProducerRecord[K, V]): UIO[ProducerRecord[K, V]]
  }

  object KafkaRecordTracer {
    val tracerDsl = TracerAlgebra.dsl[ProducerRecord[_, _], Any]

    val defaultKafkaProducerTracerAlgebra: TracerAlgebra[ProducerRecord[_, _], Any] = {
      import tracerDsl._
      withRequestAttributes(req =>
        Map(
          "kafka.topic" -> req.topic(),
          "kafka.partition" -> req.partition().toString,
        )
      )
    }

    def layer(tracerAlgebra: TracerAlgebra[ProducerRecord[_, _], Any] = defaultKafkaProducerTracerAlgebra): ZLayer[Baggage with Tracing, Nothing, KafkaRecordTracer] = {
      ZLayer.fromZIO {
        for {
          tracing <- ZIO.service[Tracing]
          baggage <- ZIO.service[Baggage]
          tracer <- new KafkaProducerInterpreter(tracerAlgebra, tracing, baggage).interpretation
        } yield tracer
      }
    }


    def traced[K, V]: ZIOAspect[Nothing, KafkaRecordTracer, Nothing, Any, ProducerRecord[K, V], ProducerRecord[K, V]] =
      new ZIOAspect[Nothing, KafkaRecordTracer, Nothing, Any, ProducerRecord[K, V], ProducerRecord[K, V]] {
        override def apply[R >: Nothing <: KafkaRecordTracer, E >: Nothing <: Any, A >: ProducerRecord[K, V] <: ProducerRecord[K, V]](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          for {
            tkp <- ZIO.service[KafkaRecordTracer]
            record <- zio
            tr <- tkp.tracedRecord(record)
          } yield tr
      }
  }

  private class KafkaProducerInterpreter(
                                  val tracerAlgebra: TracerAlgebra[ProducerRecord[_, _], Any],
                                  val tracing: Tracing,
                                  val baggage: Baggage,
                                ) extends ClientTracerBaseInterpreter[ProducerRecord[_, _], Any, List[KafkaHeader], KafkaRecordTracer] {
    override protected def carrierToTransport(carrier: OutgoingContextCarrier[mutable.Map[String, String]]): List[KafkaHeader] =
      carrier.kernel.toList.map {
        case (k, v) => new RecordHeader(k, v.getBytes("UTF-8"))
      }

    override def interpretation: UIO[KafkaRecordTracer] = ZIO.succeed(
      new KafkaRecordTracer {
        override def tracedRecord[K, V](producerRecord: ProducerRecord[K, V]): UIO[ProducerRecord[K, V]] =
          for {
            outgoingCarrier <- beforeSendingRequest(producerRecord)
            headers = carrierToTransport(outgoingCarrier)
            existingHeders = producerRecord.headers()
            allHeaders = headers ++ existingHeders.toArray.toList
          } yield new ProducerRecord[K, V](
            producerRecord.topic(),
            producerRecord.partition(),
            producerRecord.timestamp(),
            producerRecord.key(),
            producerRecord.value(),
            allHeaders.asJava
          )

      }
    )
  }
}

