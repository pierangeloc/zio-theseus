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
    .aggregate(sttp)
    .aggregate(grpc)
    .aggregate(kafka)

lazy val `tracing-commons` =
  project
    .in(file("modules/tracing-commons"))
    .settings(commonSettings)
    .settings(
      libraryDependencies ++= Seq(zio, zioTelemetry)
    )

lazy val doobie =
  project
    .in(file("modules/doobie"))
    .settings(commonSettings)
    .settings(
      libraryDependencies ++= allDoobie ++ Seq(zio, zioInteropCats, zioTelemetry, otelJdbc)
    )

lazy val grpc =
  project
    .in(file("modules/grpc"))
    .settings(commonSettings)
    .settings(
      libraryDependencies ++= allGrpc ++ Seq(zio, zioTelemetry, otelGrpc, otelSdk)
    )

lazy val kafka =
  project
    .in(file("modules/kafka"))
    .settings(commonSettings)
    .settings(
      libraryDependencies ++= allKafka ++ Seq(zio, zioTelemetry, otelGrpc, otelSdk)
    )
    .dependsOn(`tracing-commons`)

lazy val sttp =
  project
    .in(file("modules/sttp"))
    .settings(commonSettings)
    .settings(
      libraryDependencies ++= allSttp ++ Seq(zio, zioInteropCats) ++ Seq(zioTelemetry)
    )

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


