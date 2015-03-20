import Dependencies._

lazy val core = (project in file("core"))
  .settings(Commons.settings: _*)
  .settings(libraryDependencies ++= coreDependencies)
  .settings(resourceGenerators in Test <+= JarExtractorTests.createJar)