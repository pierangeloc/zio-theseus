package io.tuliplogic.ziotoolbox.tracing.example

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes
import zio._

object JaegerTracer {

  val default = live("http://localhost:4317", "SRS-opentelemetry-zio")
  val className = getClass.getCanonicalName
  def live(host: String, serviceName: String): ULayer[Tracer] =
    ZLayer {
      for {
        tracer <- makeTracer(host, serviceName).orDie
      } yield tracer
    }

  def makeTracer(host: String, serviceName: String): Task[Tracer] =
    for {
      spanExporter   <- ZIO.attempt(OtlpGrpcSpanExporter.builder().setEndpoint(host).build())
      spanProcessor  <- ZIO.succeed(SimpleSpanProcessor.create(spanExporter))
      tracerProvider <-
        ZIO.attempt(
          SdkTracerProvider
            .builder()
            .setResource(Resource.create(Attributes.of(ResourceAttributes.SERVICE_NAME, serviceName)))
            .addSpanProcessor(spanProcessor)
            .build()
        )
      openTelemetry  <- ZIO.succeed(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build())
      tracer         <- ZIO.succeed(openTelemetry.getTracer(className))
    } yield tracer

}
