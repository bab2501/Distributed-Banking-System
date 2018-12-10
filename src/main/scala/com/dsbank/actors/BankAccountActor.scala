package com.dsbank.actors

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ActorLogging, ActorRef, Stash}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.onSuccess
import akka.http.scaladsl.server.directives.RouteDirectives.complete
import akka.persistence._
import com.dsbank.Remote.MessageWithId
import akka.pattern.ask

import scala.concurrent.duration._
import akka.util.Timeout

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}

case class BankAccountState() {
  var active = false
  var balance: Float = 0
  var clock = new AtomicInteger(0)
  def update(event: BankAccountEvent): Unit = event match {
    case AccountCreated() => active = true
    case BalanceIncreased(amount) => balance += amount
    case BalanceDecreased(amount) => balance -= amount
  }
}

object BankAccountActor {
  var state = BankAccountState()

  trait Command

  final case class Create(clock: Int, accountNumber: String)
  final case class CreateAPI(accountNumber: String)

  final case class Withdraw(clock: Int, amount: Float)
  final case class WithdrawAPI(amount: Float)

  final case class Deposit(clock: Int, amount: Float)
  final case class DepositAPI(amount: Float)

  final case class Interest(clock: Int, constant: Float)
  final case class InterestAPI(constant: Float)

  final case class Transfer(clockWithdraw: Int, clockDeposit: Int, bankAccountCluster: ActorRef, accountNumberDestination: String, amount: Float)
  final case class TransferAPI(accountNumberDestination: String, amount: Float)

  final case class GetBalance(clock: Int)
  final case class GetBalanceAPI()

  trait OperationOutcome

  final case class OperationSuccess(result: String) extends OperationOutcome

  final case class OperationFailure(reason: String) extends OperationOutcome

}

trait BankAccountEvent
case class BalanceIncreased(amount: Float) extends BankAccountEvent
case class BalanceDecreased(amount: Float) extends BankAccountEvent
case class AccountCreated() extends BankAccountEvent

class BankAccountActor extends PersistentActor with ActorLogging {

  implicit val timeout: Timeout = Timeout(5.seconds)
  import BankAccountActor._

  override def persistenceId: String = self.path.name

  val receiveRecover: Receive = {
    case evt: BankAccountEvent => state.update(evt)
    case SnapshotOffer(_, snapshot: BankAccountState) => state = snapshot
  }

  val snapShotInterval = 5
  val receiveCommand: Receive = {
    case Create(clock, accountNumber) =>
      if(state.clock.get() != clock){
        stash()
      }
      else {
        persist(AccountCreated())(e => {
          state.update(e)
          if (lastSequenceNr % snapShotInterval == 0 && lastSequenceNr != 0)
            saveSnapshot(state)
          sender() ! OperationSuccess("Bank account created")
        })
        state.clock.incrementAndGet()
        unstashAll()
      }
    case GetBalance(clock) =>
      if(state.clock.get() != clock){
        stash()
      }
      else {
        if (!state.active) {
          sender() ! OperationFailure("Account doesn't exist")
        } else {
          sender() ! OperationSuccess(state.balance.toString)
        }
        state.clock.incrementAndGet()
        unstashAll()
      }
    case Withdraw(clock, amount) =>
      if(state.clock.get() != clock){
        stash()
      }
      else {
        if (state.active) {
          if (state.balance >= amount) {
            persist(BalanceDecreased(amount))(e => {
              state.update(e)
              if (lastSequenceNr % snapShotInterval == 0 && lastSequenceNr != 0)
                saveSnapshot(state)
            })
          }
        }
        state.clock.incrementAndGet()
        unstashAll()
      }

    case Deposit(clock, amount) =>
      if(state.clock.get() != clock){
        stash()
      }
      else{
        if (state.active) {
          persist(BalanceIncreased(amount))(e => {
            state.update(e)
            if (lastSequenceNr % snapShotInterval == 0 && lastSequenceNr != 0)
              saveSnapshot(state)
          })
        }
        state.clock.incrementAndGet()
        unstashAll()
      }

    case Interest(clock, constant) =>
      if(state.clock.get() != clock){
        stash()
      }
      else {
        if (state.active) {
          val bankEvent = BalanceIncreased(state.balance * constant)
          persist(bankEvent)(e => {
            state.update(e)
            if (lastSequenceNr % snapShotInterval == 0 && lastSequenceNr != 0)
              saveSnapshot(state)
          })
        }
        state.clock.incrementAndGet()
        unstashAll()
      }
    case Transfer(clockWithdraw, clockDeposit, bankAccountCluster, accountNumberDestination, amount) =>
      if(state.clock.get() != clockWithdraw){
        stash()
      }
      else{
        if(state.active) {
          if (amount <= state.balance) {
            persist(BalanceDecreased(amount))(e => {
              state.update(e)
              if (lastSequenceNr % snapShotInterval == 0 && lastSequenceNr != 0)
                saveSnapshot(state)
              bankAccountCluster ! MessageWithId(accountNumberDestination, Deposit(clockDeposit, amount))
              // What if 'accountNumberDestination' isn't created yet?
            })
          }
          else {
            sender() ! OperationFailure("Not enough funds")
          }
        }
        state.clock.incrementAndGet()
        unstashAll()
      }
  }
}