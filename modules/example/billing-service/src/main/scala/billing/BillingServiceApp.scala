package billing

import io.tuliplogic.ziotoolbox.tracing.commons.Bootstrap
import org.apache.kafka.clients.consumer.ConsumerRecord
import zio.{Scope, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer}

object BillingServiceApp extends ZIOAppDefault {

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
    Bootstrap.defaultBootstrap

  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] = ???

 }
