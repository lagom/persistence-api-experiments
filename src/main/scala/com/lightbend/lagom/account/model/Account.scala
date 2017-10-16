package com.lightbend.lagom.account.model

import com.lightbend.lagom.core.persistence.effects.core.persistence.Done
import com.lightbend.lagom.core.persistence.effects.core.persistence.PersistentEntity.ReplyType

import scala.util.{Failure, Success, Try}


sealed trait Account

case class OpenedAccount(amount: Double)
case object ClosedAccount

sealed trait AccountCommand[R] extends ReplyType[R]

case class Deposit(amount: Double) extends AccountCommand[Unit]

case class Withdraw(amount: Double) extends AccountCommand[Unit]

case object GetBalance extends AccountCommand[Unit]

case object GetState extends AccountCommand[Unit]


sealed trait AccountEvent {
  def amount: Double
}

case class DepositExecuted(amount: Double) extends AccountEvent

case class WithdrawExecuted(amount: Double) extends AccountEvent
