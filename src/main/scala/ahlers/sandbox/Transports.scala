package ahlers.sandbox

import akka.actor.{ Actor, ActorSystem, Props }
import com.typesafe.config.{ ConfigFactory, ConfigValueFactory }
import com.typesafe.scalalogging.LazyLogging

object Transports extends App with LazyLogging {

  /* It's remote; no reference is available. */
  ActorSystem("remote", ConfigFactory.load().withValue("akka.remote.netty.tcp.port", ConfigValueFactory.fromAnyRef(2552)))

  val system = ActorSystem("local", ConfigFactory.load().withValue("akka.remote.netty.tcp.port", ConfigValueFactory.fromAnyRef(2553)))

  val server = system.actorOf(Props {
    new Actor {
      override def receive: Receive = {
        case message =>
          logger.info(s"""Request: "$message" from "$sender".""")
          sender ! "event"
          context.system.terminate()
      }
    }
  }, "server")

  val client = system.actorOf(Props {
    new Actor {
      override def preStart(): Unit = server ! "command"
      override def receive: Receive = {
        case message =>
          logger.info(s"""Response: "$message" from "$sender".""")
          context.system.terminate()
      }
    }
  })

}
