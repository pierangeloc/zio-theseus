package io.tuliplogic.ziotoolbox.tracing.commons

import io.opentelemetry.api.common.Attributes
import zio.ZIO
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.tracing.Tracing

object TracingUtils {
  def makeAttributes(pairs: (String, String)*): Attributes =
    pairs.foldLeft(Attributes.builder())((builder, kv) => builder.put(kv._1, kv._2))
      .build()

  def putInBaggage(pairs: List[(String, String)]): ZIO[Baggage, Nothing, Unit] =
    ZIO.serviceWith[Baggage](b => pairs.foreach { case (k, v) => b.set(k, v) })

  def createEventWithAttributes(eventName: String, attributes: (String, String)*): ZIO[Tracing, Nothing, Unit] =
    ZIO.serviceWithZIO[Tracing](_.addEventWithAttributes(eventName, TracingUtils.makeAttributes(attributes: _*)))

}
