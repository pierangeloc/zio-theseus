package chargingservice
import sttp.tapir._
import sttp.tapir.json.circe._
import sttp.tapir.generic.auto._
import io.circe.generic.auto._


class ChargeSessionApi {

}

object ChargeSessionApi {
  case class StartChargeSessionRequest(
    chargePointId: String,
    chargeTokenId: String
  )

  case class StartChargeSessionResponse(
    success: Boolean,
    sessionId: String
  )

  case class StopChargeSessionRequest(
    sessionId: String
  )

  case class StopChargeSessionResponse(
    success: Boolean,
  )

  val startSessionEndpoint =
    endpoint.in("session" / "start")
      .in(jsonBody[StartChargeSessionRequest])
      .out(jsonBody[StartChargeSessionResponse])
      .errorOut(stringBody)

  val stopSessionEndpoint =
    endpoint.in("session" / "stop")
      .in(jsonBody[StopChargeSessionRequest])
      .out(jsonBody[StopChargeSessionResponse])
      .errorOut(stringBody)

}
