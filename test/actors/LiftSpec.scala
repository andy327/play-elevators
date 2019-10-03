package actors

import akka.actor.{ ActorSystem, Props }
import akka.testkit.{ TestActorRef, TestKit, TestProbe }
import org.scalatest.{ WordSpecLike, BeforeAndAfterAll }

class LiftSpec extends TestKit(ActorSystem("LiftSpec")) with WordSpecLike with BeforeAndAfterAll {
  import Lift._
  import Engine._

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "Lift" should {
    "service a GoToFloor request to go up" in {
      val lift = TestActorRef(Props(new Lift(1L, 100, 20, 60, 8)), "Lift1")

      val user1 = TestProbe()

      user1.send(lift, GoToFloor(10))

      user1.expectMsg(RequestServed(GoToFloor(10)))
    }

    "service several GoToFloor requests to go up multiple stops" in {
      val lift = TestActorRef(Props(new Lift(2L, 100, 20, 60, 8)), "Lift2")

      val user1 = TestProbe()
      val user2 = TestProbe()
      val user3 = TestProbe()

      user1.send(lift, GoToFloor(20))
      user2.send(lift, GoToFloor(5))
      user3.send(lift, GoToFloor(10))

      user1.expectMsg(RequestServed(GoToFloor(20)))
      user2.expectMsg(RequestServed(GoToFloor(5)))
      user3.expectMsg(RequestServed(GoToFloor(10)))
    }

    "service several GoToFloor requests to go up and down multiple stops" in {
      val lift = TestActorRef(Props(new Lift(3L, 100, 20, 60, 8)), "Lift3")

      val user1 = TestProbe()
      val user2 = TestProbe()
      val user3 = TestProbe()

      user1.send(lift, GoToFloor(5))
      Thread.sleep(200)
      user2.send(lift, GoToFloor(3))
      user3.send(lift, GoToFloor(1))

      user1.expectMsg(RequestServed(GoToFloor(5)))
      user2.expectMsg(RequestServed(GoToFloor(3)))
      user3.expectMsg(RequestServed(GoToFloor(1)))
    }

    "service a GoToFloor request to go down" in {
      val lift = TestActorRef(Props(new Lift(4L, 100, 20, 60, 8)), "Lift4")

      val user1 = TestProbe()
      val user2 = TestProbe()

      user1.send(lift, GoToFloor(5))
      Thread.sleep(100)
      user2.send(lift, GoToFloor(1))

      user1.expectMsg(RequestServed(GoToFloor(5)))
      user2.expectMsg(RequestServed(GoToFloor(1)))
    }

    "service a GoToFloor request to go to the same floor" in {
      val lift = TestActorRef(Props(new Lift(4L, 100, 20, 60, 8)), "Lift5")

      val user1 = TestProbe()

      user1.send(lift, GoToFloor(1))

      user1.expectMsg(RequestServed(GoToFloor(1)))
    }

    "service GoToFloor requests in the opposite direction of travel" in {
      val lift = TestActorRef(Props(new Lift(5L, 100, 20, 60, 8)), "Lift6")

      val user1 = TestProbe()

      user1.send(lift, GoToFloor(10))
      Thread.sleep(200)
      user1.send(lift, GoToFloor(1))
      user1.send(lift, GoToFloor(6))
      user1.send(lift, GoToFloor(12))

      user1.expectMsg(RequestServed(GoToFloor(6)))
      user1.expectMsg(RequestServed(GoToFloor(10)))
      user1.expectMsg(RequestServed(GoToFloor(12)))
      user1.expectMsg(RequestServed(GoToFloor(1)))
    }

    "service a RequestUpLift request" in {
      val lift = TestActorRef(Props(new Lift(5L, 100, 20, 60, 8)), "Lift7")

      val dispatcher = TestProbe()

      dispatcher.send(lift, RequestUpLift(5))

      dispatcher.expectMsg(RequestServed(RequestUpLift(5)))
    }

    "service several RequestUpLift requests" in {
      val lift = TestActorRef(Props(new Lift(5L, 100, 20, 60, 8)), "Lift8")

      val dispatcher = TestProbe()

      dispatcher.send(lift, RequestUpLift(4))
      dispatcher.send(lift, RequestUpLift(7))
      dispatcher.send(lift, RequestUpLift(10))

      dispatcher.expectMsg(RequestServed(RequestUpLift(4)))
      dispatcher.expectMsg(RequestServed(RequestUpLift(7)))
      dispatcher.expectMsg(RequestServed(RequestUpLift(10)))
    }

    "service a RequestUpLift for the same floor" in {
      val lift = TestActorRef(Props(new Lift(5L, 100, 20, 60, 8)), "Lift9")

      val dispatcher = TestProbe()

      dispatcher.send(lift, RequestUpLift(1))

      dispatcher.expectMsg(RequestServed(RequestUpLift(1)))
    }
  }
}
