# play-elevators [![Build Status](https://travis-ci.com/andy327/play-elevators.svg?branch=master)](https://travis-ci.com/andy327/play-elevators)


## Table of contents
* [Overview](#overview)
* [Technologies](#technologies)
* [Getting Started](#getting-started)
* [Configuration](#configuration)
* [History](#history)
* [Design](#design)
* [License](#license)


## Overview
This project spins up a simple web server that allows the user to interact with a system of elevators.
The Play framework is used to define routes to turn incoming HTTP requests into actions that interact with an elevator dispatch system.
The elevator system is implemented using Akka actors, and passengers interact with the dispatcher system and the lifts through a series of messages.

<p align="center">
<img src="https://i.imgur.com/w7BEnPT.gif" alt="elevator HTTP requests">
</p>


## Technologies
* Play Framework 4.0.3
* Akka 2.5.23
* Scala 2.13.0

<img src="https://i.imgur.com/Gxs6ket.png" width="200" alt="play and akka">


## Getting Started
Please ensure you are running Java 8 or later.
Then clone the git repository, and run the Play application using SBT.

```
$ git clone https://github.com/andy327/play-elevators.git
$ cd play-elevators/
$ sbt
[play-elevators] $ run
```

That's it! The server is now running in local mode with the default elevator configuration.
Try opening another terminal session and adding passengers and sending them on their way through a series of HTTP requests (you can use `curl` to send requests, or [HTTPie](https://httpie.org/) if you'd like a more intuitive HTTP client):

```
$ http localhost:9000/add_passenger?name=bob
$ http localhost:9000/move?"name=bob&floor=15"
```

You can check on the location of your passengers or the status of the dispatch system itself using the `location` and `status` commands.

```
$ http localhost:9000/location?name=bob
$ http localhost:9000/status
```

On the backend, the server interacts with a system of Akka actors that represent the dispatch system, the lifts, the passengers, and more to properly send passengers to and from the given floors.
If you take a look at your server, you can see the log of messages telling us what's happening when these requests are being made.

<p align="center">
<img src="https://i.imgur.com/WmW543n.png" alt="elevator server logs">
</p>


## Configuration
If you tested the elevator system using the instructions above, you may have noticed that there were three elevators available, and each could take up to eight passengers anywhere from floor 1 to 100, at a rate of about one floor per second.
These and any other parameters can be configured in a [Typesafe config](https://github.com/typesafehub/config) file located in `conf/application.conf`:

```
dispatcher {
  # number of lifts available to the dispatcher
  num-lifts = 3

  # time in milliseconds to allow passengers to enter
  door-open-time = 2000

  # number of milliseconds lift takes to move one floor
  millis-per-floor = 1000

  # number of floors accessible to lifts
  max-floor = 100

  # number of passengers that will fit in one lift
  lift-capacity = 8
}
```

If you want to change the behavior of your elevator system, edit this file and restart the server, and the new elevator system will use these settings.
Create a lightning fast elevator system that launches passengers to their destinations at 20 milliseconds per floor, or set it to one slow elevator and send a subway train's worth of passengers to the hundredth floor while watching the server slowly serve each and every request.
Or go ahead and simulate the tedium of daily life by configuring the system to mirror your office building's speed and try taking a busy elevator system to your own floor.
_Exciting!_


## History
Elevators weren't always so complex.
Before the 1950s when electrical switches took over, an elevator was operated by a lift attendant standing inside.
The attendant would command the elevator to respond to calls by pulling a lever to control the lift's movement.
When automatic elevators first came into use, some of the simpler systems would shuttle passengers up and down the entirety of the building at scheduled intervals.
It was wasteful and slow; elevators would stop at floors where no one was waiting, and make pointless round-trips during off-peak hours.<sup>[[1]](https://www.popularmechanics.com/technology/infrastructure/a20986/the-hidden-science-of-elevators/)</sup>

By the 1960s, elevators began operating more similarly to the ones we see today, guided by the *elevator algorithm*.
It can be stated simply with the following two rules:

1. As long as there’s someone inside or ahead of the elevator who wants to go in the current direction, keep heading in that direction.
2. Once the elevator has exhausted the requests in its current direction, switch directions if there’s a request in the other direction.
Otherwise, stop and wait for a call.

(Incidentally, the elevator algorithm has also found an application in determining the motion of a hard disk's arm and head in servicing read and write requests!)
The algorithm is simple enough, and in most cases will resolve requests efficiently.
Larger buildings typically have banks of several elevators, and oftentimes certain elevators will service only parts of the building.
Some skyscrapers have more involved logic for servicing requests, such as [destination dispatch](https://en.wikipedia.org/wiki/Destination_dispatch), which groups passengers with similar destinations into the same elevators to minimize travel time.
We may see some more exotic solutions in the future, such as Thyssenkrup's [twin elevator system](https://www.wired.com/2016/05/thyssenkrup-twin-elevator), or elevators that can move passengers [both vertically and horizontally](https://canada.constructconnect.com/dcn/news/technology/2018/09/revolutionary-multi-elevator-system-headed-canada) along an intricate track system.

For the purposes of this project, elevators will respect the standard elevator algorithm, and will dispatch the nearest lift that is moving in the direction of the request.


## Design
The elevator system is built using Akka.
Akka emphasizes actor-based concurrency, where actors send and respond to messages from other actors, without using locks or blocking on return values.
Messages are processed one at a time by an actor, and all the actors work concurrently with each other.
There are four actor classes in this project, defined in the `actors` package:

1. The `Dispatcher` actor is the primary point of communications for Passengers making requests.
It has a series of child actors that represent the individual Lifts.
2. The `Lift` actor represents one elevator car and is forwarded requests from the Dispatcher, in addition to handling requests from Passengers inside.
3. The `Engine` actor controls the movement of a Lift, and is responsible for notifying Lifts when they have moved to a new floor.
4. The `Passenger` actor represents a person who interacts with the Dispatcher, as well as the individual Lift once they have entered inside of one.

The diagram belows shows the avenues of communication between the different actors.

<p align="center">
<img src="https://i.imgur.com/iMWCwfP.png" alt="actor messages">
</p>


### Message sequences
In designing the message sequences, it's helpful to take a look at a few use cases of the elevator system, in order to detail the messages that should get sent between the different actors.
For example, let's say a passenger takes an elevator ride from floor 1 to floor 2.
That might consist of the following steps:

1. Passenger sends a `RequestUpLift(1)` message to the Dispatcher
2. Dispatcher sends a `LiftReady` message to the Passenger
3. Passenger sends a `GetOn` message to the Lift
4. Lift sends a `PassengerAccepted` message to the Passenger
5. Passenger sends a `GoToFloor(2)` message to the Lift
6. Lift sends a `RequestServed` message to the Passenger
7. Passenger sends a `GetOff` message to the Lift

In step 1, the dispatcher receives a message stating that someone is on the first floor, awaiting a lift to take them up.
This corresponds to a person pressing the 'up' button at the lobby.
The dispatcher then has to decide which lift would best serve this request.
In between steps 1 and 2, the dispatcher is communicating with the best choice of lift and telling it to process the request by heading down to the first floor.
Once the lift has arrived at the first floor (or perhaps it's already there), the lift sends a `RequestServed` message to the dispatcher containing the original request to inform it that the request has been handled.
In step 2, the dispatcher sends the passenger a `LiftReady` message containing a reference to the lift, so that it may further communicate commands to the lift directly.

Steps 3 through 7 include a series of communications between the lift and the passenger.
First the passenger will send a `GetOn` message to the lift to attempt to board.
The lift will then need to verify that there is room for the passenger before allowing them to board, sending either a `PassengerAccepted` or `PassengerRejected` message.
In the case of the latter, the passenger has to re-attempt making a request with the dispatcher, similar to how in real life a person will let a full elevator car go on ahead, and request an elevator again once the elevator doors have closed.
If the passenger is accepted, they can proceed to send requests for specific floors to the lift, and await a `RequestServed` message back once they have arrived at their destination.
The passenger is then removed from the lift upon receipt of a `GetOff` message.

Let's also take a look at a request for an elevator at floor 1 while the nearest lift is at floor 2, this time from the perspective of the lift:

1. Dispatcher sends a `RequestUpLift(1)` message to the Lift
2. Lift sends a `GoDownOne` message to the Engine
3. Engine sends a `MovedDownOne` message to the Lift
4. Lift sends a `RequestServed` message to the Dispatcher

Here we see that the lift 'moves' between floors by receiving messages from the Engine.
The Engine actor is responsible for timing the elevation changes upon request to move up and down, and only it can send messages that the lift will use to change floors.


### State-dependent Behavior
We've seen how the actors communicate via messages, but how do they keep track of all their requests?
How do they know whether they're in the correct state to even respond to requests?
After all, a lift sitting idly on the ground floor should be able to respond to any request, whether it comes from an elevator request on another floor (the dispatcher) or a person standing inside it who wishes to go to another floor.
But a lift that's currently climbing its way up to the tenth floor while serving eight requests from other passengers (or perhaps one mischievous person who likes mashing buttons) might not be able to respond to such a request.
The behavior of a lift is dependent on its current action, or state, so the Lift actor mixes in the `akka.actor.FSM` trait, which gives the Lift class the ability to function as a finite state machine.

The finite state machine, or FSM, can be described as a set of relations of the form:

> State(S) x Event(E) -> Actions (A), State(S’)

which can be interpreted as meaning:

> If we are in state S and the event E occurs, we should perform the actions A and make a transition to the state S’.<sup>[[2]](https://doc.akka.io/docs/akka/current/fsm.html)</sup>

The events here are our messages that the Lift class receives, and the states are defined such that these events need to be handled differently depending on which state we are in.
The Lift class provides three different states:

```scala
sealed trait State
case object Idle extends State
case object MovingUp extends State
case object MovingDown extends State
```

When we are in the idle state, we should respond to a service request by immediately moving in the direction of the given floor, and as soon as we do so, should transition to a new state depending on which direction the lift is moving.
We can see how lifts should respond to messages while in the idle state by looking at the `when(Idle) { ... }` block in the Lift class.
Once we are traveling in a specific direction, we can continue to accept requests, but we can no longer always move in the direction of the request if it means changing the direction of movement.
After all, the elevator algorithm says that we must first exhaust the requests in the direction of movement before moving on to other requests.

Each `when()` block details how to respond to messages depending on the current state, as well as how we should transition to a new state (or stay in the same state) and alter the data we store.
The Lift class keeps track of its current floor, the references to passengers riding inside, and the requests that it is serving if applicable.
Arriving at a new floor causes the lift to examine its data to determine if any requests can be served, and whether it should continue with the direction of travel, reverse course, or go into an idle state.

Additionally, there is a `whenUnhandled { ... }` block for messages that are not dependent on state.
For example, when a `GetOn` message is received and the elevator is at capacity, it should always reject the passenger.
Also worth noting is that the Lift class issues `gossip` calls to listeners upon transition to a new state; the dispatcher and any passenger on board can receive notice of the lift moving to a new floor and update its own behavior and/or internal state accordingly.

In addition to the Lift class, the Passenger actor also mixes in the `FSM` trait, and responds to different responses depending on whether it is idle, waiting for a lift, or traveling between floors.

Take a look at the source code for each individual actor for more information on how they respond to messages.
For more clarity on how the actors respond to individual messages in situations of varying complexity, look for unit tests in the `test` directory.


### Interacting with elevators using Play
The elevator system is controlled by a single application controller that generates `Action` values.
HTTP requests that are received by the Play application are routed to this controller.
The controller can be found in `controllers/Application.scala`, and it keeps track of one `Dispatcher` to control the elevator system, and a map of passengers that can interact with the elevator system.
The abstraction of engines is hidden away from the end user.

The application routes that are supported are detailed in the `conf/routes` file:

```
GET     /add_passenger              controllers.Application.addPassenger(name: String)
GET     /move                       controllers.Application.movePassenger(name: String, floor: Int)
GET     /location                   controllers.Application.location(name: String)
GET     /status                     controllers.Application.status
```

There are four available routes that are converted into actions: adding a passenger, moving the passenger to a specific floor, querying for a passenger's current location, and requesting the status of all the lifts.

Adding and moving a passenger result in simple 200-level responses for success, or a 400 response for bad requests.
The location and status endpoints are for requesting information about the dispatcher or passengers, and these return a JSON response.
The `JSONMapping` object is responsible for defining macros to automatically map objects that we receive from the actor messages into JSON objects.
If you're a particularly web-savvy developer, these responses should be all you need to design a front-end for a user to to interact with the dispatcher system directly and to visualize the results.

Enjoy!


## License
(c) 2019 Andres Perez.
This project is licensed under the [MIT License](https://opensource.org/licenses/MIT)
