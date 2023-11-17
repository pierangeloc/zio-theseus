package chargingservice

import chargingservice.ChargeSessionRepository.ChargeSession
import doobie.hikari.HikariTransactor
import io.tuliplogic.ziotoolbox.doobie.DBError
import zio.{IO, Task, ZIO, ZLayer}
import doobie.postgres.implicits._

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

class DoobieChargeSessionRepository extends ChargeSessionRepository {
  override def upsert(chargeSession: ChargeSession): IO[DBError, Unit] = ???

  override def get(chargeSessionId: UUID): IO[DBError, Option[ChargeSession]] = ???
}

object DoobieChargeSessionRepository {
  object Queries {
    import doobie.implicits._
    def upsert(chargeSession: ChargeSession): doobie.Update0 =
      sql"""
           insert into charge_sessions (id, charge_point_id, charge_card_id, started_at, ended_at)
           values (${chargeSession.id}, ${chargeSession.chargePointId}, ${chargeSession.chargeCardId}, ${chargeSession.starteAt}, ${chargeSession.endedAt})
         """.update

    def get(chargeSessionId: UUID): doobie.Query0[ChargeSession] =
      sql"""
           select id, charge_point_id, token_id, started_at, ended_at
           from charge_sessions
           where id = $chargeSessionId
         """.query[ChargeSession]
  }

  val live = ZLayer.fromZIO {
    for {
      tx <- ZIO.service[HikariTransactor[Task]]
    } yield new ChargeSessionRepository {

      import doobie.implicits._
      import zio.interop.catz._

      override def upsert(chargeSession: ChargeSession): IO[DBError, Unit] =
        ZIO.logInfo(s"upserting ChargeSession $chargeSession") *>
          Queries.upsert(chargeSession).run.transact(tx).mapError(t => DBError("Error upserting charge session", Some(t))).unit

      override def get(chargeSessionId: UUID): IO[DBError, Option[ChargeSession]] =
        ZIO.logInfo(s"fetching ChargeSession with id $chargeSessionId") *>
          Queries.get(chargeSessionId).to[List].transact(tx)
            .mapBoth(t => DBError("Error upserting charge session", Some(t)),
              css => css.headOption)
    }
  }
}
