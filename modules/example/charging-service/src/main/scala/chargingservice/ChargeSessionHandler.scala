package chargingservice

import chargingservice.ChargeSessionApi.StopChargeSessionResponse
import chargingservice.model.ChargeSessionEnded
import zio.{Task, UIO, ZIO, ZLayer}

trait ChargeSessionHandler {
  def startSession(request: ChargeSessionApi.StartChargeSessionRequest): Task[ChargeSessionApi.StartChargeSessionResponse]
  def stopSession(request: ChargeSessionApi.StopChargeSessionRequest): Task[ChargeSessionApi.StopChargeSessionResponse]
}

class LiveChargeSessionHandler(chargeSessionRepository: ChargeSessionRepository, publisher: SessionPublisher) extends ChargeSessionHandler {
  override def startSession(request: ChargeSessionApi.StartChargeSessionRequest): Task[ChargeSessionApi.StartChargeSessionResponse] = {
    for {
      now <- zio.Clock.instant
      uuid <- zio.Random.nextUUID
      outcome <- ZIO.succeed(true)// zio.Random.nextBoolean
      res <- chargeSessionRepository.upsert(ChargeSessionRepository.ChargeSession(
        id = uuid,
        chargePointId = request.chargePointId,
        chargeCardId = request.chargeCardId,
        starteAt = now,
        endedAt = None
      )).as(ChargeSessionApi.StartChargeSessionResponse(success = outcome, sessionId = "123"))
    } yield res

  }

  override def stopSession(request: ChargeSessionApi.StopChargeSessionRequest): Task[ChargeSessionApi.StopChargeSessionResponse] = {
    for {
      uuid <- ZIO.succeed(java.util.UUID.fromString(request.sessionId))
      maybeSession <- chargeSessionRepository.get(uuid)
      session <- ZIO.fromOption(maybeSession).orElseFail(new IllegalArgumentException(s"Session with id ${uuid} not found"))
      now <- zio.Clock.instant
      _ <- chargeSessionRepository.upsert(session.copy(endedAt = Some(now)))
      _ <- publisher.publish(
        ChargeSessionEnded(
          id = session.id,
          chargePointId = session.chargePointId,
          chargeCardId = session.chargeCardId,
          starteAt = session.starteAt,
          endedAt = now
        ))
    } yield StopChargeSessionResponse(success = true)
  }
}


object LiveChargeSessionHandler {
  val layer = ZLayer.fromZIO {
    for {
      csr <- ZIO.service[ChargeSessionRepository]
      sessionPublisher <- ZIO.service[SessionPublisher]
    } yield new LiveChargeSessionHandler(csr, sessionPublisher)
  }
}
