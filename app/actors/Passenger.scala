package actors

import akka.actor.{ Actor, ActorRef, ActorLogging, FSM, Props }
import akka.actor.FSM.{ Transition, CurrentState }
import akka.routing.{ Listen, Deafen }
import scala.concurrent.duration._

object Passenger {
  sealed trait State
  case object Idle extends State
  case object WaitingForLift extends State
  case object Traveling extends State

  object Data { def unapply(data: Data) = Option(data.currentFloor) }
  sealed trait Data { def currentFloor: Int }
  case class IdleData(currentFloor: Int) extends Data
  case class WaitingData(currentFloor: Int, endFloor: Int, dispatcher: ActorRef) extends Data
  case class TravelingData(currentFloor: Int, endFloor: Int, dispatcher: ActorRef, lift: ActorRef) extends Data

  case class MoveToFloor(floor: Int, dispatcher: ActorRef)
  case object GetLocationInfo
  case class LocationInfo(state: State, floor: Int)
}

/**
 * A Passenger can accept requests to get to a particular floor using a given elevator [[Dispatcher]] system, and will
 * update its state with the current floor location until it reaches its destination, and returns to an idle state.
 */
class Passenger extends Actor with ActorLogging with FSM[Passenger.State, Passenger.Data] {
  import Passenger._

  startWith(Idle, IdleData(1))

  when(Idle) {
    case Event(MoveToFloor(floor, _), IdleData(currentFloor)) if currentFloor == floor =>
      stay

    case Event(MoveToFloor(floor, dispatcher), IdleData(currentFloor)) if currentFloor < floor =>
      dispatcher ! Lift.RequestUpLift(currentFloor)
      goto(WaitingForLift) using WaitingData(currentFloor, floor, dispatcher)

    case Event(MoveToFloor(floor, dispatcher), IdleData(currentFloor)) if currentFloor > floor =>
      dispatcher ! Lift.RequestDownLift(currentFloor)
      goto(WaitingForLift) using WaitingData(currentFloor, floor, dispatcher)
  }

  when(WaitingForLift) {
    case Event(MoveToFloor(floor, _), WaitingData(currentFloor, _, _)) if currentFloor == floor =>
      goto(Idle) using IdleData(currentFloor)

    case Event(MoveToFloor(floor, _), WaitingData(_, endFloor, _)) if endFloor == floor =>
      stay

    case Event(MoveToFloor(floor, dispatcher), WaitingData(currentFloor, endFloor, _))
      if floor > currentFloor && endFloor > currentFloor =>
      stay using WaitingData(currentFloor, floor, dispatcher)

    case Event(MoveToFloor(floor, dispatcher), WaitingData(currentFloor, endFloor, _))
      if floor < currentFloor && endFloor < currentFloor =>
      stay using WaitingData(currentFloor, floor, dispatcher)

    case Event(MoveToFloor(floor, dispatcher), WaitingData(currentFloor, _, _))
      if currentFloor < floor =>
      dispatcher ! Lift.RequestUpLift(currentFloor)
      stay using WaitingData(currentFloor, floor, dispatcher)

    case Event(MoveToFloor(floor, dispatcher), WaitingData(currentFloor, _, _))
      if currentFloor > floor =>
      dispatcher ! Lift.RequestDownLift(currentFloor)
      stay using WaitingData(currentFloor, floor, dispatcher)

    case Event(Dispatcher.LiftReady(lift), WaitingData(currentFloor, endFloor, dispatcher)) =>
      lift ! Lift.GetOn
      lift ! Listen(self)
      goto(Traveling) using TravelingData(currentFloor, endFloor, dispatcher, lift)
  }

  when(Traveling) {
    case Event(MoveToFloor(floor, _), TravelingData(_, endFloor, _, _)) if endFloor == floor =>
      stay

    case Event(MoveToFloor(floor, dispatcher), TravelingData(currentFloor, _, _, lift)) =>
      lift ! Lift.GoToFloor(floor)
      stay using TravelingData(currentFloor, floor, dispatcher, lift)

    case Event(Lift.PassengerAccepted, TravelingData(currentFloor, endFloor, dispatcher, lift)) =>
      log info(s"riding ${lift.path.name}")
      lift ! Lift.GoToFloor(endFloor)
      stay

    case Event(Lift.PassengerRejected, TravelingData(currentFloor, endFloor, dispatcher, lift)) =>
      log info(s"rejected by ${lift.path.name}, retrying...")
      self ! MoveToFloor(endFloor, dispatcher)
      goto(Idle) using IdleData(currentFloor)

    case Event(Lift.RequestServed(request), TravelingData(_, endFloor, _, lift)) =>
      if (request.floor == endFloor) {
        log info(s"arrived at destination on floor ${request.floor}")
        lift ! Lift.GetOff
        goto(Idle) using IdleData(endFloor)
      } else // served an outdated request
        stay

    case Event(Lift.InvalidRequest(_, msg), TravelingData(currentFloor, _, _, lift)) =>
      log info(s"$msg")
      log info(s"getting off lift ${lift.path.name}")
      lift ! Deafen(self)
      lift ! Lift.GetOff // no sense riding this elevator system anymore with no destination
      goto(Idle) using IdleData(currentFloor)

    case Event(Lift.Status(_, data), TravelingData(_, endFloor, dispatcher, lift)) =>
      stay using TravelingData(data.currentFloor, endFloor, dispatcher, lift)
  }

  whenUnhandled {
    case Event(GetLocationInfo, Data(currentFloor)) =>
      sender ! LocationInfo(stateName, currentFloor)
      stay

    case Event(Dispatcher.LiftReady(_), Data(_)) =>
      stay // we're no longer waiting for the lift, don't follow through with this request

    case Event(Lift.RequestServed(_), Data(_)) =>
      stay // we're no longer waiting for the lift, don't follow through with this request

    case Event(Transition(_, _, _), Data(_)) =>
      stay

    case Event(CurrentState(_, _), Data(_)) =>
      stay

    case Event(Lift.Status(_, _), Data(_)) =>
      stay // we're no longer interested in updates about the lift when we've gotten off

    case Event(e, d) =>
      log info(s"received unhandled request $e in state $stateName/$d")
      stay
  }
}
