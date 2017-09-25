package com.lightbend.lagom

import com.lightbend.lagom.core.persistence.effects.core.persistence.PersistentEntity.ReplyType
import com.lightbend.lagom.core.persistence.effects.core.persistence._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object App {

  def main(args: Array[String]): Unit = {

    val entityRef = new PersistenceEntityRef(new AccountEntity)

    val result =
    for {
      _ <- entityRef ? Deposit(100)
      _ <- entityRef ? Deposit(120)
      _ <- entityRef ? Withdraw(80)
      balance <- entityRef ? GetBalance
      state <- entityRef ? GetState
    } yield {
      println("Balance is: " + balance)
      println("State is: " + state)
    }

    Await.ready(result, 500.millis)

  }


}
