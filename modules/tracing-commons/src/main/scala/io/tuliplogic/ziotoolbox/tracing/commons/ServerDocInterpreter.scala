package io.tuliplogic.ziotoolbox.tracing.commons

import io.opentelemetry.api.trace.SpanKind

class ServerDocInterpreter[Req: Show, Res: Show](tracerAlgebra: TracerAlgebra[Req, Res]) {
  val spanKind: SpanKind = ???
  val title: String = ???
  val description: String = ???
  val exampleRequest: Req = ???
  val exampleResponse: Res = ???

//  def document: Documentation
}
