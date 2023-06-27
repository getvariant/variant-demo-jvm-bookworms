package urisman.bookworms

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, ExceptionHandler, RequestContext, Route, RouteResult}
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import com.typesafe.scalalogging.LazyLogging
import com.variant.client.{Connection, VariantClient}
import com.variant.share.httpc.HttpStatusCode
import urisman.bookworms.api.{Books, Copies, Root}
import urisman.bookworms.variant.Variant

import scala.concurrent.{ExecutionContext, Future}

class Routes(implicit ec: ExecutionContext) {

  import Routes._

  private val rootRoutes = pathEndOrSingleSlash {
    get {
      // GET / - Health page
      complete(Root.get())
    }
  }

  private val booksRoutes = pathPrefix("books") {
    concat(
      pathEnd {
        concat(
          get {
            onSuccess(Books.get)(resp => complete(resp))
          },
          //            post {
          //              entity(as[Book]) { user =>
          //                onSuccess(createUser(user)) { performed =>
          //                  complete((StatusCodes.Created, performed))
          //                }
          //              }
          //            }
        )
      },
      path(Segment) { bookId =>
        get {
          onSuccess(Books.get(bookId.toInt)) (resp => complete(resp))
        }
      }
    )
  }

  private val copiesRoutes = pathPrefix("copies") {
      concat(
        pathEndOrSingleSlash {
          put {
            entity(as[String]) {
              body =>
                onSuccess(withBodyAs[Copy](body)(Copies.update))(resp => complete(resp))
            }
          }
        }
        ,
        path(Segment) { copyId =>
          put {
            // Put hold on a book copy
            // Instrument experiment
            implicit ctx => action {
              val req = ctx.request
              Variant.targetForState("MyState") match {
                case Some(stateRequest) =>
                  // All went well and we have the state request.
                  println(stateRequest)

                case None =>
                  // Variant had a problem, default to control
                  println("None")
              }
              Copies.hold(copyId.toInt)
            }
          }
        }
      )
  }

  def routes: Route = {
    cors() {
      rootRoutes ~ booksRoutes ~ copiesRoutes
    }
  }
  //        //#users-get-delete
//        //#users-get-post
//        path(Segment) { name =>
//          concat(
//            get {
//              //#retrieve-user-info
//              rejectEmptyResponse {
//                onSuccess(getUser(name)) { response =>
//                  complete(response.maybeUser)
//                }
//              }
//              //#retrieve-user-info
//            },
//            delete {
//              //#users-delete-logic
//              onSuccess(deleteUser(name)) { performed =>
//                complete((StatusCodes.OK, performed))
//              }
//              //#users-delete-logic
//            })
//        })
//      //#users-get-delete
//    }
//  )
}

object Routes extends LazyLogging {

  import akka.http.scaladsl.model.StatusCodes._
  import scala.reflect.runtime.universe._
  import io.circe._
  import io.circe.parser._

  private def action(bloc: => Future[HttpResponse])(implicit ctx: RequestContext): Future[RouteResult] = {
    try {
      ctx.complete(bloc)
    } catch {
      case t: Throwable =>
        logger.error("Unhandled exception:", t)
        ctx.complete(
          HttpResponse(
            StatusCodes.BadRequest,
            entity = s"Something's rotten in the Kingdom of Denmark:\n${t.getMessage}"))
    }
  }

  private def withBodyAs[T](body: String)(f: T => Future[HttpResponse])
                             (implicit decoder: Decoder[T], tt: TypeTag[T]): Future[HttpResponse] = {

    parse(body) match {
      case Left(ex) =>
        Future.successful(
          HttpResponse(
            BadRequest,
            entity = s"Exception while parsing JSON in request: ${ex.getMessage()}")
        )
      case Right(json) =>
        json.as[T] match {
          case Left(ex) =>
            val msg = s"Unable to unmarshal request body to class ${tt.tpe.getClass.getName} "
            Future.successful(HttpResponse(BadRequest, entity = msg))
          case Right(t) =>
            f(t)
        }
    }
  }
}