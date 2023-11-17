package chargingservice

import java.time.Instant
import java.util.UUID

object model {
  case class ChargeSessionEnded(
    id: UUID,
    chargePointId: String,
    chargeCardId: String,
    starteAt: Instant,
    endedAt: Instant
  )
}
