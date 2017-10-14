package com.lightbend.lagom.hello.model

import java.time.LocalDateTime

import com.lightbend.lagom.core.persistence.effects.core.persistence.{Done, PersistentEntity}

class HelloEntity extends PersistentEntity[HelloCommand[_], HelloEvent, HelloState] {

  def changeMessageCmd(state: HelloState) =
    onCommand {
      Handler[UseGreetingMessage]
        .persistOne {
          case cmd => GreetingMessageChanged(cmd.message)
        }
    }

  def eventHandler(state: HelloState) =
    onEvent {
      case GreetingMessageChanged(msg) => HelloState(message = msg, timestamp = LocalDateTime.now.toString)
    }

  override def behavior =
    Behavior
      .initialState(HelloState("Hello", LocalDateTime.now.toString))
      .handlers {
        case hello => changeMessageCmd(hello) and eventHandler(hello)
      }
}

