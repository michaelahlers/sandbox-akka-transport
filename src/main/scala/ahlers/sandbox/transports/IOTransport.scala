package ahlers.sandbox.transports

import java.net.InetSocketAddress

import ahlers.sandbox.transports.IOTransport.IOTransportSettings
import akka.actor.{ ActorLogging, Address, ExtendedActorSystem, FSM, UnboundedStash }
import akka.io.{ IO, Tcp }
import akka.remote.transport.AssociationHandle.HandleEventListener
import akka.remote.transport.Transport.AssociationEventListener
import akka.remote.transport.{ AssociationHandle, Transport }
import akka.util.ByteString
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.{ Future, Promise }

/**
 * A [[Transport]] implemented atop [[http://doc.akka.io/docs/akka/current/scala/io.html Akka I/O]] ''as a learning exercise''.
 * @author <a href="michael@ahlers.consulting">Michael Ahlers</a>
 */
object IOTransport {

  case class IOTransportSettings(host: String, port: Int, maximumPayloadBytes: Int)
  object IOTransportSettings {
    def apply(configuration: Config): IOTransportSettings = {
      import configuration._
      IOTransportSettings(
        host = if (hasPath("host")) getString("host") else "localhost",
        port = getInt("port"),
        maximumPayloadBytes = 32000
      )
    }
  }

  case class IOAssociationHandle(localAddress: Address, remoteAddress: Address) extends AssociationHandle {
    override val readHandlerPromise: Promise[HandleEventListener] = Promise()
    override def write(payload: ByteString): Boolean = ???
    override def disassociate(): Unit = ???
  }

  object Server {

    sealed trait State
    case object Idle extends State
    case object Active extends State

    sealed trait WithBound
    case object NotBound extends WithBound
    case object IsBound extends WithBound

    sealed trait WithListener
    case object NoListener extends WithListener
    case class HasListener(listener: AssociationEventListener) extends WithListener

  }

  class Server(host: String, port: Int) extends FSM[Server.State, (Server.WithBound, Server.WithListener)] with UnboundedStash with ActorLogging {

    import Server._
    import Tcp._
    import context.system

    startWith(Idle, (NotBound, NoListener))

    when(Idle)(transform {

      case Event(Bound(local), (_, withListener)) =>
        log.info("Bound to {}.", local)
        stay using (IsBound -> withListener)

      case Event(listener: AssociationEventListener, (withBound, _)) =>
        log.info("Registered association event listener {}.", listener)
        stay using (withBound -> HasListener(listener))

      case Event(CommandFailed(_: Bind), _) =>
        log.error("Failed to bind.")
        context stop self
        stay

      case Event(Received(data), _) =>
        log.info("Stashing {} bytes from client.", data.size)
        sender ! Write(data)
        stay

    } using {
      case FSM.State(_, (IsBound, _: HasListener), _, _, _) =>
        goto(Active)
    })

    /* TODO: Once active, start passing messages around. */

    onTransition {
      case _ -> Idle => IO(Tcp) ! Bind(self, new InetSocketAddress(host, port))
      case _ -> Active => unstashAll()
    }

    initialize()
  }

}

class IOTransport(system: ExtendedActorSystem, settings: IOTransportSettings) extends Transport with LazyLogging {
  val log = logger

  log.info("Using {}.", settings)
  def this(system: ExtendedActorSystem, configuration: Config) = this(system, IOTransportSettings(configuration))

  import system.dispatcher

  override val schemeIdentifier: String = "io"
  override val maximumPayloadBytes: Int = settings.maximumPayloadBytes
  val localAddress = Address(s"akka.$schemeIdentifier", system.name, Some(settings.host), Some(settings.port))

  val listenerPromise: Promise[AssociationEventListener] = Promise()

  override def listen: Future[(Address, Promise[AssociationEventListener])] =
    Future.successful(localAddress -> listenerPromise)

  override def isResponsibleFor(address: Address): Boolean = address match {
    case Address(protocol, _, Some(_), Some(_)) if s"akka.$schemeIdentifier" == protocol => true
    case _ => false
  }

  override def associate(remoteAddress: Address): Future[AssociationHandle] = {
    log.debug("Associate with {}.", remoteAddress)
    Future {
      ???
    }
  }

  override def shutdown(): Future[Boolean] =
    Future.successful(true)

}
