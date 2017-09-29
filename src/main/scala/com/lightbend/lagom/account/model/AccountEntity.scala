package com.lightbend.lagom.account.model

import com.lightbend.lagom.core.persistence.effects.core.persistence.{Done, PersistentEntity}
import com.lightbend.lagom.core.persistence.effects.core.persistence.PersistentEntity.ReplyType

import scala.util.{Failure, Success, Try}

class AccountEntity extends BaseAccountEntity {

  /**
    * Behavior is a PartialFunction from Option[State] => Handlers
    *
    * When there is no state (None) we provide the handlers that will build the Entity
    * (equivalent to a constructor, but expressed as Command and Event Handlers)
    *
    * When there is a state (Some[State]) we provide the handlers that are able to update the Entity.
    *
    * In the absence of a snapshot, Option[State] == None and we replay the events starting from first offset.
    * This basically replays the event that is responsible for creating the model (the seed event).
    */
  override def behavior =
    Behavior
      .first { // None => Handlers == () => Handlers
        // first we define actions that can construct the Account
        // we go from None => Some[Account]
        depositCommandHandlers and atCreationEventsHandlers
      }
      .andThen { // Some[State] => Handlers == State => Handlers
        // here come the actions that are valid when Account
        // is already created
        case account =>
          readOnlyCommandHandlers and
            withdrawCommandHandlers(account) and
            depositCommandHandlers and
            afterCreationEventsHandlers(account)
      }

}
