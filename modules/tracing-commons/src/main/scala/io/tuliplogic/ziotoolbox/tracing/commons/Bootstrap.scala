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

  private def logFormatWithBaggageAndTracing: LogFormat =
    LogFormat.make { (builder, _, _, _ , _, _, fiberRefs, _, _) =>
      fiberRefs.get(contextFiberRef).foreach { context =>
        val span = Span.fromContext(context)
//        val baggage = Baggage.fromContext(context) //no need, this is done by Baggage.logAnnotated
//        val baggageList = Chunk.fromIterable(baggage.asMap().asScala.map {
//          case (k, v) => k -> v.getValue
//        })

        builder.appendKeyValues(
          Chunk(
            "traceId" -> span.getSpanContext.getTraceId,
            "spanId" -> span.getSpanContext.getSpanId
          ) //++ baggageList
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
  val defaultBootstrap = Runtime.removeDefaultLoggers >>> SLF4J.slf4j(SLF4J.logFormatDefault + logFormatWithBaggageAndTracing)

  val tracingLayer: URLayer[Tracer, ContextStorage with Baggage with Tracing] =
    ZLayer.makeSome[Tracer, ContextStorage with Baggage with Tracing](
      fiberRefContextStorage,
      Baggage.logAnnotated,
      Tracing.live
    )
}
