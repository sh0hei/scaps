package scaps.webservice

import spray.routing.HttpService
import akka.actor.Actor
import akka.actor.ActorSystem
import spray.http.HttpEntity
import scalatags.Text
import spray.http.MediaTypes
import spray.http.HttpEntity
import scaps.webapi.ScapsApi
import akka.io.Tcp.Bound
import scaps.webservice.ui.Pages
import spray.http.Uri

class ScapsServiceActor(val apiImpl: Scaps) extends Actor with ScapsService {
  def actorRefFactory = context

  def receive = runRoute(route)
}

trait ScapsService extends HttpService {
  implicit val _ = actorRefFactory.dispatcher

  val apiImpl: ScapsApi

  def route =
    path("api" / Segments) { path =>
      post {
        extract(_.request.entity.asString) { e =>
          complete {
            Router.route[ScapsApi](apiImpl)(
              autowire.Core.Request(path, upickle.read[Map[String, String]](e)))
          }
        }
      }
    } ~
      pathSingleSlash {
        get {
          parameters('q, 'p.as[Int] ? 0, 'm.?) { (query, resultPage, moduleId) =>
            if (query.isEmpty())
              reject
            else
              complete {
                val enabledModuleId = moduleId.flatMap(m => if (m.isEmpty()) None else Some(m))
                val resultOffset = resultPage * ScapsApi.defaultPageSize

                for {
                  status <- apiImpl.getStatus()
                  result <- apiImpl.search(query, moduleId = enabledModuleId, offset = resultOffset)
                  page = HtmlPages.skeleton(status, enabledModuleId,
                    result.fold(HtmlPages.queryError(_), HtmlPages.results(resultPage, query, enabledModuleId, _)), query)
                } yield HttpEntity(MediaTypes.`text/html`, page.toString())
              }
          } ~
            complete {
              for {
                status <- apiImpl.getStatus()
                page = HtmlPages.skeleton(status, None, HtmlPages.main(status))
              } yield HttpEntity(MediaTypes.`text/html`, page.toString())
            }
        }
      } ~
      path("scaps.css") {
        get {
          complete {
            HttpEntity(MediaTypes.`text/css`, HtmlPages.ScapsStyle.styleSheetText)
          }
        }
      } ~
      get { getFromResourceDirectory("") }
}

object HtmlPages extends Pages(scalatags.Text) {
  def encodeUri(path: String, params: Map[String, Any]): String =
    (Uri(path) withQuery params.mapValues(_.toString)).toString()
}

object Router extends autowire.Server[String, upickle.Reader, upickle.Writer] {
  def read[Result: upickle.Reader](p: String) = upickle.read[Result](p)
  def write[Result: upickle.Writer](r: Result) = upickle.write(r)
}
