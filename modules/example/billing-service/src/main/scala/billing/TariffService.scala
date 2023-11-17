package billing

import billing.TariffService.Tariff
import zio.{UIO, ZIO, ZLayer}

trait TariffService {
  def getTariff(customerId: String, chargePointId: String): UIO[Tariff]
}
object TariffService {
  case class Tariff(id: String, pricePerMinute: Double)
}

object OneTariffService {
  val live = ZLayer.succeed(new TariffService {
    override def getTariff(customerId: String, chargePointId: String): UIO[Tariff] =
      ZIO.succeed(Tariff("tariff-123", 100))
  })
}
