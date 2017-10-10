package com.lightbend.lagom.account.model

import com.lightbend.lagom.core.persistence.effects.core.persistence.Done
import com.lightbend.lagom.core.persistence.effects.core.persistence.PersistentEntity.ReplyType

import scala.util.{Failure, Success, Try}


case class Account(amount: Double) {

  def validateWithdraw(withdrawAmount: Double): Try[Double] = {
    if (amount - withdrawAmount >= 0)
      Success(withdrawAmount)
    else
      Failure(new RuntimeException("Insufficient balance"))
  }

}

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