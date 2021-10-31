lazy val root = (project in file("."))
  .enablePlugins(PlayScala, LauncherJarPlugin)
  .settings(
    name := """play-scala-hello-world-tutorial""",
    organization := "com.example",
    version := "1.0-SNAPSHOT",
    scalaVersion := "2.12.6",
    libraryDependencies ++= Seq("com.dripower" %% "play-circe" % "2712.0","com.typesafe.play" %% "play" % "2.8.8", "org.scalaj" %% "scalaj-http" % "2.4.2", "org.scorexfoundation" %% "scrypto" % "2.1.10", "org.ergoplatform" %% "ergo-appkit" % "4.0.5", guice,
      "org.scalatestplus.play" %% "scalatestplus-play" % "5.0.0" % Test
    ),
    scalacOptions ++= Seq(
      "-feature",
      "-deprecation",
      "-Xfatal-warnings"
    )
  )
