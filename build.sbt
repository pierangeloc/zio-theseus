import Dependencies.*

ThisBuild / organization := "io.tuliplogic.toolbox"
ThisBuild / scalaVersion := "2.13.12"

lazy val `zio-toolbox` =
  project
    .in(file("."))
    .settings(name := "zio-toolbox")
    .settings(commonSettings)
    .settings(autoImportSettings)
    .settings(dependencies)
    .aggregate(`tracing-commons`)
    .aggregate(doobie)
    .aggregate(`tracing-doobie`)
    .aggregate(`tracing-sttp`)
    .aggregate(`tracing-grpc`)
    .aggregate(`tracing-kafka`)
    .aggregate(`simple-example`)
    .aggregate(`example-billing-service`)
    .aggregate(`example-charging-hub`)
    .aggregate(`example-charging-service`)

lazy val `tracing-commons` =
  project
    .in(file("modules/tracing-commons"))
    .settings(commonSettings)
    .settings(
      libraryDependencies ++= Seq(zio, zioTelemetry, otelSemconv, zioLogging, zioLoggingSlf4j, zioOptics,
        "io.opentelemetry" % "opentelemetry-exporter-otlp" % "1.29.0",
        "io.opentelemetry" % "opentelemetry-sdk" % "1.29.0",
      )
    )

lazy val doobie =
  project
    .in(file("modules/doobie"))
    .settings(commonSettings)
    .settings(
      libraryDependencies ++= allDoobie ++ Seq(zio, zioInteropCats, zioTelemetry, otelJdbc)
    )

lazy val `tracing-doobie` =
  project
    .in(file("modules/tracing-doobie"))
    .settings(commonSettings)
    .settings(
      libraryDependencies ++= Seq(zio, zioInteropCats, zioTelemetry, otelJdbc)
    )
    .dependsOn(doobie)

lazy val `tracing-grpc` =
  project
    .in(file("modules/tracing-grpc"))
    .settings(commonSettings)
    .settings(
      libraryDependencies ++= allGrpc ++ Seq(zio, zioTelemetry, otelGrpc, otelSdk)
    )
    .dependsOn(`tracing-commons`)

lazy val `tracing-kafka` =
  project
    .in(file("modules/tracing-kafka"))
    .settings(commonSettings)
    .settings(
      libraryDependencies ++= allKafka ++ Seq(zio, zioTelemetry, otelGrpc, otelSdk)
    )
    .dependsOn(`tracing-commons`)

lazy val `tracing-sttp` =
  project
    .in(file("modules/tracing-sttp"))
    .settings(commonSettings)
    .settings(
      libraryDependencies ++= allSttp ++ Seq(zio, zioInteropCats) ++ Seq(zioTelemetry)
    )
    .dependsOn(`tracing-commons`)

lazy val `simple-example` = project
  .in(file("modules/simple-example"))
  .settings(commonSettings)
  .settings(
    scalacOptions ~= { current =>
      current.filterNot(v => v.equals("-Xfatal-warnings"))
    },
    libraryDependencies ++= examplesDeps,
    Compile / PB.targets := Seq(
      scalapb.gen(grpc = true) -> (Compile / sourceManaged).value / "scalapb",
      scalapb.zio_grpc.ZioCodeGenerator -> (Compile / sourceManaged).value / "scalapb",
    )
  )
  .dependsOn(`tracing-grpc`, `tracing-kafka`, `tracing-sttp`, `tracing-doobie`, doobie)

lazy val `example-charging-service` = project
  .in(file("modules/example/charging-service"))
  .settings(commonSettings)
  .settings(
    scalacOptions ~= { current =>
      current.filterNot(v => v.equals("-Xfatal-warnings"))
    },
    libraryDependencies ++= examplesDeps,
    Compile / PB.targets := Seq(
      scalapb.gen(grpc = true) -> (Compile / sourceManaged).value / "scalapb",
      scalapb.zio_grpc.ZioCodeGenerator -> (Compile / sourceManaged).value / "scalapb",
    )
  )
  .dependsOn(`tracing-grpc`, `tracing-kafka`, `tracing-sttp`, `tracing-doobie`, doobie)

lazy val `example-charging-hub` = project
  .in(file("modules/example/charging-hub"))
  .settings(commonSettings)
  .settings(
    scalacOptions ~= { current =>
      current.filterNot(v => v.equals("-Xfatal-warnings"))
    },
    libraryDependencies ++= examplesDeps,
    Compile / PB.targets := Seq(
      scalapb.gen(grpc = true) -> (Compile / sourceManaged).value / "scalapb",
      scalapb.zio_grpc.ZioCodeGenerator -> (Compile / sourceManaged).value / "scalapb",
    )
  )
  .dependsOn(`tracing-grpc`, `tracing-kafka`, `tracing-sttp`, `tracing-doobie`, doobie)

lazy val `example-billing-service` = project
  .in(file("modules/example/billing-service"))
  .settings(commonSettings)
  .settings(
    scalacOptions ~= { current =>
      current.filterNot(v => v.equals("-Xfatal-warnings"))
    },
    libraryDependencies ++= examplesDeps,
    Compile / PB.targets := Seq(
      scalapb.gen(grpc = true) -> (Compile / sourceManaged).value / "scalapb",
      scalapb.zio_grpc.ZioCodeGenerator -> (Compile / sourceManaged).value / "scalapb",
    )
  )
  .dependsOn(`tracing-grpc`, `tracing-kafka`, `tracing-sttp`, `tracing-doobie`, doobie)



lazy val commonSettings = {
  lazy val commonCompilerPlugins = Seq(
    addCompilerPlugin(com.olegpy.`better-monadic-for`),
    addCompilerPlugin(org.typelevel.`kind-projector`),
  )

  lazy val commonScalacOptions = Seq(
    Compile / console / scalacOptions := {
      (Compile / console / scalacOptions)
        .value
        .filterNot(_.contains("wartremover"))
        .filterNot(Scalac.Lint.toSet)
        .filterNot(Scalac.FatalWarnings.toSet) :+ "-Wconf:any:silent"
    },
    Test / console / scalacOptions :=
      (Compile / console / scalacOptions).value,
  )

  lazy val otherCommonSettings = Seq(
    update / evictionWarningOptions := EvictionWarningOptions.empty
  )

  Seq(
    commonCompilerPlugins,
    commonScalacOptions,
    otherCommonSettings,
  ).reduceLeft(_ ++ _)
}

lazy val autoImportSettings = Seq(
  scalacOptions +=
    Seq(
      "java.lang",
      "scala",
      "scala.Predef",
      "scala.annotation",
      "scala.util.chaining",
    ).mkString(start = "-Yimports:", sep = ",", end = ""),
  Test / scalacOptions +=
    Seq(
      "derevo",
      "derevo.scalacheck",
      "org.scalacheck",
      "org.scalacheck.Prop",
    ).mkString(start = "-Yimports:", sep = ",", end = ""),
)

lazy val dependencies = Seq(

  libraryDependencies ++= Seq(
    zio,
    zioTelemetry,
    zioGrpc,
    zioInteropCats,
    zioLogging,
    zioTest,
    zioTestSbt,
  ),
)


