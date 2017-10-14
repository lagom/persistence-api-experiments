package com.lightbend.lagom.account.model

import com.lightbend.lagom.core.persistence.effects.core.persistence.PersistentEntity

trait BaseAccountEntity extends PersistentEntity[AccountCommand[_], AccountEvent, Account] {

  // on creation and later
  val depositCommandHandlers =
    onCommand {
      // this is directive builder that expects one single Event
      Handler[Deposit]
        .persistOne {
          case Deposit(amount) => DepositExecuted(amount)
        }
        .andThen { (evt, state) =>
          println(s"Deposit ${evt.amount}, current balance is ${state.amount}")
        }
        // reply with new balance
        .replyWith(_.amount)
    }

  def withdrawCommandHandlers(account: Account) =
    onCommand {
      TryHandler[Withdraw]
        .persistOne { // <- this Effect builder expects a Try[Event]
          case Withdraw(amount) =>
            account
              .validateWithdraw(amount) // <- this method returns a Try[Double]
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
  val readOnlyCommandHandlers =
    onCommand {
      ReadOnly[GetBalance.type].replyWith((_, st) => st.amount)
    }
      .onCommand {
        ReadOnly[GetState.type].replyWith((_, st) => st)
      }

  val atCreationEventsHandlers =
    onEvent {
      // at creation time, first DepositExecuted initialises the account
      case evt: DepositExecuted => Account(evt.amount)
    }

  def afterCreationEventsHandlers(account: Account) =
    onEvent {
      case evt: DepositExecuted => account.copy(amount = account.amount + evt.amount)
      case evt: WithdrawExecuted => account.copy(amount = account.amount - evt.amount)
    }


}
