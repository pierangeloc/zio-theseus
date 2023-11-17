package chargingservice

import chargingservice.ChargeSessionRepository.ChargeSession
import chargingservice.DoobieChargeSessionRepository.Queries
import doobie.hikari.HikariTransactor
import io.tuliplogic.ziotoolbox.doobie.DBError
import zio.{IO, Task, ZIO, ZLayer}
import doobie.postgres.implicits._
import doobie.util.fragment
import doobie.util.transactor.Transactor

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
    chargeCardId: String,
    starteAt: Instant,
    endedAt: Option[Instant]
  )
}

class DoobieChargeSessionRepository(tx: Transactor[Task]) extends ChargeSessionRepository {

  import doobie.implicits._
  import zio.interop.catz._
  import doobie.DoobieTracing.syntax.TracedFragment

  override def upsert(chargeSession: ChargeSession): IO[DBError, Unit] =
    ZIO.logInfo(s"upserting ChargeSession $chargeSession") *>
      Queries.upsert(chargeSession).update.run.transact(tx).mapError(t => DBError("Error upserting charge session", Some(t))).unit

  override def get(chargeSessionId: UUID): IO[DBError, Option[ChargeSession]] =
    ZIO.logInfo(s"fetching ChargeSession with id $chargeSessionId") *>
      Queries.get(chargeSessionId).query[ChargeSession].to[List].transact(tx)
        .mapBoth(t => DBError(s"Error fetching charge session with id $chargeSessionId", Some(t)),
          css => css.headOption)
}

object DoobieChargeSessionRepository {
  object Queries {
    import doobie.implicits._
    def upsert(chargeSession: ChargeSession): fragment.Fragment =
      sql"""
           insert into charge_session (id, charge_point_id, charge_card_id, started_at, ended_at)
           values (${chargeSession.id}, ${chargeSession.chargePointId}, ${chargeSession.chargeCardId}, ${chargeSession.starteAt}, ${chargeSession.endedAt})
           on conflict (id) do update set
             charge_point_id = ${chargeSession.chargePointId},
             charge_card_id = ${chargeSession.chargeCardId},
             started_at = ${chargeSession.starteAt},
             ended_at = ${chargeSession.endedAt}
         """

    def get(chargeSessionId: UUID): fragment.Fragment =
      sql"""
           select id, charge_point_id, charge_card_id, started_at, ended_at
           from charge_session
           where id = $chargeSessionId
         """
  }

  val live = ZLayer.fromZIO {
    for {
      tx <- ZIO.service[HikariTransactor[Task]]
    } yield new DoobieChargeSessionRepository(tx)  }
}
