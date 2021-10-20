organization := "org.hypo10use"

name := "quidgames"

version := "0.1"

scalaVersion := "2.10.3"

val stage = taskKey[Unit]("Stage task")

val Stage = config("stage")

stage := {
  (packageWar in Compile).value
  (update in Stage).value.allFiles.foreach { f =>
    if (f.getName.matches("webapp-runner-[0-9\\.]+.jar")) {
      println("copying " + f.getName)
      IO.copyFile(f, baseDirectory.value / "target" / "webapp-runner.jar")
    }
  }
}
