package com.dsbank.routes

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.MethodDirectives.get
import akka.http.scaladsl.server.directives.MethodDirectives.post
import akka.http.scaladsl.server.directives.RouteDirectives.complete
import akka.http.scaladsl.server.directives.PathDirectives.path
import com.dsbank.JsonSupport
import com.dsbank.Remote.MessageWithId
import com.dsbank.actors.BankAccountActor._
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.duration._
import scala.concurrent.Future

trait AccountRoutes extends JsonSupport {

  implicit def system: ActorSystem

  def bankAccountActorsCluster: ActorRef


  implicit lazy val timeout = Timeout(5.seconds)

  lazy val accountRoutes: Route =
    pathPrefix("accounts") {
      post {
        entity(as[Create]) { create =>
          val accountCreated: Future[OperationOutcome] =
            (bankAccountActorsCluster ? MessageWithId(create.accountNumber, Create(create.accountNumber))).mapTo[OperationOutcome]

          onSuccess(accountCreated) {
            _ => complete(StatusCodes.NoContent)
          }
        }
      } ~
        pathPrefix(Segment) { accountNumber =>
          path("balance") {
            get {
              val balanceRetrieved: Future[OperationOutcome] =
                (bankAccountActorsCluster ? MessageWithId(accountNumber, GetBalance)).mapTo[OperationOutcome]

              onSuccess(balanceRetrieved) {
                case OperationSuccess(result) =>
                  complete(result)
                case OperationFailure(_) =>
                  complete(StatusCodes.NotFound)
              }
            }
          } ~
            path("withdraw") {
              post {
                entity(as[Withdraw]) { withdraw =>
                  val moneyWithdrawn: Future[OperationOutcome] =
                    (bankAccountActorsCluster ? MessageWithId(accountNumber, Withdraw(withdraw.amount))).mapTo[OperationOutcome]
                  onSuccess(moneyWithdrawn) {
                    case OperationSuccess(_) =>
                      complete(StatusCodes.NoContent)
                    case OperationFailure(_) =>
                      complete(StatusCodes.NotFound)
                  }
                }
              }
            } ~
            path("deposit") {
              post {
                entity(as[Deposit]) { deposit =>
                  val moneyDeposited: Future[OperationOutcome] =
                    (bankAccountActorsCluster ? MessageWithId(accountNumber, Deposit(deposit.amount))).mapTo[OperationOutcome]

                  onSuccess(moneyDeposited) {
                    case OperationSuccess(_) =>
                      complete(StatusCodes.NoContent)
                    case OperationFailure(_) =>
                      complete(StatusCodes.NotFound)
                  }
                }
              }
            } ~
            path("interest") {
              println("router interest")
              post {
                entity(as[Interest]) { interest =>
                  val moneyDeposited: Future[OperationOutcome] =
                    (bankAccountActorsCluster ? MessageWithId(accountNumber, Interest(interest.constant)))
                      .mapTo[OperationOutcome]
                  onSuccess(moneyDeposited) {
                    case OperationSuccess(_) =>
                      complete(StatusCodes.NoContent)
                    case OperationFailure(_) =>
                      complete(StatusCodes.NotFound)
                  }
                }
              }
            } ~
            path("transfer") {
              post {
                entity(as[TransferAPI]) { transfer =>
                  val moneyDeposited: Future[OperationOutcome] =
                    (bankAccountActorsCluster ? MessageWithId(
                      accountNumber,
                      Transfer(bankAccountActorsCluster, transfer.accountNumberDestination, transfer.amount)))
                      .mapTo[OperationOutcome]

                  onSuccess(moneyDeposited) {
                    case OperationSuccess(_) =>
                      complete(StatusCodes.NoContent)
                    case OperationFailure(_) =>
                      complete(StatusCodes.NotFound)
                  }
                }
              }
            }
        }
    }
}
