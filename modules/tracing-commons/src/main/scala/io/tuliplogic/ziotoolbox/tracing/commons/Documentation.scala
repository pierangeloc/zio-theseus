package io.tuliplogic.ziotoolbox.tracing.commons

import io.tuliplogic.ziotoolbox.tracing.commons.Documentation.Attribute

case class Documentation (
  title: String,
  description: String,
  attributesFromRequest: List[Attribute],
  attributesFromResponse: List[Attribute]
                         )

object Documentation {
  case class Attribute(name: String, value: String)
}
