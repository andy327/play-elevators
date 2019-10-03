package controllers

import actors._
import actors.Dispatcher._
import actors.Passenger._
import scala.collection.mutable.{ Map => MMap }
import scala.concurrent.ExecutionContext

import play.api.mvc._
import play.api.libs.json.Json
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.Configuration
import akka.actor.{ ActorSystem, ActorRef, Props }
import akka.pattern.ask
import akka.util.Timeout
import java.util.concurrent.TimeoutException
import javax.inject._
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

@Singleton
class Application @Inject()(cc: ControllerComponents, config: Configuration) extends AbstractController(cc) {
  import JSONMappings._

  implicit val timeout: Timeout = 5.seconds
  implicit val system = ActorSystem("ElevatorSystem")

  val numLifts = config.get[Int]("dispatcher.num-lifts")
  val millisDoorOpen = config.get[Int]("dispatcher.door-open-time")
  val millisPerFloor = config.get[Int]("dispatcher.millis-per-floor")
  val maxFloor = config.get[Int]("dispatcher.max-floor")
  val capacity = config.get[Int]("dispatcher.lift-capacity")

  val dispatcher = system.actorOf(
    Props(new Dispatcher(numLifts, millisDoorOpen, millisPerFloor, maxFloor, capacity)), "dispatcher"
  )
  val passengers: MMap[String, ActorRef] = MMap()

  /**
   * Create an Action to add a passenger to interact with the lift system.
   */
  def addPassenger(name: String) = Action { implicit request: Request[AnyContent] =>
    if (passengers.contains(name))
      BadRequest(s"passenger $name already exists")
    else {
      passengers += (name -> system.actorOf(Props[Passenger], name))
      Created(s"added passenger $name")
    }
  }

  /**
   * Create an Action to move a passenger to the desired floor.
   */
  def movePassenger(name: String, floor: Int) = Action { implicit request: Request[AnyContent] =>
    if (passengers.contains(name)) {
      passengers(name) ! MoveToFloor(floor, dispatcher)
      Ok(s"moving $name to floor $floor")
    } else
      BadRequest(s"passenger $name doesn't exist")
  }

  /**
   * Create an Action to return the location of a passenger.
   */
  def location(name: String) = Action { implicit request: Request[AnyContent] =>
    if (passengers.contains(name)) {
      try {
        val fut: Future[LocationInfo] = (passengers(name) ? GetLocationInfo).mapTo[LocationInfo]
        val response = Await.result(fut, 2500.milliseconds).asInstanceOf[LocationInfo]
        Ok(Json.toJson(response))
      } catch {
        case e: TimeoutException => InternalServerError("Timeout exceeded")
        case e: Exception => InternalServerError("Unknown server error occured")
      }
    } else
      BadRequest(s"passenger $name doesn't exist")
  }

  /**
   * Create an Action to return the statuses of all the lifts in the elevator system.
   */
  def status = Action { implicit request: Request[AnyContent] =>
    try {
      val fut: Future[Statuses] = (dispatcher ? GetStatuses).mapTo[Statuses]
      val response = Await.result(fut, 2500.milliseconds).asInstanceOf[Statuses]
      Ok(Json.toJson(response))
    } catch {
      case e: TimeoutException => InternalServerError("Timeout exceeded")
      case e: Exception => InternalServerError("Unknown server error occured")
    }
  }
}
