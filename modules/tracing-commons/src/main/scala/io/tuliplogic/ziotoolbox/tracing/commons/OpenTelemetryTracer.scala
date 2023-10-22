package io.tuliplogic.ziotoolbox.tracing.commons

import zio.{URIO, ZIO}
import zio.telemetry.opentelemetry.tracing.Tracing


//TODO: see if we can reuse this in grpc/http modules
trait OpenTelemetryTracer[In, Out] {
  def spanName(request: In): String
  def before(request: In): URIO[Tracing, Unit]
  def after(response: Out): URIO[Tracing, Unit]
}

object OpenTelemetryTracer {
  def onlySpan(span: String): OpenTelemetryTracer[Any, Any] = new OpenTelemetryTracer[Any, Any] {
    override def spanName(request: Any): String = span
    override def before(request: Any): URIO[Tracing, Unit] = ZIO.unit
    override def after(response: Any): URIO[Tracing, Unit] = ZIO.unit
  }
}
