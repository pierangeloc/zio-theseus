package billing

import billing.BillableSessionRepository.BillableSession
import billing.DoobieBillableSessionRepostory.Queries
import doobie.Transactor
import doobie.util.fragment
import io.tuliplogic.ziotoolbox.doobie.DBError
import zio.{IO, LogAnnotation, Task, ZIO, ZLayer}

import java.time.Instant
import java.util.UUID

trait BillableSessionRepository {
  def insert(billableSession: BillableSession): IO[DBError, Unit]
  def get(billableSessionId: UUID): IO[DBError, Option[BillableSession]]
}

object BillableSessionRepository {
  case class BillableSession(
    id: UUID,
    customerName: String,
    tariffId: String,
    pricePerMinute: Double,
    starteAt: Instant,
    endedAt: Instant,
    totalPrice: Double
  )
}

class DoobieBillableSessionRepostory(tx: Transactor[Task]) extends BillableSessionRepository {

  import doobie.implicits._
  import zio.interop.catz._
  override def insert(billableSession: BillableSession): IO[DBError, Unit] = {
    ZIO.logAnnotate(
      LogAnnotation("billableSession.id", billableSession.id.toString),
      LogAnnotation("billableSession.tariff", billableSession.tariffId),
      LogAnnotation("billableSession.customerName", billableSession.customerName)
    )
    ZIO.logInfo(s"upserting BillableSession $billableSession") *>
      Queries
        .insert(billableSession)
        .update
        .run
        .transact(tx)
        .mapError(t => DBError("Error upserting charge session", Some(t)))
        .unit
  }

  override def get(billableSessionId: UUID): IO[DBError, Option[BillableSession]] =
    ZIO.logInfo("fetching BillableSession with id $billableSessionId") *>
      Queries
        .get(billableSessionId)
        .query[BillableSession]
        .to[List]
        .transact(tx)
        .mapBoth(t => DBError("Error upserting charge session", Some(t)), css => css.headOption)
}

object DoobieBillableSessionRepostory {
  object Queries {
    import doobie.implicits._
    import doobie.postgres.implicits._
    def insert(billableSession: BillableSession): fragment.Fragment =
      sql"""
           insert into billable_session (id, customer, tariff_id, price_per_minute, started_at, ended_at, total_price)
           values (${billableSession.id}, ${billableSession.customerName}, ${billableSession.tariffId}, ${billableSession.pricePerMinute}, ${billableSession.starteAt}, ${billableSession.endedAt}, ${billableSession.totalPrice})
         """

    def get(billableSessionId: UUID): fragment.Fragment =
      sql"""
           select id, customer, tariff_id, price_per_minute, started_at, ended_at, total_price
           from billable_session
           where id = $billableSessionId
         """
  }

  val layer = ZLayer.fromZIO {
    for {
      tx <- ZIO.service[Transactor[Task]]
    } yield new DoobieBillableSessionRepostory(tx)
  }
}
