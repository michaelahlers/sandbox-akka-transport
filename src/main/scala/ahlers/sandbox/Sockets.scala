package ahlers.sandbox

import java.net.InetSocketAddress

import akka.actor.{ActorLogging, ActorRef, ActorSystem, FSM, Props, UnboundedStash}
import akka.io.{IO, Tcp}
import akka.pattern._
import akka.util.{ByteString, Timeout}

import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * A server echoes messages from various client. Learning exercise of [[http://doc.akka.io/docs/akka/current/scala/io.html Akka I/O]] for the purpose of implementing a trivial [[akka.remote.transport.Transport]].
 * @author <a href="michael@ahlers.consulting">Michael Ahlers</a>
 */
object Sockets extends App {

  implicit val to = Timeout(60 seconds)

  val system = ActorSystem()

  import Server.GetAddress
  import Tcp._
  import system.dispatcher

  val server = system.actorOf(Props[Server], "server")

  (server ? GetAddress).mapTo[InetSocketAddress] foreach { remote =>

    val client0 = system.actorOf(Props[Client], "client-zero")
    client0 ! Connect(remote)
    client0 ! ByteString("Four score and seven years ago our fathers brought forth on this continent, a new nation, conceived in Liberty, and dedicated to the proposition that all men are created equal.")
    client0 ! Close

    val client1 = system.actorOf(Props[Client], "client-one")
    client1 ! Connect(remote)
    client1 ! ByteString("The quick red fox jumps over the lazy brown dog.")
    client1 ! Close

  }

}

object Client {

  sealed trait State
  case object Idle extends State
  case object Active extends State

}

class Client extends FSM[Client.State, ActorRef] with UnboundedStash with ActorLogging {

  import Client._
  import Tcp._
  import context.system

  startWith(Idle, context.system.deadLetters)

  when(Idle) {

    case Event(connect: Connect, _) =>
      IO(Tcp) ! connect
      stay

    case Event(CommandFailed(connect:Connect), _) =>
      log.error("Couldn't connect to {}.", connect.remoteAddress)
      context stop self
      stay

    case Event(Connected(remote, local), _) =>
      log.info("Connected to {} from {}.", remote, local)
      sender ! Register(self)
      goto(Active) using sender

    case Event(data: ByteString, _) =>
      log.info("Stashing {} bytes (until connected).", data.size)
      stash()
      stay

    case Event(Close, _) =>
      log.info("Stashing {} command (until connected).", Close)
      stash()
      stay

  }

  when(Active) {

    case Event(data: ByteString, connection) =>
      log.info("Sending {} bytes to server.", data.size)
      connection ! Write(data)
      stay

    case Event(Received(data), _) =>
      log.info("Got {} bytes from server.", data.size)
      stay

    case Event(Close, connection) =>
      connection ! Close
      stay

    case Event(_: ConnectionClosed, _) =>
      log.info("Closed connection.")
      context stop self
      stay

  }

  onTransition {
    case _ -> Active => unstashAll()
  }

}

object Server {

  sealed trait State
  case object Idle extends State
  case object Active extends State

  sealed trait Data
  case object NoBinding extends Data
  case class Binding(address: InetSocketAddress) extends Data

  case object GetAddress

}

class Server extends FSM[Server.State, Server.Data] with UnboundedStash with ActorLogging {

  import Server._
  import Tcp._
  import context.system

  startWith(Idle, NoBinding)

  when(Idle) {

    case Event(Bound(local), _) =>
      log.info("Bound to {}.", local)
      goto(Active) using Binding(local)

    case Event(CommandFailed(_: Bind), _) =>
      log.error("Failed to bind.")
      context stop self
      stay using NoBinding

    case Event(GetAddress, _) =>
      stash()
      stay using NoBinding

  }

  when(Active) {

    case Event(Connected(remote, local), _) =>
      log.info("Connection from {} to {}.", remote, local)
      sender ! Register(self)
      stay

    case Event(GetAddress, Binding(local)) =>
      sender ! local
      stay

    case Event(Received(data), _) =>
      log.info("Got {} bytes from client.", data.size)
      sender ! Write(data)
      stay

    case Event(_:ConnectionClosed, _) =>
      log.info("A connection was closed.")
      stay

  }

  onTransition {
    case _ -> Idle => IO(Tcp) ! Bind(self, new InetSocketAddress("localhost", 0))
    case _ -> Active => unstashAll()
  }

  initialize()

}
