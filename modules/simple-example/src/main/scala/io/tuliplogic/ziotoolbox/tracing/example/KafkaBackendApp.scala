package io.tuliplogic.ziotoolbox.tracing.example

import io.tuliplogic.ziotoolbox.tracing.commons.{Bootstrap, OTELTracer, TracingUtils}
import io.tuliplogic.ziotoolbox.tracing.kafka.consumer.KafkaConsumerTracer
import io.tuliplogic.ziotoolbox.tracing.kafka.producer.ProducerTracing.KafkaProducerTracer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}
import zio._
import zio.kafka.consumer.{Consumer, ConsumerSettings, Subscription}
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.kafka.serde.Serde
import zio.stream.ZStream
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.tracing.Tracing

object KafkaBackendApp extends ZIOAppDefault {

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
    Bootstrap.defaultBootstrap

  def process(record: ConsumerRecord[Long, String]) =
    for {
      baggage <- ZIO.service[Baggage]
      baggageContent <- baggage.getAll
      _ <- ZIO.logInfo(s"Baggage content: $baggageContent")
      repo <- ZIO.service[CallRecordRepository]
      now <- zio.Clock.instant
      _ <- repo.saveRecord(CallRecordRepository.CallRecord(now, s"Kafka consumer record ${now.toEpochMilli / 100}"))
      _ <- TracingUtils.createEventWithAttributes("sent event")
    } yield ()

  val consumer: ZStream[Consumer with Baggage with Tracing with CallRecordRepository with KafkaConsumerTracer, Throwable, Nothing] =
    Consumer
      .plainStream(Subscription.topics("ziotelemetry"), Serde.long, Serde.string)
      .mapZIO(r =>
        for {
          _ <- ZIO.logInfo(s"Consumed record ${r}, now saving record")
          _ <- (process(r.record) @@ KafkaConsumerTracer.aspects.kafkaTraced(r.record)).forkDaemon
        } yield r
      )
      .map(_.offset)
      .aggregateAsync(Consumer.offsetBatches)
      .mapZIO(_.commit)
      .drain

  def consumerLayer =
    ZLayer.scoped(
      Consumer.make(
        ConsumerSettings(List("localhost:29092")).withGroupId("group")
      )
    )

  override def run = {
    ZIO.logInfo("Running KAFKA app") *>
    consumer
      .runDrain
      .provide(
        consumerLayer,
        CallRecordRepository.workingRepoLayer,
//        Baggage.logAnnotated,
//        ContextStorage.fiberRef,
//        Tracing.live,
        Bootstrap.tracingLayer,
        OTELTracer.default("kafka-backend-app"),
        KafkaConsumerTracer.layer(KafkaConsumerTracer.defaultConsumerTracingAlgebra("kafka-consumer"))
      )
  }
}

object KafkaClient extends ZIOAppDefault {
  val producerLayer =
    ZLayer.scoped(
      Producer.make(
        settings = ProducerSettings(List("localhost:29092"))
      )
    )

  def produce: ZIO[Producer with KafkaProducerTracer, Throwable, RecordMetadata] = {
    KafkaProducerTracer.produceTracedRecord(new ProducerRecord[Long, String](
      "ziotelemetry",
      0,
      0L,
      1L,
      "Something"
    )
    )(r =>
      Producer.produce[Any, Long, String](
        r,
        keySerializer = Serde.long,
        valueSerializer = Serde.string
      )
    )
  }

  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] =
    produce.provide(
      producerLayer,
//      Tracing.live,
//      Baggage.logAnnotated,
//      ContextStorage.fiberRef,
      Bootstrap.tracingLayer,
      OTELTracer.default("kafka-backend-app"),
      KafkaProducerTracer.layer()
    )
}
