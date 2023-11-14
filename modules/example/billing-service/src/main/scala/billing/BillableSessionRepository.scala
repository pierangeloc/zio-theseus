package billing

import billing.BillableSessionRepository.BillableSession
import io.tuliplogic.ziotoolbox.doobie.DBError
import zio.IO

import java.time.Instant
import java.util.UUID

trait BillableSessionRepository {
  def upsert(billableSession: BillableSession): IO[DBError, Unit]
  def get(billableSessionId: UUID): IO[DBError, Option[BillableSession]]
}

object BillableSessionRepository {
  case class BillableSession(
    id: UUID,
    timestamp: Instant,
    customer: String,
    tariffId: String,
    pricePerMinute: Double,
    starteAt: Instant,
    endedAt: Instant,
    totalPrice: Double
  )
}
