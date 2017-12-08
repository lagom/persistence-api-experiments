package com.lightbend.lagom.account.model

import akka.Done
import com.lightbend.lagom.core.es.PersistentEntity
import com.lightbend.lagom.core.es.PersistentEntity.ReplyType



object Account extends PersistentEntity {

  type State = Account
  type Command = AccountCommand[_]
  type Event = AccountEvent


  case class Account(amount: Double) {

    def validWithdraw(withdrawAmount: Double): Boolean =
      amount - withdrawAmount >= 0

    val readOnlyCommandHandlers = {
      actions
        .onCommand[GetBalance.type] {
        case _ => ReplyWith(_.amount)
      }
        .onCommand[GetState.type] {
        case _ => ReplyWith.state
      }
    }

    val eventHandlers =
      actions.onEvent {
        case Deposited(txAmount) => copy(amount = amount +  txAmount)
        case Withdrawn(txAmount) => copy(amount = amount -  txAmount)
      }

    val withdrawCommandHandler =
      actions
        .onCommand[Withdraw] {
        case Withdraw(txAmount) if validWithdraw(txAmount) =>
          Effect
            .persist(Withdrawn(txAmount))
            .andThen { state =>
              println(s"Withdrawn $txAmount, current balance is ${state.amount}")
            }
        case _ =>
          Effect
            .reject("Insufficient balance.")
      }
  }
  private val depositCommandHandlers =
    actions
      .onCommand[Deposit] {
        case Deposit(amount) =>
          Effect
            .persist(Deposited(amount))
            .andThen { state =>
              println(s"Deposited $amount, current balance is ${state.amount}")
            }
            .replyWith(_.amount)
      }
      .rejectCommand[Withdraw]("Not a valid command")

  private val depositOnCreation =
    actions
      .onEvent {
        // creates the account after first deposit
        case Deposited(amount) => Account(amount)
      }


  override def behavior =
    Behavior
      .create(depositCommandHandlers and depositOnCreation)
      .update {
        case account =>
          account.readOnlyCommandHandlers and
            account.eventHandlers and
            depositCommandHandlers and
            account.withdrawCommandHandler
      }


  def behaviorWithInitialState =
    Behavior
      .initialState(Account(0.0))
      .update {
        case account =>
          account.readOnlyCommandHandlers and
            account.eventHandlers and
            depositCommandHandlers and
            account.withdrawCommandHandler
      }



  sealed trait AccountCommand[R] extends ReplyType[R]

  case class Deposit(amount: Double) extends AccountCommand[Double]

  case class Withdraw(amount: Double) extends AccountCommand[Done]

  case object GetBalance extends AccountCommand[Double]

  case object GetState extends AccountCommand[Account]


  sealed trait AccountEvent {
    def amount: Double
  }
  case class Deposited(amount: Double) extends AccountEvent

  case class Withdrawn(amount: Double) extends AccountEvent

}

