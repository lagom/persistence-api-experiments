package com.lightbend.lagom.hello.model

import java.time.LocalDateTime

import akka.Done
import com.lightbend.lagom.core.es.PersistentEntity
import com.lightbend.lagom.core.es.PersistentEntity.ReplyType

case class Hello(message: String, timestamp: String)

object Hello extends PersistentEntity {

  type State = Hello
  type Command = HelloCommand[_]
  type Event = HelloEvent

  def changeMessageCmd(state: Hello) =
    actions
      .onCommand[UseGreetingMessage] {
        case cmd =>  Effect.persist(GreetingMessageChanged(cmd.message))
      }
      .onCommand[SayNothing.type] {
        case _ => Effect.none
      }

  def eventHandler(state: Hello) =
    actions
      .onEvent {
        case GreetingMessageChanged(msg) => Hello(message = msg, timestamp = LocalDateTime.now.toString)
      }

  override def behavior =
    Behavior
      .initialState(Hello("Hello", LocalDateTime.now.toString))
      .update {
        case hello => changeMessageCmd(hello) and eventHandler(hello)
      }
}

sealed trait HelloCommand[R] extends ReplyType[R]
case class UseGreetingMessage(message: String) extends HelloCommand[Done]
case object SayNothing extends HelloCommand[Done]
case class SayHello(name: String) extends HelloCommand[String]

sealed trait HelloEvent
case class GreetingMessageChanged(message: String) extends HelloEvent
