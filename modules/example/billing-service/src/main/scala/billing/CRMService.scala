package billing

import billing.CRMService.Customer
import zio.{UIO, ULayer, ZIO, ZLayer}

trait CRMService {
  def lookupCustomer(chargeCardId: String): UIO[Option[Customer]]
}

object CRMService {
  case class Customer(id: String, name: String)
}

object OneCustomerCRMService {
  val live: ULayer[CRMService] = ZLayer.succeed(
    new CRMService {
      override def lookupCustomer(chargeCardId: String): UIO[Option[Customer]] =
        ZIO.some(Customer("John", "Smith"))
    }
  )
}
