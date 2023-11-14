package chargingservice

import chargingservice.ChargeSessionRepository.ChargeSession
import io.tuliplogic.ziotoolbox.doobie.DBError
import zio.IO

import java.time.Instant
import java.util.UUID

trait ChargeSessionRepository {
  def upsert(chargeSession: ChargeSession): IO[DBError, Unit]
  def get(chargeSessionId: UUID): IO[DBError, Option[ChargeSession]]
}

object ChargeSessionRepository {
  case class ChargeSession(
    id: UUID,
    chargePointId: String,
    tokenId: String,
    starteAt: Instant,
    endedAt: Option[Instant]
  )
}
