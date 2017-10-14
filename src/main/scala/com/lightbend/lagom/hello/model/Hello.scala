package com.lightbend.lagom.hello.model

import com.lightbend.lagom.core.persistence.effects.core.persistence.Done
import com.lightbend.lagom.core.persistence.effects.core.persistence.PersistentEntity.ReplyType

case class HelloState(message: String, timestamp: String)

sealed trait HelloCommand[R] extends ReplyType[R]
case class UseGreetingMessage(message: String) extends HelloCommand[Done]
case class Hello(name: String) extends HelloCommand[String]

sealed trait HelloEvent
case class GreetingMessageChanged(message: String) extends HelloEvent
