package actors

import akka.actor.{ Actor, ActorRef, ActorLogging }
import scala.concurrent.duration._

object Engine {
  case object MovedUpOne
  case object MovedDownOne
}

/**
 * Handles requests from the [[Lift]] class to move up or down floors. Schedules return messages using the configured
 * speed to inform the owner that the engine has moved up or down a floor.
 */
class Engine(lift: ActorRef, millisPerFloor: Int = 1000) extends Actor with ActorLogging {
  import Engine._
  import Lift._

  implicit val ec = context.dispatcher
  val scheduler = context.system.scheduler

  def receive = {
    case GoUpOne =>
      scheduler.scheduleOnce(millisPerFloor.milliseconds, lift, MovedUpOne)
    case GoDownOne =>
      scheduler.scheduleOnce(millisPerFloor.milliseconds, lift, MovedDownOne)
  }
}
