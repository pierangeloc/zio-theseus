package io.tuliplogic.ziotoolbox.tracing.example

import sttp.model.QueryParams
import sttp.tapir._
object StatusEndpoints {

  val backendStatusEndpoint: Endpoint[Unit, Unit, Unit, String, Any] = endpoint.in("status")
    .out(plainBody[String])

  val proxyStatusesEndpoint: Endpoint[Unit, QueryParams, Unit, String, Any] = endpoint.in("statuses")
    .in(queryParams)
    .out(plainBody[String])

}
