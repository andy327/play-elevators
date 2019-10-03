package actors

import akka.actor.{ ActorSystem, Props }
import akka.testkit.{ TestActorRef, TestFSMRef, TestKit }
import org.scalatest.{ WordSpecLike, Matchers, BeforeAndAfterAll }
import scala.util.Random
import scala.concurrent.duration._

class PassengerSpec extends TestKit(ActorSystem("PassengerSpec")) with WordSpecLike with Matchers with BeforeAndAfterAll {
  import Passenger._

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "Passenger" should {
    "ride a Dispatcher system to the same floor" in {
      val dispatcher = TestActorRef(Props(new Dispatcher(3, 100, 20, 60, 8)), "Dispatcher1")
      val passenger1 = TestFSMRef(new Passenger, "Passenger1")

      passenger1 ! MoveToFloor(1, dispatcher)

      awaitAssert(passenger1.stateData shouldBe IdleData(1))
    }

    "ride a Dispatcher system up to a target floor" in {
      val dispatcher = TestActorRef(Props(new Dispatcher(3, 100, 20, 60, 8)), "Dispatcher2")
      val passenger1 = TestFSMRef(new Passenger, "Passenger2")

      passenger1 ! MoveToFloor(10, dispatcher)

      // awaitAssert(passenger1.stateName shouldBe WaitingForLift) // sometimes the test won't catch this state in time!
      awaitAssert(passenger1.stateName shouldBe Traveling)
      awaitAssert(passenger1.stateName shouldBe Idle)
      awaitAssert(passenger1.stateData shouldBe IdleData(10))
    }

    "ride a Dispatcher system up and then down to a target floor" in {
      val dispatcher = TestActorRef(Props(new Dispatcher(3, 100, 20, 60, 8)), "Dispatcher3")
      val passenger1 = TestFSMRef(new Passenger, "Passenger3")

      passenger1 ! MoveToFloor(10, dispatcher)
      Thread.sleep(100) // wait till the lift is already going up
      passenger1 ! MoveToFloor(1, dispatcher)

      awaitAssert(passenger1.stateName shouldBe Traveling)
      awaitAssert(passenger1.stateName shouldBe Idle)
      awaitAssert(passenger1.stateData shouldBe IdleData(1))
    }

    "ride a Dispatcher system along with multiple passengers" in {
      val dispatcher = TestActorRef(Props(new Dispatcher(3, 100, 20, 60, 8)), "Dispatcher4")
      val passengers = Seq.fill(10)(TestFSMRef(new Passenger))
      val destinations = Seq.fill(10)(Random.nextInt(20) + 1)

      passengers foreach { _.setState(Idle, IdleData(currentFloor = Random.nextInt(20) + 1)) }

      (passengers zip destinations) foreach { case (passenger, destination) =>
        passenger ! MoveToFloor(destination, dispatcher)
      }

      (passengers zip destinations) foreach { case (passenger, destination) =>
        awaitAssert(passenger.stateData shouldBe IdleData(destination), 5000.milliseconds)
      }
    }

    "retry riding a Dispatcher system when an elevator is crowded" in {
      val dispatcher = TestActorRef(Props(new Dispatcher(1, 100, 20, 60, 8)), "Dispatcher5")
      val passengers = Seq.fill(10)(TestFSMRef(new Passenger))

      passengers foreach { _ ! MoveToFloor(10, dispatcher) }

      passengers foreach { passenger => awaitAssert(passenger.stateData shouldBe IdleData(10)) }
    }

    "get off a Dispatcher system that doesn't service the desired floor" in {
      val dispatcher = TestActorRef(Props(new Dispatcher(3, 100, 20, 60, 8)), "Dispatcher6")
      val passenger1 = TestFSMRef(new Passenger, "Passenger5")

      passenger1 ! MoveToFloor(999, dispatcher)

      awaitAssert(passenger1.stateData shouldBe IdleData(1))
    }
  }
}
