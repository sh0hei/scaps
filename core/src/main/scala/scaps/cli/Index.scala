package scaps.cli

import java.io.File
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scaps.searchEngine.SearchEngine
import scaps.featureExtraction.ExtractionError
import scaps.featureExtraction.JarExtractor
import scaps.settings.Settings
import scaps.api.Module
import scaps.utils.Logging
import scalaz.std.list._
import scaps.featureExtraction.CompilerUtils

object Index extends App with Logging {
  val sourceJar = new File(args(0))

  val compiler = CompilerUtils.createCompiler(Nil)
  val extractor = new JarExtractor(compiler)

  val engine = SearchEngine(Settings.fromApplicationConf).get

  engine.resetIndexes().get

  val entities =
    ExtractionError.logErrors(extractor(sourceJar), logger.info(_))

  engine.indexEntities(Module.Unknown, entities).get
}
