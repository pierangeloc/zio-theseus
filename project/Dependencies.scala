import sbt._

object Dependencies {
  object com {
    object olegpy {
      val `better-monadic-for` =
        "com.olegpy" %% "better-monadic-for" % "0.3.1"
    }
  }

  object org {
    object typelevel {
      val `kind-projector` =
        "org.typelevel" %% "kind-projector" % "0.13.2" cross CrossVersion.full
    }

  }


  val doobie = "org.tpolecat" %% "doobie-core" % "1.0.0-RC4"
  val doobieHikari = "org.tpolecat" %% "doobie-hikari" % "1.0.0-RC4"
  val postgres = "org.postgresql" % "postgresql" % "42.6.0"
  val allDoobie = Seq(doobie, doobieHikari, postgres)

  val zio = "dev.zio" %% "zio" % "2.0.13"
  val zioTelemetry = "dev.zio" %% "zio-opentelemetry" % "3.0.0-RC17"
  val zioInteropCats = "dev.zio" %% "zio-interop-cats" % "23.0.0.8"
  val zioLogging = "dev.zio" %% "zio-logging" % "2.1.13"
  val zioGrpc = "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-core" % "0.6.0"

  val zioTest = "dev.zio" %% "zio-test" % "2.0.18" % Test
  val zioTestSbt = "dev.zio" %% "zio-test-sbt" % "2.0.18" % Test
}
