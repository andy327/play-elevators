package actors

import akka.actor.{ Actor, ActorRef, ActorLogging, FSM, Props }
import scala.concurrent.duration._

object Lift {
  sealed trait State
  case object Idle extends State
  case object MovingUp extends State
  case object MovingDown extends State

  type RequestMap = Map[ServiceRequest, Set[ActorRef]]
  object Data { def unapply(data: Data) = Option(data.currentFloor, data.passengers) }
  sealed trait Data {
    def currentFloor: Int
    def passengers: Set[ActorRef]
  }
  object IdleData { def initial: IdleData = IdleData(1, Set()) }
  case class IdleData(currentFloor: Int, passengers: Set[ActorRef]) extends Data
  case class MovingData(currentFloor: Int, passengers: Set[ActorRef], requests: RequestMap) extends Data

  case object GoUpOne // sent to the engine
  case object GoDownOne // sent to the engine

  object ServiceRequest { def unapply(request: ServiceRequest) = Option(request.floor) }
  sealed trait ServiceRequest { def floor: Int }
  object RequestLift { def unapply(request: RequestLift) = Option(request.floor) }
  sealed trait RequestLift extends ServiceRequest

  case object GetOn // sent from passenger
  case object GetOff // sent from passenger
  case object PassengerAccepted // sent to passenger
  case object PassengerRejected // sent to passenger

  case class GoToFloor(floor: Int) extends ServiceRequest // sent from passenger inside lift
  case class RequestUpLift(floor: Int) extends RequestLift // sent from the dispatcher
  case class RequestDownLift(floor: Int) extends RequestLift // sent from the dispatcher

  case class RequestServed(request: ServiceRequest) // sent to sender (passengers/dispatcher)
  case class InvalidRequest(request: ServiceRequest, msg: String) // sent to request sender (passengers/dispatcher)
  case class Status(state: State, data: Data) // sent to listeners
}

/**
 * Services requests to move passengers up and down an elevator lift system. Receives requests from passengers inside
 * the lift as well as from the lift dispatcher system to go to specific floors. The Lift class reacts to requests by
 * messaging its listeners with either a refusal to service the request, or a success message when it has reached one
 * of its destinations.
 *
 * To facilitate movement, the Lift class contains an instance of an [[Engine]] that it communicates with by sending
 * requests to move up and down floors and receiving alerts when the lift has moved.
 *
 * Through extension of the [[akka.actor.FSM]] trait, a Lift can be in one of three states: idle, moving up, or moving
 * down, and will respond to requests appropriately given its current state. When all requests have been served, the
 * Lift will enter into the Idle state.
 */
