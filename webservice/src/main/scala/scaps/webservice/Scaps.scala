package scaps.webservice

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.pattern.ask
import akka.util.Timeout
import scaps.webapi.IndexStatus
import scaps.webapi.ScapsApi
import scaps.webapi.SearchResult
import scaps.webservice.actors.SearchEngineProtocol
import scaps.webservice.actors.SearchEngineActor
import akka.actor.ActorRefFactory
import scalaz.\/

class Scaps(context: ActorRefFactory) extends ScapsApi {
  import SearchEngineProtocol._

  val searchEngine = context.actorOf(Props[SearchEngineActor], "searchEngine")

  implicit val _ = context.dispatcher
  implicit val timeout = Timeout(10.seconds)

  def index(sourceFile: String, classpath: Seq[String]): Unit = {
    searchEngine ! Index(sourceFile, classpath)
  }

  def getStatus(): Future[IndexStatus] =
    for {
      queue <- (searchEngine ? GetQueue).mapTo[List[String]]
    } yield IndexStatus(queue)

  def search(query: String): Future[Either[String, Seq[SearchResult]]] =
    for {
      result <- (searchEngine ? Search(query)).mapTo[String \/ Seq[SearchResult]]
    } yield result.toEither
}
