package actors

import akka.actor.{ Actor, ActorRef, ActorLogging, Props }
import akka.routing.Listen
import scala.collection.mutable.{ Map => MMap }

object Dispatcher {
  case class LiftReady(lift: ActorRef)
  case object GetStatuses
  case object LiftStatus {
    def fromStatus(liftStatus: Lift.Status) = liftStatus match {
      case Lift.Status(state, data) => LiftStatus(state, data.currentFloor, data.passengers.size)
    }
  }
  case class LiftStatus(state: Lift.State, floor: Int, numPassengers: Int)
  case class Statuses(liftStatuses: Map[String, LiftStatus])
}

/**
  * Receives requests for lifts from people located on different floors and communicates with the appropriate [[Lift]]
  * instances to service the request. The Dispatcher also tracks the status of all the lifts it controls. When a Lift
  * arrives at the requested floor, the request is no longer tracked and a reference to the Lift is sent to the person
  * who issued the request.
  */
class Dispatcher(
  numLifts: Int,
  millisDoorOpen: Int = 2000,
  millisPerFloor: Int = 1000,
  maxFloor: Int = 100,
  capacity: Int = 8
) extends Actor with ActorLogging {
  import Dispatcher._
  import Lift._

  var lifts: MMap[ActorRef, Status] = MMap() ++
    (1 to numLifts).map(id =>
      context.actorOf(Props(new Lift(id, millisDoorOpen, millisPerFloor, maxFloor, capacity)), s"Lift$id") ->
      Status(Idle, IdleData.initial)
    ).toMap

  /** map of requesters to their requests that have been delegated to a lift */
  var servedRequests: MMap[ActorRef, RequestLift] = MMap.empty

  /** map of requesters to their requests that are awaiting handling by a lift */
  var pendingRequests: MMap[ActorRef, RequestLift] = MMap.empty

  override def preStart() = {
    lifts.keys foreach { _ ! Listen(self) }
  }

  /** Returns an Option containing a reference to the nearest available lift, if one exists. */
  def bestLiftForRequest(request: RequestLift): Option[ActorRef] = {
    val validLiftsAndDistances = request match {
      case RequestUpLift(floor) =>
        lifts.map { case (lift, Status(state, data)) => (lift, data.currentFloor, state) }.collect {
          case (lift, liftFloor, Idle) => (lift, math.abs(floor - liftFloor))
          case (lift, liftFloor, MovingUp) if liftFloor < floor => (lift, floor - liftFloor)
        }
      case RequestDownLift(floor) =>
        lifts.map { case (lift, Status(state, data)) => (lift, data.currentFloor, state) }.collect {
          case (lift, liftFloor, Idle) => (lift, math.abs(liftFloor - floor))
          case (lift, liftFloor, MovingDown) if liftFloor > floor => (lift, liftFloor - floor)
        }
    }
    // take the closest lift that can service the request, if it exists
    validLiftsAndDistances.minByOption(_._2).map(_._1)
  }

  def receive = {
    case request @ RequestLift(floor) =>
      log info(s"received request $request")
      bestLiftForRequest(request) match {
        case Some(lift) =>
          servedRequests += sender -> request
          log info(s"request $request sent to ${lift.path.name}")
          lift ! request
        case None =>
          pendingRequests += sender -> request
          log info(s"request $request marked pending")
      }

    case RequestServed(servedRequest: RequestLift) =>
      log info(s"completed request $servedRequest")
      val (completed, remaining) = servedRequests.partition { case (_, request) => request == servedRequest }
      servedRequests = remaining
      completed foreach { case (requester, request) =>
        log info(s"${sender.path.name} is ready for ${requester.path.name}")
        requester ! LiftReady(sender)
      }

    case InvalidRequest(rejectedRequest: RequestLift, _) =>
      log info(s"request $rejectedRequest was rejected, marked as pending...")
      val (rejected, remaining) = servedRequests.partition { case (_, request) => request == rejectedRequest }
      servedRequests = remaining
      pendingRequests ++= rejected

    case status @ Status(_, _) =>
      log debug(s"updating status for ${sender.path.name} to $status")
      lifts.update(sender, status)
      pendingRequests foreach { case (requester, request) =>
        log info(s"re-attempting to serve request $request for ${requester.path.name}")
        self.tell(request, requester)
      }
      pendingRequests.clear

    case GetStatuses =>
      sender ! Statuses(lifts.map { case (lift, status) => lift.path.name -> LiftStatus.fromStatus(status) }.toMap)
  }
}
