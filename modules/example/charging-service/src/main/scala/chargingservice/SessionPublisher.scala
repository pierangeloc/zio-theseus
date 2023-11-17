package chargingservice

import chargingservice.model.ChargeSessionEnded
import io.circe.Decoder
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import zio.{Task, UIO, ZIO, ZLayer}
import zio.kafka.producer.Producer
import zio.kafka.serde.Serde
import io.circe.generic.auto._
import io.circe.parser
import io.circe.syntax._

import java.util.UUID

trait SessionPublisher {
  def publish(chargeSessionEnded: ChargeSessionEnded): Task[Unit]
}

case class KafkaPublisher(config: KafkaPublisher.Config, producer: Producer) extends SessionPublisher {

  val eventSerde: Serde[Any, ChargeSessionEnded] =
    Serde.string.inmapM[Any, ChargeSessionEnded](s =>
      ZIO
        .fromEither(parser.decode[ChargeSessionEnded](s))
        .mapError(e => new IllegalArgumentException(s"error decoding json ${s} - error: $e"))
    )(chargeSessionEnded => ZIO.succeed(chargeSessionEnded.asJson.noSpaces))

  override def publish(chargeSessionEnded: ChargeSessionEnded): Task[Unit] =
    ZIO.logAnnotate("sessionId", chargeSessionEnded.id.toString) {
      val record = new ProducerRecord[UUID, ChargeSessionEnded](
        config.topic,
        0,
        0L,
        chargeSessionEnded.id,
        chargeSessionEnded
      )

      producer
        .produce(
          record,
          keySerializer = Serde.uuid,
          valueSerializer = eventSerde
        )
        .unit *> ZIO.logInfo(s"produced ChargeSessionEnded $chargeSessionEnded to Kafka ")
    }

}

object KafkaPublisher {
  case class Config(topic: String,
                    bootstrapServers: String)

  val layer = ZLayer.fromZIO {
    for {
      producer <- ZIO.service[Producer]
      config   <- ZIO.service[Config]
    } yield KafkaPublisher(config, producer)
  }
}
