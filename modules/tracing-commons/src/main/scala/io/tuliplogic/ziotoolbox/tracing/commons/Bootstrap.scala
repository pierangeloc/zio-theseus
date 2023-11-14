package io.tuliplogic.ziotoolbox.tracing.commons

import io.opentelemetry.api.trace.{Span, Tracer}
import io.opentelemetry.context.Context
import zio.logging.LogFormat
import zio.logging.backend.SLF4J
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{Chunk, FiberRef, Runtime, Scope, Trace, UIO, ULayer, URLayer, Unsafe, ZIO, ZLayer}

import scala.jdk.CollectionConverters._
object Bootstrap {

  private val contextFiberRef: FiberRef[Context] = Unsafe.unsafe {implicit unsafe =>
    FiberRef.unsafe.make(Context.root())
  }

  private def fromFiberRef(fr: FiberRef[Context]): ContextStorage = new ContextStorage {
    override def get(implicit trace: Trace): UIO[Context] =
      fr.get

    override def set(context: Context)(implicit trace: Trace): UIO[Unit] =
      fr.set(context)

    override def getAndSet(context: Context)(implicit trace: Trace): UIO[Context] =
      fr.getAndSet(context)

    override def updateAndGet(f: Context => Context)(implicit trace: Trace): UIO[Context] =
      fr.updateAndGet(f )

    override def locally[R, E, A](context: Context)(zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      fr.locally(context)(zio)

    override def locallyScoped(context: Context)(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
      fr.locallyScoped(context)
  }

  trait ExtraAnnotationsExtractor {
    def extractExtraAnnotations(span: Span): Chunk[(String, String)]
  }
  object ExtraAnnotationsExtractor {
    case object Noop extends ExtraAnnotationsExtractor {
      override def extractExtraAnnotations(span: Span): Chunk[(String, String)] = Chunk.empty
    }

    case object Datadog extends ExtraAnnotationsExtractor {
      val dd_span_id = "dd.span_id"
      val dd_trace_id = "dd.trace_id"

      def toDDSpanId(spanId: String): String = {
        val spanIdHex = spanId
        val ddSpanId = java.lang.Long.parseUnsignedLong(spanIdHex, 16)
        java.lang.Long.toUnsignedString(ddSpanId)
      }

      def toDDTraceId(traceId: String): String = {
        val traceIdHex = traceId.drop(16)
        val ddTraceId = java.lang.Long.parseUnsignedLong(traceIdHex, 16)
        java.lang.Long.toUnsignedString(ddTraceId)
      }

      override def extractExtraAnnotations(span: Span): Chunk[(String, String)] =
        Chunk(dd_span_id -> toDDSpanId(span.getSpanContext.getSpanId), dd_trace_id -> toDDTraceId(span.getSpanContext.getTraceId))
    }
  }

  private def logFormatWithBaggageAndTracing(extraAnnotationsExtractor: ExtraAnnotationsExtractor = ExtraAnnotationsExtractor.Datadog): LogFormat =
    LogFormat.make { (builder, _, _, _ , _, _, fiberRefs, _, _) =>
      fiberRefs.get(contextFiberRef).foreach { context =>
        val span = Span.fromContext(context)
        builder.appendKeyValues(
          Chunk(
            "traceId" -> span.getSpanContext.getTraceId,
            "spanId" -> span.getSpanContext.getSpanId
          ) ++ extraAnnotationsExtractor.extractExtraAnnotations(span)
        )
      }
  }

  /**
   *  Wire this context storage into your application layers to ensure tracing information is propagated through the logs
   */
  val fiberRefContextStorage = ZLayer.succeed(fromFiberRef(contextFiberRef))

  /**
   * Use this as bootstrap layer to get tracing and logging matching
   */
  val defaultBootstrap = Runtime.removeDefaultLoggers >>> SLF4J.slf4j(SLF4J.logFormatDefault + logFormatWithBaggageAndTracing())

  val tracingLayer: URLayer[Tracer, ContextStorage with Baggage with Tracing] =
    ZLayer.makeSome[Tracer, ContextStorage with Baggage with Tracing](
      fiberRefContextStorage,
      Baggage.logAnnotated,
      Tracing.live
    )
}
