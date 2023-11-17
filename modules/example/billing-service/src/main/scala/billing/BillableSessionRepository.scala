package billing

import billing.BillableSessionRepository.BillableSession
import billing.DoobieBillableSessionRepostory.Queries
import doobie.Transactor
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
        .run
        .transact(tx)
        .mapError(t => DBError("Error upserting charge session", Some(t)))
        .unit
  }

  override def get(billableSessionId: UUID): IO[DBError, Option[BillableSession]] =
    ZIO.logInfo("fetching BillableSession with id $billableSessionId") *>
      Queries
        .get(billableSessionId)
        .to[List]
        .transact(tx)
        .mapBoth(t => DBError("Error upserting charge session", Some(t)), css => css.headOption)
}

object DoobieBillableSessionRepostory {
  object Queries {
    import doobie.implicits._
    import doobie.postgres.implicits._
    def insert(billableSession: BillableSession): doobie.Update0 =
      sql"""
           insert into billable_sessions (id, customer, tariff_id, price_per_minute, started_at, ended_at, total_price)
           values (${billableSession.id}, ${billableSession.customerName}, ${billableSession.tariffId}, ${billableSession.pricePerMinute}, ${billableSession.starteAt}, ${billableSession.endedAt}, ${billableSession.totalPrice})
         """.update

    def get(billableSessionId: UUID): doobie.Query0[BillableSession] =
      sql"""
           select id, customer, tariff_id, price_per_minute, started_at, ended_at, total_price
           from billable_sessions
           where id = $billableSessionId
         """.query[BillableSession]
  }

  val layer = ZLayer.fromZIO {
    for {
      tx <- ZIO.service[Transactor[Task]]
    } yield new DoobieBillableSessionRepostory(tx)
  }
}
