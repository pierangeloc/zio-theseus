package io.tuliplogic.ziotoolbox.tracing.example

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import zio.logging.LogFormat
import zio.{EnvironmentTag, FiberRef, Ref, Scope, UIO, ZIO, ZIOApp, ZIOAppArgs, ZLayer}

trait MyCtx {
  def get: UIO[String]
  def set(s: String): UIO[Unit]
}

object MyCtx {

  val real = ZLayer.fromZIO {
    for {
      ref <- Ref.make("")
    } yield new MyCtx {
      override def get: UIO[String] = ref.get

      override def set(s: String): UIO[Unit] = ref.set(s)
    }
  }

  val empty = new MyCtx {
    override def get: UIO[String] = ZIO.succeed("")

    override def set(s: String): UIO[Unit] = ZIO.unit
  }
}
object ZioStateMgmt extends ZIOApp {

  type Environment = MyCtx

  val bootstrap: ZLayer[ZIOAppArgs, Any, MyCtx] = MyCtx.real // ZLayer.succeed(MyCtx.empty)

  val environmentTag: EnvironmentTag[MyCtx] = EnvironmentTag[MyCtx]

//  override type Environment = MyCtx
  override def run: ZIO[MyCtx with ZIOAppArgs with Scope, Any, Any] = for {
    ctx    <- ZIO.service[MyCtx]
    before <- ctx.get
    _      <- ZIO.logInfo(s"ctx before: $before")
    _      <- ctx.set("[something has changed]")
    after  <- ctx.get
    _      <- ZIO.logInfo(s"ctx after: $after")
  } yield ()
}

object ZioTelemetryContextStorage {

  def tracingSpan(fiberRef: FiberRef[Context]): LogFormat =
    LogFormat.make { (builder, _, _, _, _, _, fiberRefs, _, _) =>
      fiberRefs
        .get(fiberRef)
        .foreach { context =>
          val span = Span.fromContext(context)
          builder.appendKeyValue("dd-span-id", span.getSpanContext.getSpanId)
          builder.appendKeyValue("dd-trace-id", span.getSpanContext.getTraceId)
        }
      ()
    }


}
