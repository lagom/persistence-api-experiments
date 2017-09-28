package com.lightbend.lagom.core.persistence.effects.core.persistence

import com.lightbend.lagom.core.persistence.effects.core.persistence.PersistentEntity.ReplyType

import scala.util.{Failure, Success}


class AccountEntity extends PersistentEntity[AccountCommand[_], AccountEvent, Account] {

  /**
    * Behavior is a PartialFunction from Option[State] => Handlers
    *
    * When there is no state (None) we provide the handlers that will build the Entity
    * (equivalent to a constructor, but expressed as Command and Event Handlers)
    *
    * When there is a state (Some[State]) we provide the handlers that are able to update the Entity.
    *
    * In the absence of a snapshot, Option[State] == None and we replay the events starting from first offset.
    * This basically replays the event that is responsible for creating the model (the seed event).
    */
  override def behavior =
    Behavior
      .first { // None => Handlers == () => Handlers
        // first we define actions that can construct the Account
        // we go from None => Some[Account]
        depositCommandHandlers and atCreationEventsHandlers
      }
      .andThen { // Some[State] => Handlers == State => Handlers
        // here come the actions that are valid when Account
        // is already created
        case account =>
          readOnlyCommandHandlers and
            withdrawCommandHandlers(account) and
            depositCommandHandlers and
            afterCreationEventsHandlers(account)
      }


  // on creation and later
  val depositCommandHandlers: CommandHandlers =
    onCommand {
      // this is directive builder that expects one single Event
      Handler[Deposit]
        .persistOne {
          case cmd => DepositExecuted(cmd.amount)
        }
        .andThen { (evt, state) =>
          println(s"Deposit ${evt.amount}, current balance is ${state.amount}")
        }
        // reply with new balance
        .replyWith(_.amount)
    }

  def withdrawCommandHandlers(account: Account): CommandHandlers =
    onCommand {
      Handler[Withdraw]
        .attempt.persistOne { // <- this Effect builder expects a Try[Event]
        case cmd if account.amount - cmd.amount >= 0 => Success(WithdrawExecuted(cmd.amount))
        case cmd => Failure(new RuntimeException("Insufficient balance"))
      }
      // NOTE: we don't need reply because Withdraw replies with Done
      // and there is an implicit for it
    }


  /**
    * read-only commands are no different, it's just an effect without a command handler
    * behind the scenes, this is a regular Effect with a NoOps command handler.
    * It doesn't have callbacks neither, but do have a reply.
    */
  val readOnlyCommandHandlers: CommandHandlers =
    onCommand {
      ReadOnly[GetBalance.type].replyWith(_.amount)
    }
    .onCommand {
      // there is also an implicit for reply with State
      // but intellij gives an error hence the explicit call here
      ReadOnly[GetState.type].replyWith(identity)
    }

  val atCreationEventsHandlers: EventHandlers =
    onEvent {
      // at creation time, first DepositExecuted initialises the account
      case evt: DepositExecuted => Account(evt.amount)
    }

  def afterCreationEventsHandlers(account: Account): EventHandlers =
    onEvent {
      case evt: DepositExecuted => account.copy(amount = account.amount + evt.amount)
      case evt: WithdrawExecuted => account.copy(amount = account.amount - evt.amount)
    }


}

case class Account(amount: Double)

sealed trait AccountCommand[R] extends ReplyType[R]

case class Deposit(amount: Double) extends AccountCommand[Double]

case class Withdraw(amount: Double) extends AccountCommand[Done]

case object GetBalance extends AccountCommand[Double]

case object GetState extends AccountCommand[Account]


sealed trait AccountEvent {
  def amount: Double
}

case class DepositExecuted(amount: Double) extends AccountEvent

case class WithdrawExecuted(amount: Double) extends AccountEvent