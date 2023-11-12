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


  val doobieVersion = "1.0.0-RC4"
  val doobie = "org.tpolecat" %% "doobie-core" % doobieVersion
  val doobieHikari = "org.tpolecat" %% "doobie-hikari" % doobieVersion
  val postgres = "org.postgresql" % "postgresql" % "42.6.0"
  val flyway   = "org.flywaydb"        % "flyway-core" % "9.22.3"
  val allDoobie = Seq(doobie, doobieHikari, postgres, flyway)

  val sttp = Seq(
    "com.softwaremill.sttp.client3" %% "core" % "3.8.15",
    "com.softwaremill.sttp.client3" %% "circe" % "3.8.15",
    "com.softwaremill.sttp.client3" %% "zio" % "3.8.15"
  )

  val tapirVersion = "1.6.0"
  val tapir =  Seq(
    "com.softwaremill.sttp.tapir" %% "tapir-zio" % tapirVersion,
    "com.softwaremill.sttp.tapir" %% "tapir-core" % tapirVersion,
    "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % tapirVersion,
  )

  val allSttp = sttp ++ tapir

  val allGrpc = Seq(
    "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-core" % "0.6.0"
  )

  val allKafka = Seq(
    "dev.zio" %% "zio-kafka" % "2.4.2"
  )

  val zio = "dev.zio" %% "zio" % "2.0.18"
  val zioTelemetry = "dev.zio" %% "zio-opentelemetry" % "3.0.0-RC17"
  val zioInteropCats = "dev.zio" %% "zio-interop-cats" % "23.1.0.0"
  val zioLogging = "dev.zio" %% "zio-logging" % "2.1.14"
  val zioLoggingSlf4j = "dev.zio" %% "zio-logging-slf4j" % "2.1.14"
  val zioGrpc = "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-core" % "0.6.0"

  val zioTest = "dev.zio" %% "zio-test" % "2.0.18" % Test
  val zioTestSbt = "dev.zio" %% "zio-test-sbt" % "2.0.18" % Test

  val otelSdk = "io.opentelemetry" % "opentelemetry-sdk" % "1.29.0"
  val otelSemconv = "io.opentelemetry.semconv" % "opentelemetry-semconv" % "1.21.0-alpha"

  val otelJdbc = "io.opentelemetry.instrumentation" % "opentelemetry-jdbc" % "1.31.0-alpha"
  val otelGrpc = "io.opentelemetry.instrumentation" % "opentelemetry-grpc-1.6" % "1.31.0-alpha"

  val examplesDeps = Seq(
    zio,
    "dev.zio" %% "zio-kafka" % "2.4.2",
    "io.grpc" % "grpc-netty" % "1.50.1",
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
    zioGrpc,
    "com.softwaremill.sttp.tapir" %% "tapir-zio-http-server" % tapirVersion,
    "com.softwaremill.sttp.tapir" %% "tapir-sttp-client" % tapirVersion,
    "dev.zio" %% "zio-kafka" % "2.4.2",

    "io.jaegertracing" % "jaeger-core" % "1.8.0",
    "io.jaegertracing" % "jaeger-client" % "1.8.0",
    "io.jaegertracing" % "jaeger-zipkin" % "1.8.0",

    "dev.zio" %% "zio-logging-slf4j" % "2.1.13",
    "org.slf4j" % "jul-to-slf4j" % "1.7.36",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
    "ch.qos.logback" % "logback-classic" % "1.4.6",
    "net.logstash.logback" % "logstash-logback-encoder" % "7.2",
    "org.codehaus.janino" % "janino" % "3.1.7",
    "org.tpolecat" %% "doobie-postgres" % doobieVersion,
    "com.softwaremill.sttp.client3" %% "slf4j-backend" % "3.8.15",
    "com.github.loki4j" % "loki-logback-appender" % "1.4.2",
  )
}
