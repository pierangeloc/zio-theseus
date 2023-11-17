package chargingservice

import charginghub.charging_hub_api.{StartSessionRequest, StopSessionRequest, ZioChargingHubApi}
import chargingservice.ChargeSessionApi.StopChargeSessionResponse
import chargingservice.model.ChargeSessionEnded
import zio.{Task, UIO, ZIO, ZLayer}

import java.util.UUID

trait ChargeSessionHandler {
  def startSession(request: ChargeSessionApi.StartChargeSessionRequest): Task[ChargeSessionApi.StartChargeSessionResponse]
  def stopSession(request: ChargeSessionApi.StopChargeSessionRequest): Task[ChargeSessionApi.StopChargeSessionResponse]
}

class LiveChargeSessionHandler(chargingHubClient: ZioChargingHubApi.ChargingHubApiClient, chargeSessionRepository: ChargeSessionRepository, publisher: SessionPublisher) extends ChargeSessionHandler {
  override def startSession(request: ChargeSessionApi.StartChargeSessionRequest): Task[ChargeSessionApi.StartChargeSessionResponse] = {
    for {
      now <- zio.Clock.instant
      requestUUID <- zio.Random.nextUUID
      hubResponse <- chargingHubClient.startSession(
        StartSessionRequest(
          requestUUID.toString,
          request.chargePointId,
          request.chargeCardId
        )
      )
      res <- if (hubResponse.success) {
        chargeSessionRepository.upsert(ChargeSessionRepository.ChargeSession(
          id = UUID.fromString(hubResponse.sessionId),
          chargePointId = request.chargePointId,
          chargeCardId = request.chargeCardId,
          starteAt = now,
          endedAt = None
        )).as(ChargeSessionApi.StartChargeSessionResponse(success = hubResponse.success, sessionId = hubResponse.sessionId))
      } else ZIO.succeed(ChargeSessionApi.StartChargeSessionResponse(success = hubResponse.success, sessionId = hubResponse.sessionId))
    } yield res

  }

  override def stopSession(request: ChargeSessionApi.StopChargeSessionRequest): Task[ChargeSessionApi.StopChargeSessionResponse] = {
    for {
      uuid <- ZIO.succeed(java.util.UUID.fromString(request.sessionId))
      maybeSession <- chargeSessionRepository.get(uuid)
      session <- ZIO.fromOption(maybeSession).orElseFail(new IllegalArgumentException(s"Session with id ${uuid} not found"))
      now <- zio.Clock.instant
      hubStopResponse <- chargingHubClient.stopSession(
        StopSessionRequest(
          session.id.toString
        )
      )
      _ <- if (hubStopResponse.success) {
        chargeSessionRepository.upsert(session.copy(endedAt = Some(now))) *>
        publisher.publish(
          ChargeSessionEnded(
            id = session.id,
            chargePointId = session.chargePointId,
            chargeCardId = session.chargeCardId,
            starteAt = session.starteAt,
            endedAt = now
          )) *> ZIO.logInfo("Session start succeeded, performing persistence and publication")
      } else ZIO.logInfo("Session start failed, skipping persistence and publication")
    } yield StopChargeSessionResponse(success = true)
  }
}


object LiveChargeSessionHandler {
  val layer = ZLayer.fromZIO {
    for {
      csr <- ZIO.service[ChargeSessionRepository]
      sessionPublisher <- ZIO.service[SessionPublisher]
      hubClient <- ZIO.service[ZioChargingHubApi.ChargingHubApiClient]
    } yield new LiveChargeSessionHandler(hubClient, csr, sessionPublisher)
  }
}
