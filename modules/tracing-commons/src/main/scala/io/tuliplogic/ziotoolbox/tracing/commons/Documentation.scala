package io.tuliplogic.ziotoolbox.tracing.commons

import io.opentelemetry.api.trace.SpanKind
import io.tuliplogic.ziotoolbox.tracing.commons.Documentation.Attribute

case class Documentation[Req: Show, Res: Show] (
  title: String,
  description: String,
  exampleRequest: Req,
  exampleResponse: Res,
  attributesFromRequest: List[Attribute],
  attributesFromResponse: List[Attribute]
) {
  def show(spanKind: SpanKind): String =
    s"""
      |Title: $title
      |Description: $description
      |
      |***********
      |
      |
      |""".stripMargin

  def showClient =
    s"""
      | Outgoing Request  : ${Show[Req].show(exampleRequest)}
      | Request Attributes:
      | ${attributesFromRequest.map(a => s"${a.name}: ${a.value}").mkString("\n")} \n
      |
      | Incoming Response : ${Show[Res].show(exampleResponse)}
      | Response Attributes:
      | ${attributesFromResponse.map(a => s"${a.name}: ${a.value}").mkString("\n")} \n
      |
      |""".stripMargin

  def showProducer: String =
    s""""""

  def showConsumer: String =
    s""""""
}

object Documentation {
  case class Attribute(name: String, value: String)
}
