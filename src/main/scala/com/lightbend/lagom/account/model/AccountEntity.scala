package com.lightbend.lagom.account.model

import com.lightbend.lagom.core.persistence.effects.core.persistence.{Done, PersistentEntity}
import com.lightbend.lagom.core.persistence.effects.core.persistence.PersistentEntity.ReplyType

import scala.util.{Failure, Success, Try}

class AccountEntity extends PersistentEntity[AccountCommand[_], AccountEvent, Account] {

  override def behavior =
    Behavior
      .first { 
        Handlers.empty
      }
      .andThen { 
        case acc => Handlers.empty
      }

}
