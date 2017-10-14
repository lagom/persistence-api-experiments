package com.lightbend.lagom.core.persistence.effects.core.persistence

import com.lightbend.lagom.core.persistence.effects.core.persistence.PersistentEntity.WithReply
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

/**
  * This is a dummy  PersistenceEntityRef. It's not backed by an Actor.
  * Its purpose is to just demonstrate what it would look like if we have it running inside and Actor
  */
class PersistenceEntityRef[C <: WithReply, Event, State](entity: PersistentEntity[C, Event, State]) {

  private var stateOpt: Option[State] = None

  def ?[CC <: C](cmd: CC): Future[C#Reply] = {

    // using the current state, we find the actions that are applicable
    val actions = entity.behavior(stateOpt)

    // find the Effect that were registered for the passed Command
    val effect = actions.commandHandlers.apply(cmd).asInstanceOf[entity.Effect[CC]]

    /*
      val triedEvents = effectCxt(cmd).events
      val callbacks = effectCxt(cmd).callbacks
    */
    // emit events
    val triedEvents = effect.handler(cmd)

    // apply events and update state
    stateOpt =
      triedEvents match {
        case Success(events) =>
          events.foldLeft(stateOpt) {
            // which event handlers for state?
            case (s, e) => Option(entity.behavior(s).eventHandlers(e))
          }
        case Failure(exp) => throw exp
      }

    // at that point we should persist the events

    class ReplyContext extends Context {
      val promise =  Promise[Any]()
      val future = promise.future
      override def reply[A](value: A): Unit = promise.complete(Try(value))

    }

    val replyContext = new ReplyContext

    // once events persisted, we loop over the callbacks using the updated state.
    // This must be protected, any failure must be ignored, but logged. That's a best effort.
    triedEvents match {
      case Success(events) =>
        // after persisting, run the callbacks
        stateOpt.foreach(state => effect.andThenCallback(cmd, state,  events, replyContext))

      case _ => () // no call in case of failure
    }

    // once all done, reply using state
    //NOTE: this can only fail if a ReadOnly command is run before creating the entity
    replyContext.future.map(_.asInstanceOf[C#Reply])
  }
}
