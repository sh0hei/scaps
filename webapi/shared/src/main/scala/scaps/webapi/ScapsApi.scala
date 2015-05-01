package scaps.webapi

import scala.concurrent.Future

case class IndexStatus(workQueue: Seq[Module], indexedModules: Seq[Module])

/**
 * The main API exposed to search engine users.
 *
 * All exposed methods are considered safe and should not lead to erroneous behavior
 * when missused.
 */
trait ScapsApi extends CommonApi {
  def search(
    query: String,
    moduleId: Option[String] = None,
    noResults: Int = ScapsApi.defaultPageSize,
    offset: Int = 0): Future[Either[String, Seq[TermEntity]]]

  def assessPositivley(query: String, moduleId: Option[String], resultNo: Int, termSignature: String): Unit
}

object ScapsApi {
  val apiPath = "api"

  val defaultPageSize = 10
}

/**
 * A control API which access may be limited to local users.
 *
 * The exposed methods may destroy the index or will take a long time to process.
 */
trait ScapsControlApi extends CommonApi {
  def index(module: Module, artifactPath: String, classpath: Seq[String], forceReindex: Boolean): Unit

  /**
   * Reinitializes the search engine with an empty index.
   */
  def resetIndexes(): Unit
}

object ScapsControlApi {
  val apiPath = "api"
}

/**
 * Methods exposed by both APIs.
 */
trait CommonApi {
  def getStatus(): Future[IndexStatus]
}