class Lift(
  id: Long,
  millisDoorOpen: Int = 2000,
  millisPerFloor: Int = 1000,
  maxFloor: Int = 100,
  capacity: Int = 8
) extends Actor
  with ActorLogging
  with FSM[Lift.State, Lift.Data] {

  import Lift._
  import Engine._

  implicit val ec = context.dispatcher
  val scheduler = context.system.scheduler

  val engine = context.actorOf(Props(new Engine(self, millisPerFloor)), "Engine")

  startWith(Idle, IdleData.initial)

  when(Idle) {
    case Event(request @ ServiceRequest(floor), data @ IdleData(currentFloor, passengers))
      if (1 to maxFloor).contains(floor) =>
      request match {
        case GoToFloor(_) => log info(s"received $request from passenger ${sender.path.name}")
        case RequestLift(_) => log info(s"received $request from dispatcher")
      }
      if (floor == currentFloor) {
        sender ! RequestServed(request)
        stay
      } else if (floor > currentFloor) {
        log info(s"going up...")
        scheduler.scheduleOnce(millisDoorOpen.milliseconds, engine, GoUpOne)
        goto(MovingUp) using MovingData(currentFloor, passengers, Map(request -> Set(sender)))
      } else {
        log info(s"going down...")
        scheduler.scheduleOnce(millisDoorOpen.milliseconds, engine, GoDownOne)
        goto(MovingDown) using MovingData(currentFloor, passengers, Map(request -> Set(sender)))
      }

    case Event(GetOn, IdleData(currentFloor, passengers)) if passengers.size < capacity =>
      log info(s"passenger ${sender.path.name} boarded ${self.path.name}")
      sender ! PassengerAccepted
      goto(Idle) using IdleData(currentFloor, passengers + sender)

    case Event(GetOff, IdleData(currentFloor, passengers)) =>
      log info(s"passenger ${sender.path.name} disembarked ${self.path.name}")
      goto(Idle) using IdleData(currentFloor, passengers - sender)
  }

  when(MovingUp) {
    case Event(MovedUpOne, data @ MovingData(currentFloor, passengers, requests)) =>
      val nextFloor = currentFloor + 1
      log info(s"reached floor $nextFloor")
      if (requests.keys.exists(_.floor == nextFloor)) {
        log info(s"arrived at destination floor $nextFloor")
        val (servedRequests, remainingRequests) = requests.partition(_._1.floor == nextFloor)
        servedRequests foreach { case (request, requesters) =>
          requesters foreach { _ ! RequestServed(request) }
        }
        if (remainingRequests.isEmpty)
          goto(Idle) using IdleData(nextFloor, passengers)
        else if (remainingRequests.keys.exists(_.floor > nextFloor)) {
          log info(s"going up...")
          scheduler.scheduleOnce(millisDoorOpen.milliseconds, engine, GoUpOne)
          goto(MovingUp) using MovingData(nextFloor, passengers, remainingRequests)
        } else {
          log info(s"going down...")
          scheduler.scheduleOnce(millisDoorOpen.milliseconds, engine, GoDownOne)
          goto(MovingDown) using MovingData(nextFloor, passengers, remainingRequests)
        }
      } else {
        engine ! GoUpOne
        goto(MovingUp) using MovingData(nextFloor, passengers, requests)
      }

    case Event(request @ ServiceRequest(floor), MovingData(currentFloor, passengers, requests))
      if (1 to maxFloor).contains(floor) =>
      request match {
        case GoToFloor(_) => log info(s"received $request from passenger ${sender.path.name}")
        case RequestLift(_) => log info(s"received $request from dispatcher")
      }
      goto(MovingUp) using MovingData(currentFloor, passengers, addRequest(requests, request -> sender))

    case Event(GetOn, MovingData(currentFloor, passengers, requests)) if passengers.size < capacity =>
      log info(s"passenger ${sender.path.name} boarded ${self.path.name}")
      sender ! PassengerAccepted
      goto(MovingUp) using MovingData(currentFloor, passengers + sender, requests)

    case Event(GetOff, MovingData(currentFloor, passengers, requests)) =>
      log info(s"passenger ${sender.path.name} disembarked ${self.path.name}")
      goto(MovingUp) using MovingData(currentFloor, passengers - sender, requests)
  }

  when(MovingDown) {
    case Event(MovedDownOne, data @ MovingData(currentFloor, passengers, requests)) =>
      val nextFloor = currentFloor - 1
      log info(s"reached floor $nextFloor")
      if (requests.keys.exists(_.floor == nextFloor)) {
        log info(s"arrived at destination floor $nextFloor")
        val (servedRequests, remainingRequests) = requests.partition(_._1.floor == nextFloor)
        servedRequests foreach { case (request, requesters) =>
          requesters foreach { _ ! RequestServed(request) }
        }
        if (remainingRequests.isEmpty)
          goto(Idle) using IdleData(nextFloor, passengers)
        else if (remainingRequests.keys.exists(_.floor < nextFloor)) {
          log info(s"going down...")
          scheduler.scheduleOnce(millisDoorOpen.milliseconds, engine, GoDownOne)
          goto(MovingDown) using MovingData(nextFloor, passengers, remainingRequests)
        } else {
          log info(s"going up...")
          scheduler.scheduleOnce(millisDoorOpen.milliseconds, engine, GoUpOne)
          goto(MovingUp) using MovingData(nextFloor, passengers, remainingRequests)
        }
      } else {
        engine ! GoDownOne
        goto(MovingDown) using MovingData(nextFloor, passengers, requests)
      }

    case Event(request @ ServiceRequest(floor), MovingData(currentFloor, passengers, requests))
      if (1 to maxFloor).contains(floor) =>
      request match {
        case GoToFloor(_) => log info(s"received $request from passenger ${sender.path.name}")
        case RequestLift(_) => log info(s"received $request from dispatcher")
      }
      goto(MovingDown) using MovingData(currentFloor, passengers, addRequest(requests, request -> sender))

    case Event(GetOn, MovingData(currentFloor, passengers, requests)) =>
      log info(s"passenger ${sender.path.name} boarded ${self.path.name}")
      sender ! PassengerAccepted
      goto(MovingDown) using MovingData(currentFloor, passengers + sender, requests)

    case Event(GetOff, MovingData(currentFloor, passengers, requests)) =>
      log info(s"passenger ${sender.path.name} disembarked ${self.path.name}")
      goto(MovingDown) using MovingData(currentFloor, passengers - sender, requests)
  }

  onTransition {
    case _ -> nextState =>
      gossip(Status(nextState, nextStateData))
  }

  whenUnhandled {
    case Event(request @ ServiceRequest(floor), Data(_, _)) if !(1 to maxFloor).contains(floor) =>
      sender ! InvalidRequest(request,
        s"can't service $request: only floors from 1 to $maxFloor are accepted")
      stay

    case Event(GetOn, Data(currentFloor, passengers)) if passengers.size >= capacity =>
      log info(s"lift at capacity, rejecting passenger ${sender.path.name}...")
      sender ! PassengerRejected
      stay

    case Event(e, d) =>
      log info(s"received unhandled request $e in state $stateName/$d")
      stay
  }

  private def addRequest(requests: RequestMap, requestWithRef: (ServiceRequest, ActorRef)): RequestMap = {
    val (request, requester) = requestWithRef
    requests.updated(request, requests.getOrElse(request, Set()) + requester)
  }
}
