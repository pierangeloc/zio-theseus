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
    .aggregate(doobie)
    .aggregate(sttp)

lazy val doobie =
  project
    .in(file("modules/doobie"))
    .settings(commonSettings)
    .settings(
      libraryDependencies ++= allDoobie ++ Seq(zio, zioInteropCats) ++ Seq(zioTelemetry, otelJdbc)
    )

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


