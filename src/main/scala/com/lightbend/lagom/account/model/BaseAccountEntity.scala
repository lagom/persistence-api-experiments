package com.lightbend.lagom.account.model

import com.lightbend.lagom.core.persistence.effects.core.persistence.PersistentEntity

trait BaseAccountEntity extends PersistentEntity[AccountCommand[_], AccountEvent, Account] {

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
      TryHandler[Withdraw]
        .persistOne { // <- this Effect builder expects a Try[Event]
          case cmd =>
            account
              .validateWithdraw(cmd.amount) // <- this method returns a Try[Double]
              .map(WithdrawExecuted)
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