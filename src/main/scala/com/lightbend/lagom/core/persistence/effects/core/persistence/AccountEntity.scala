package com.lightbend.lagom.core.persistence.effects.core.persistence

import com.lightbend.lagom.core.persistence.effects.core.persistence.PersistentEntity.ReplyType

import scala.util.{ Failure, Success }


class AccountEntity extends PersistentEntity[AccountCommand[_], AccountEvent, Account] {

  /**
    * Behavior is a PartialFunction from Option[State] => Actions
    *
    * When there is no state (None) we provide the actions that will build the Entity
    * (equivalent to a constructor, but expressed as Command and Event Handlers)
    *
    * When there is a state (Some[State]) we provide the actions that are able to update the Entity.
    *
    * In the absence of a snapshot, the Option[State] == None and we read events starting from first offset.
    * This basically replays the event that is responsible for creating the model (the seed event).
    */
  override def behavior =
    Behavior
      .first { // None => Actions == () => Actions
        // first we define actions that can construct the Account
        // we go from None => Some[Account]
        depositCommandActions orElse atCreationEvents
      }
      .andThen { // Some[State] => Actions == State => Actions
        // here come the actions that are valid when Account
        // is already created
        //
        // IMS: I think it is unnecessary to make the argument of `andThen` a PartialFunction. If this branch
        // is equivalent to `State => Actions` then we could require a `Function[State, Actions]`, right?
        //
        //
        //     Behavior
        //        .first { depositCommandActions orElse atCreationEvents}
        //        .andThen { account =>
        //              readOnlyCommands orElse
        //              moreCommands orElse
        //                  ...
        //
        //
        case account =>
          readOnlyCommands orElse
            withdrawCommandActions(account) orElse
            depositCommandActions orElse
            afterCreationEvents(account)
      }

  // on creation and later
  val depositCommandActions: Actions =
    actionBuilder
      .onCommand {
        // this is directive builder that expects one single Event
        Handler[Deposit]
          .persistOne {
            case cmd => DepositExecuted(cmd.amount)
          }
          .andThen {  (evt, state) =>
            println(s"Deposit ${evt.amount}, current balance is ${state.amount}")
          }
          // reply with new balance
          .replyWith(_.amount)
      }

  def withdrawCommandActions(account: Account) =
    actionBuilder
      .onCommand {
        Handler[Withdraw]
          // IMS: I think we're causing a lot of extra effort here. I mean, because one case may throw an exception
          // we have to (1) call `attempt` and (2) wrap each result into Success or Failure. It's a necessary
          // evil, unfortunately :-(
          .attempt.persistOne { // <- this Effect builder expects a Try[Event]
            case cmd if account.amount - cmd.amount >= 0 => Success(WithdrawExecuted(cmd.amount))
            case cmd => Failure(new RuntimeException("Insufficient balance"))
          }
          // NOTE: we don't need reply because Withdraw replies with Done
          // and there is an implicit for it
      }


  // read-only commands are no different, it's just an effect without a command handler
  // behind the scenes, this is a regular Effect with a NoOps command handler.
  // It doesn't have callbacks neither, but do have a reply.
  val readOnlyCommands =
    actionBuilder
      .onCommand {
        ReadOnly[GetBalance.type].replyWith(_.amount)
      }
      .onCommand {
        // there is also an implicit for reply with State
        // but intellij gives an error hence the explicit call here
        ReadOnly[GetState.type].replyWith(identity)
      }

  val atCreationEvents: Actions =
    actionBuilder
      .onEvent {
        // at creation time, first DepositExecuted initialises the account
        case evt: DepositExecuted => Account(evt.amount)
      }

  def afterCreationEvents(account: Account) =
    actionBuilder
      .onEvent {
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