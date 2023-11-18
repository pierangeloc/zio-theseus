package billing

import billing.KafkaSessionConsumer.KafkaConfig
import billing.KafkaSessionConsumer.model.{ChargeSessionEnded, eventSerde}
import io.circe.generic.auto._
import io.circe.parser
import io.circe.syntax._
import io.tuliplogic.ziotoolbox.tracing.kafka.consumer.KafkaConsumerTracer
import zio.{ZIO, ZLayer}
import zio.kafka.consumer.{Consumer, Subscription}
import zio.kafka.serde.Serde
import zio.stream.ZStream

import java.time.Instant
import java.util.UUID


trait SessionConsumer {
  def consumeSessions: ZStream[Any, Throwable, Unit]
}

class KafkaSessionConsumer(kafkaConsumerTracer: KafkaConsumerTracer, kafkaConsumer: Consumer, kafkaConfig: KafkaConfig, crmServic: CRMService, tariffService: TariffService, billableSessionRepository: BillableSessionRepository) extends SessionConsumer {

  override def consumeSessions: ZStream[Any, Throwable, Unit] =
    kafkaConsumer
      .plainStream(
        Subscription.topics(kafkaConfig.sessionsTopic),
        keyDeserializer = Serde.uuid,
        valueDeserializer = eventSerde
      )
      .mapZIO { record =>
        (processSingleSession(record.record.value) @@ KafkaConsumerTracer.aspects.kafkaTraced(record.record))
          .forkDaemon.as(record.offset)
      }
      .aggregateAsync(Consumer.offsetBatches)
      .mapZIO(_.commit)
      .provideLayer(ZLayer.succeed(kafkaConsumerTracer))

  private def processSingleSession(chargeSessionEnded: ChargeSessionEnded) =
    for {
      _ <- ZIO.logInfo(s"start processing ChargeSessionEnded: $chargeSessionEnded ")
      maybeCustomer <- crmServic.lookupCustomer(chargeSessionEnded.chargeCardId)
      customer <- ZIO.fromOption(maybeCustomer).orElseFail("customer not found")
      tariff   <- tariffService.getTariff(customer.id, chargeSessionEnded.chargePointId)
      billableSession = BillableSessionRepository.BillableSession(
        id = chargeSessionEnded.id,
        customerName = customer.name,
        tariffId = tariff.id,
        pricePerMinute = tariff.pricePerMinute,
        starteAt = chargeSessionEnded.starteAt,
        endedAt = chargeSessionEnded.endedAt,
        totalPrice = tariff.pricePerMinute * (chargeSessionEnded.endedAt.getEpochSecond - chargeSessionEnded.starteAt.getEpochSecond)
      )
      _ <- billableSessionRepository.insert(billableSession)
      _ <- ZIO.logInfo(s"end processing ChargeSessionEnded: $chargeSessionEnded ")
    } yield ()

}

object KafkaSessionConsumer {

  object model {
    case class ChargeSessionEnded(
       id: UUID,
       chargePointId: String,
       chargeCardId: String,
       starteAt: Instant,
       endedAt: Instant
     )

    val eventSerde: Serde[Any, ChargeSessionEnded] =
      Serde.string.inmapM[Any, ChargeSessionEnded](s =>
        ZIO
          .fromEither(parser.decode[ChargeSessionEnded](s))
          .mapError(e => new IllegalArgumentException(s"error decoding json ${s} - error: $e"))
      )(chargeSessionEnded => ZIO.succeed(chargeSessionEnded.asJson.noSpaces))

  }

  case class KafkaConfig(
    bootstrapServers: String,
    sessionsTopic: String
  )

  val layer = ZLayer.fromZIO {
    for {
      consumer <- ZIO.service[Consumer]
      config   <- ZIO.service[KafkaConfig]
      crmService <- ZIO.service[CRMService]
      tariffService <- ZIO.service[TariffService]
      kafkaConsumerTracer <- ZIO.service[KafkaConsumerTracer]
      billableSessionRepository <- ZIO.service[BillableSessionRepository]
    } yield new KafkaSessionConsumer(kafkaConsumerTracer, consumer, config, crmService, tariffService, billableSessionRepository)
  }
}


