package controllers

import actors._

import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

// automate the JSON mapping of useful case classes
object JSONMappings {
  implicit val idleLiftFormat = OFormat[Lift.Idle.type](
    Reads[Lift.Idle.type] {
      case JsObject(_) => JsSuccess(Lift.Idle)
      case _ => JsError("Empty object expected")
    },
    OWrites[Lift.Idle.type] { _ => Json.obj() })

  implicit val movingUpFormat = OFormat[Lift.MovingUp.type](
    Reads[Lift.MovingUp.type] {
      case JsObject(_) => JsSuccess(Lift.MovingUp)
      case _ => JsError("Empty object expected")
    },
    OWrites[Lift.MovingUp.type] { _ => Json.obj() })

  implicit val movingDownFormat = OFormat[Lift.MovingDown.type](
    Reads[Lift.MovingDown.type] {
      case JsObject(_) => JsSuccess(Lift.MovingDown)
      case _ => JsError("Empty object expected")
    },
    OWrites[Lift.MovingDown.type] { _ => Json.obj() })

  implicit val liftStateFormat: OFormat[Lift.State] = Json.format[Lift.State]

  implicit val liftStatusFormat: OFormat[Dispatcher.LiftStatus] = Json.format[Dispatcher.LiftStatus]
  implicit val statusesWrites: Writes[Dispatcher.Statuses] = Json.writes[Dispatcher.Statuses]

  implicit val idlePassengerFormat = OFormat[Passenger.Idle.type](
    Reads[Passenger.Idle.type] {
      case JsObject(_) => JsSuccess(Passenger.Idle)
      case _ => JsError("Empty object expected")
    },
    OWrites[Passenger.Idle.type] { _ => Json.obj() })

  implicit val waitingFormat = OFormat[Passenger.WaitingForLift.type](
    Reads[Passenger.WaitingForLift.type] {
      case JsObject(_) => JsSuccess(Passenger.WaitingForLift)
      case _ => JsError("Empty object expected")
    },
    OWrites[Passenger.WaitingForLift.type] { _ => Json.obj() })

  implicit val travelingFormat = OFormat[Passenger.Traveling.type](
    Reads[Passenger.Traveling.type] {
      case JsObject(_) => JsSuccess(Passenger.Traveling)
      case _ => JsError("Empty object expected")
    },
    OWrites[Passenger.Traveling.type] { _ => Json.obj() })

  implicit val passengerStateFormat: OFormat[Passenger.State] = Json.format[Passenger.State]

  implicit val locationInfoWrites: Writes[Passenger.LocationInfo] = Json.format[Passenger.LocationInfo]
}
