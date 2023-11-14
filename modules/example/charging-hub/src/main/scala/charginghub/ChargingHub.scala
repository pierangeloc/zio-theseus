package charginghub

import io.tuliplogic.ziotoolbox.tracing.commons.Bootstrap
import zio.{Scope, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer}

object ChargingHub extends ZIOAppDefault {
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
    Bootstrap.defaultBootstrap
  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] = ???
}
