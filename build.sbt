ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "dev.alma"
ThisBuild / version      := "0.1.0-SNAPSHOT"

val CatsEffectVersion     = "3.5.7"
val Http4sVersion         = "0.23.28"
val CirceVersion          = "0.14.10"
val DoobieVersion         = "1.0.0-RC5"
val LogbackVersion        = "1.5.12"
val TypesafeConfig        = "1.4.3"
val PostgresVersion       = "42.7.4"
val MunitVersion          = "1.0.2"
val MunitCeVersion        = "2.0.0"
val TestcontainersVersion = "2.0.5"

lazy val root = (project in file("."))
  .settings(
    name := "todo-service",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Wunused:imports",
      "-Xmax-inlines:64"
    ),
    libraryDependencies ++= Seq(
      // effect system
      "org.typelevel"  %% "cats-effect"           % CatsEffectVersion,
      // repository layer
      "org.tpolecat"   %% "doobie-core"           % DoobieVersion,
      "org.tpolecat"   %% "doobie-hikari"         % DoobieVersion,
      "org.tpolecat"   %% "doobie-postgres"       % DoobieVersion,
      "org.postgresql"  % "postgresql"            % PostgresVersion,
      // http layer
      "org.http4s"     %% "http4s-ember-server"   % Http4sVersion,
      "org.http4s"     %% "http4s-dsl"            % Http4sVersion,
      "org.http4s"     %% "http4s-circe"          % Http4sVersion,
      "io.circe"       %% "circe-core"            % CirceVersion,
      "io.circe"       %% "circe-generic"         % CirceVersion,
      "io.circe"       %% "circe-parser"          % CirceVersion,
      // config + logging
      "com.typesafe"    % "config"                % TypesafeConfig,
      "ch.qos.logback"  % "logback-classic"       % LogbackVersion,
      // tests
      "org.scalameta"      %% "munit"                     % MunitVersion          % Test,
      "org.typelevel"      %% "munit-cats-effect"         % MunitCeVersion        % Test,
      "org.http4s"         %% "http4s-ember-client"       % Http4sVersion         % Test,
      "org.testcontainers"  % "testcontainers-postgresql" % TestcontainersVersion % Test
    ),
    Test / parallelExecution := false,
    run / fork := true,
    run / connectInput := true
  )
