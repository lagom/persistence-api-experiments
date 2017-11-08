package com.lightbend.lagom.hello.model

import akka.Done
import com.lightbend.lagom.core.es.PersistentEntity
import com.lightbend.lagom.core.es.PersistentEntity.ReplyType
import java.time.LocalDateTime

case class Hello(message: String, timestamp: String)

object Hello extends PersistentEntity {

  type State = Hello
  type Command = HelloCommand[_]
  type Event = HelloEvent

  def changeMessageCmd(state: Hello) =
    handlers
      .onCommand[UseGreetingMessage] {
        case cmd =>  Effect.persist(GreetingMessageChanged(cmd.message))
      }
      .onCommand[SayNothing.type] {
        case _ => Effect.ignore
      }

  def eventHandler(state: Hello) =
    handlers
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
