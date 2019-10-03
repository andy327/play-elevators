package actors

import akka.actor.{ ActorSystem, Props }
import akka.testkit.{ TestActorRef, TestKit, TestProbe }
import org.scalatest.{ WordSpecLike, BeforeAndAfterAll }

class DispatcherSpec extends TestKit(ActorSystem("DispatcherSpec")) with WordSpecLike with BeforeAndAfterAll {
  import Dispatcher._
  import Lift._

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "Dispatcher" should {
    "service multiple requests" in {
      val dispatcher = TestActorRef(Props(new Dispatcher(3, 100, 20, 60, 8)), "Dispatcher1")

      val user1 = TestProbe()
      val user2 = TestProbe()
      val user3 = TestProbe()

      user1.send(dispatcher, RequestUpLift(10))
      user2.send(dispatcher, RequestDownLift(5))
      user3.send(dispatcher, RequestUpLift(1))

      user1.expectMsgType[LiftReady]
      user2.expectMsgType[LiftReady]
      user3.expectMsgType[LiftReady]
    }

    "service a request when all lifts are unavailable" in {
      val dispatcher = TestActorRef(Props(new Dispatcher(2, 100, 20, 60, 8)), "Dispatcher2")

      val user1 = TestProbe()
      val user2 = TestProbe()
      val user3 = TestProbe()

      user1.send(dispatcher, RequestUpLift(10))
      Thread.sleep(100)
      user2.send(dispatcher, RequestDownLift(11))
      Thread.sleep(100)
      user3.send(dispatcher, RequestUpLift(1))

      user1.expectMsgType[LiftReady]
      user2.expectMsgType[LiftReady]
      user3.expectMsgType[LiftReady]
    }

    "handle any requests for a single-lift system" in {
      val dispatcher = TestActorRef(Props(new Dispatcher(1, 100, 20, 60, 8)), "Dispatcher3")

      val user1 = TestProbe()
      val user2 = TestProbe()
      val user3 = TestProbe()
      val user4 = TestProbe()
      val user5 = TestProbe()

      user1.send(dispatcher, RequestUpLift(7))
      user2.send(dispatcher, RequestDownLift(8))
      user3.send(dispatcher, RequestUpLift(1))
      user4.send(dispatcher, RequestUpLift(10))
      user5.send(dispatcher, RequestDownLift(10))

      user1.expectMsgType[LiftReady]
      user2.expectMsgType[LiftReady]
      user3.expectMsgType[LiftReady]
      user4.expectMsgType[LiftReady]
      user5.expectMsgType[LiftReady]
    }
  }
}
