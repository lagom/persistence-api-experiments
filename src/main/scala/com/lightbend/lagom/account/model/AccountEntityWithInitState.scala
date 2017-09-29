package com.lightbend.lagom.account.model

import com.lightbend.lagom.core.persistence.effects.core.persistence.PersistentEntity

class AccountEntityWithInitState extends BaseAccountEntity {

  /**
    * Behavior is a PartialFunction from Option[State] => Handlers
    *
    * However, in this example we have a initial state (model allows it), instead we provide
    * the intial state and the handlers that will be able to update id.
    *
    * In the absence of a snapshot, the intial state will be use and we replay the events starting from first offset.
    */
  override def behavior =
    Behavior
      .initialState(Account(0.0))
      .handlers {
        // here come the actions that are valid when Account
        // is already created
        case account =>
          readOnlyCommandHandlers and
            withdrawCommandHandlers(account) and
            depositCommandHandlers and
            afterCreationEventsHandlers(account)
      }

}
