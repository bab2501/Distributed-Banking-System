package com.dsbank

import java.net.InetAddress

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}
import akka.actor._
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import com.dsbank.Remote.MessageWithId
import com.dsbank.actors.{BankAccountActor, ClockManagerActor}
import com.dsbank.routes.AccountRoutes

object QuickstartServer extends App with AccountRoutes {

  var runInDAS = false
  if(sys.env.get("RUN_IN_DAS").isDefined){
    runInDAS = true
  }

  var hostname = "localhost"
  if(runInDAS) {
    hostname = sys.env("THIS_IP")
    val akkaSeedIp = sys.env("AKKA_SEED_IP")
    val nodestr = s"akka.tcp://BankCluster@$akkaSeedIp:2552"
    println(s"This IP address=$hostname\nseedNode=$nodestr")
    System.setProperty("akka.cluster.seed-nodes.0", nodestr)
    System.setProperty("akka.remote.netty.tcp.hostname", hostname)
  }
  else{
    val port = sys.env("PORT")
    println(s"Try to listen at port $port")
    System.setProperty("akka.remote.netty.tcp.port", port)
  }

  implicit val system: ActorSystem = ActorSystem("BankCluster")

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContext = system.dispatcher

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case MessageWithId(id, msg) => (id, msg)
  }

  val numberOfShards = 100

  val extractShardId: ShardRegion.ExtractShardId = {
    case MessageWithId(id, msg) => (id.hashCode % numberOfShards).toString
  }

  val bankAccountActorsCluster: ActorRef = ClusterSharding(system).start(
    typeName = "BankAccount",
    entityProps = Props[BankAccountActor],
    settings = ClusterShardingSettings(system),
    extractEntityId = extractEntityId,
    extractShardId = extractShardId)

  val clockManagerActor: ActorRef = system.actorOf(ClockManagerActor.props, "clockManager")

  lazy val routes: Route = accountRoutes

  private val httpRunEnv = sys.env.get("HTTP")

  if(httpRunEnv.isDefined) {
    val serverBinding: Future[Http.ServerBinding] = Http().bindAndHandle(routes, hostname, 8061)
    serverBinding.onComplete {
      case Success(bound) =>
        println(s"Server online at http://${bound.localAddress.getHostString}:${bound.localAddress.getPort}/")
      case Failure(e) =>
        Console.err.println(s"Server could not start!")
        e.printStackTrace()
        system.terminate()
    }
  }

  Await.result(system.whenTerminated, Duration.Inf)
}
