package dk.nscp.rest_server

import akka.util.Timeout
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer

import scala.io.StdIn

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json._


object Main extends App with JsonSupport {

  val host = "localhost"
  val port = 8080

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher
  implicit val timeout = Timeout(20.seconds)

  val route: Route = {
    concat(
      post {
        path("bet") { params =>
            onSuccess(GameDAO.bet(params.bet, params.guess)) {
                complete(StatusCodes.InternalServerError)
            }
          }
        }
      },
      post {
        path("check") { id =>
          onSuccess(GameDAO.check(id)) {
            case 0 => complete(s"""{"message": "No win"}""")
            case 1 => complete(s"""{"message": "Win"}""")
          }
        }
      }
    )
  }

  val bindingFuture = Http().bindAndHandle(route, host, port)
  println(s"\nServer running on $host:$port\nhit RETURN to terminate")
  StdIn.readLine()

  bindingFuture.flatMap(_.unbind())
  system.terminate()
}
