package chargingservice

import zio.{Task, UIO}

trait ChargeSessionHandler {
  def startSession(request: ChargeSessionApi.StartChargeSessionRequest): Task[ChargeSessionApi.StartChargeSessionResponse]
  def stopSession(request: ChargeSessionApi.StopChargeSessionRequest): Task[ChargeSessionApi.StopChargeSessionResponse]
}
