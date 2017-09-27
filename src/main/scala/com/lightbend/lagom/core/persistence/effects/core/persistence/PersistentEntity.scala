package com.lightbend.lagom.core.persistence.effects.core.persistence

import com.lightbend.lagom.core.persistence.effects.core.persistence.PersistentEntity.{ReplyType, WithReply}
import com.sun.net.httpserver.Authenticator.Failure

import scala.collection.immutable
import scala.concurrent.Future
import scala.language.implicitConversions
import scala.reflect.ClassTag
import scala.util.{Failure, Try}

object PersistentEntity {

  trait ReplyType[R] extends WithReply {
    type Reply = R
  }

  /**
    * This is the type we should use at API level so we avoid
    * having a extra R types hanging around.
    * API users may use it, but they can also use ReplyType if they feel more comfortable with
    */
  trait WithReply {
    type Reply
  }

}

abstract class PersistentEntity[Command <: WithReply, Event, State] {

  type Behavior = PartialFunction[Option[State], Handlers]

  type StateToHandlers = PartialFunction[State, Handlers]

  type CommandToEffect = PartialFunction[Command, Effect[Command]]
  type CommandHandler = PartialFunction[Command, Try[immutable.Seq[Event]]]
  type EventHandler = PartialFunction[Event, State]


  def behavior: Behavior


  object BehaviorBuilderFirst {

    /**
      * Adds StateToActions for construction phase.
      *
      * In construction phase we must declare the Command(s) that will trigger the
      * first event that will fill the constructor of the entity state.
      *
      * Based on the observation that () => Actions can be lifted to None => Actions (see bellow)
      */
    def first(creationActions: => Handlers) =
      new BehaviorBuilderAndThen(creationActions)
  }

  class BehaviorBuilderAndThen(creationActions: => Handlers) {

    /**
      * Adds entity actions for post-construction phase.
      *
      * This method receives a [[PartialFunction]] from State to Actions.
      *
      * Based on the observation that State => Actions can be lifted to Some[State] => Actions (see bellow)
      */
    def andThen(updateActions: StateToHandlers): Behavior = { // <- Behavior == PartialFunction[Option[State], Actions]

      // when None, we need to create it
      case None => creationActions

      // when Some, we use actions that update the model
      case Some(aggregate) if updateActions.isDefinedAt(aggregate) => updateActions(aggregate)
    }

  }

  def Behavior = BehaviorBuilderFirst

  sealed trait HandlersT

  final case class Handlers(commandHandlers: CommandToEffect = PartialFunction.empty,
                            eventHandlers: EventHandler = PartialFunction.empty) extends HandlersT {

    def and(handlers: HandlersT) = {

      handlers match {
        case cmd: CommandHandlers =>
          copy(commandHandlers = this.commandHandlers.orElse(cmd.commandHandlers))
        case evt: EventHandlers =>
          copy(eventHandlers = this.eventHandlers.orElse(evt.eventHandlers))
        case comb: Handlers =>
          copy(
            commandHandlers = this.commandHandlers.orElse(comb.commandHandlers),
            eventHandlers = this.eventHandlers.orElse(comb.eventHandlers)
          )
      }
    }
  }

  def onCommand[C <: Command](effect: Effect[C]): CommandHandlers = CommandHandlers(effect.toCommandHandler)

  case class CommandHandlers(commandHandlers: CommandToEffect) extends HandlersT {
    def onCommand[C <: Command](effect: Effect[C]): CommandHandlers =
      copy(commandHandlers = commandHandlers.orElse(effect.toCommandHandler))

    def and(handlers: HandlersT): Handlers = {

      handlers match {
        case cmd: CommandHandlers =>
          Handlers(commandHandlers = this.commandHandlers.orElse(cmd.commandHandlers))

        case evt: EventHandlers =>
          Handlers(
            commandHandlers = this.commandHandlers,
            eventHandlers = evt.eventHandlers
          )

        case comb: Handlers =>
          comb.copy(commandHandlers = this.commandHandlers.orElse(comb.commandHandlers))
      }
    }

  }

  def onEvent(eventHandler: EventHandler): EventHandlers = EventHandlers(eventHandler)

  case class EventHandlers(eventHandlers: EventHandler) extends HandlersT {

    def onEvent(eventHandler: EventHandler): EventHandlers =
      copy(eventHandlers = eventHandlers.orElse(eventHandler))

    def and(handlers: HandlersT): Handlers = {

      handlers match {
        case cmd: CommandHandlers =>
          Handlers(
            commandHandlers = cmd.commandHandlers,
            eventHandlers = this.eventHandlers
          )

        case evt: EventHandlers =>
          Handlers(eventHandlers = this.eventHandlers.orElse(evt.eventHandlers))

        case comb: Handlers =>
          comb.copy(eventHandlers = this.eventHandlers.orElse(comb.eventHandlers))
      }
    }
  }

  case class Effect[C <: WithReply](
                                     handler: PartialFunction[Command, Try[immutable.Seq[Event]]],
                                     andThenCallbacks: List[(immutable.Seq[Event], State) => Unit] = List.empty,
                                     replyWith: State => C#Reply) {
    def toCommandHandler: CommandToEffect = {
      case cmd if handler.isDefinedAt(cmd) => this.asInstanceOf[Effect[Command]]
    }
  }

  object Effect {

    implicit def builderToEffectDone[C <: ReplyType[Done]](replyable: Replyable[C]) =
      replyable.replyWith(_ => Done)

    implicit def builderToEffectState[C <: ReplyType[State]](replyable: Replyable[C]) =
      replyable.replyWith(identity)
  }

  trait Replyable[C <: WithReply] {
    def replyWith(reply: State => C#Reply): Effect[C]
  }

  class EffectBuilderNone[C <: WithReply : ClassTag]() extends Replyable[C] {

    def replyWith(reply: State => C#Reply): Effect[C] = {

      // NoOps handler won't ever emit events
      val noOpsHandler: PartialFunction[C, Try[immutable.Seq[Event]]] = {
        case _ => Try(immutable.Seq.empty)
      }

      EffectBuilderAll(noOpsHandler).replyWith(reply)
    }

  }

  case class EffectBuilderOne[C <: WithReply : ClassTag](handler: PartialFunction[C, Try[Event]],
                                                         andThenCallbacks: List[(Event, State) => Unit] = List.empty) extends Replyable[C] {

    def andThen(andThenCallback: (Event, State) => Unit): EffectBuilderOne[C] =
      copy(andThenCallbacks = andThenCallback :: andThenCallbacks)

    def replyWith(reply: State => C#Reply): Effect[C] = {

      // lift handler from Event to Seq[Event]
      val liftedHandler: PartialFunction[C, Try[immutable.Seq[Event]]] = {
        case cmd if handler.isDefinedAt(cmd) => handler(cmd).map(e => immutable.Seq(e))
      }

      // lift callbacks from Event to Seq[Event]
      val liftedAndThenCallbacks = andThenCallbacks.map { callback =>
        // NOTE: it's safe to call events.head because this Effect will always emit one single event (if not a failure)
        (events: immutable.Seq[Event], state: State) => callback(events.head, state)
      }

      EffectBuilderAll(liftedHandler, liftedAndThenCallbacks).replyWith(reply)
    }

  }

  case class EffectBuilderOptionOne[C <: WithReply : ClassTag](handler: PartialFunction[C, Option[Event]],
                                                               andThenCallbacks: List[(Option[Event], State) => Unit] = List.empty) extends Replyable[C] {

    def andThen(andThenCallback: (Option[Event], State) => Unit): EffectBuilderOptionOne[C] =
      copy(andThenCallbacks = andThenCallback :: andThenCallbacks)

    def replyWith(reply: State => C#Reply): Effect[C] = {

      // lift handler from Option[Event] to Seq[Event]
      val liftedHandler: PartialFunction[C, Try[immutable.Seq[Event]]] = {
        case cmd if handler.isDefinedAt(cmd) =>
          Try {
            handler(cmd) // from Option to Seq
              .map(e => immutable.Seq(e))
              .getOrElse(immutable.Seq.empty)
          }
      }

      // lift callbacks from Option[Event] to Seq[Event]
      val liftedAndThenCallbacks = andThenCallbacks.map { callback =>
        // events will be either a single element Seq or empty
        (events: immutable.Seq[Event], state: State) => callback(events.headOption, state)
      }

      EffectBuilderAll(liftedHandler, liftedAndThenCallbacks).replyWith(reply)
    }

  }

  case class EffectBuilderAll[C <: WithReply : ClassTag](handler: PartialFunction[C, Try[immutable.Seq[Event]]],
                                                         andThenCallbacks: List[(immutable.Seq[Event], State) => Unit] = List.empty) extends Replyable[C] {

    def andThen(andThenCallback: (immutable.Seq[Event], State) => Unit): EffectBuilderAll[C] =
      copy(andThenCallbacks = andThenCallback :: andThenCallbacks)

    def replyWith(reply: State => C#Reply): Effect[C] = {

      val target = implicitly[ClassTag[C]]

      // with this trick we build a PF on Command and not on C
      // C was need up to this point to carry on the Reply type.
      // from that point onward we need a PF on Command
      val handlerCmd: CommandHandler = {
        case cmd
          if target.runtimeClass == cmd.getClass &&
            handler.isDefinedAt(cmd.asInstanceOf[C]) => handler(cmd.asInstanceOf[C])
      }

      Effect(
        handler = handlerCmd,
        andThenCallbacks = andThenCallbacks.reverse,
        replyWith = reply
      )
    }
  }


  object ReadOnly {
    def apply[C <: Command : ClassTag]: EffectBuilderNone[C] = new EffectBuilderNone[C]()
  }

  object Handler {
    def apply[C <: Command : ClassTag]: HandlerSyntax[C] =
      new HandlerSyntax[C]
  }

  class HandlerSyntax[C <: WithReply : ClassTag] {

    def persistOne(handler: PartialFunction[C, Event]): EffectBuilderOne[C] =
      EffectBuilderOne[C] {
        // lifted PartialFunction
        case cmd if handler.isDefinedAt(cmd) => Try(handler(cmd))
      }

    def persistAll(handler: PartialFunction[C, immutable.Seq[Event]]): EffectBuilderAll[C] =
      EffectBuilderAll[C] {
        // lifted PartialFunction
        case cmd if handler.isDefinedAt(cmd) => Try(handler(cmd))
      }

    object attempt {

      def persistOne(handler: PartialFunction[C, Try[Event]]): EffectBuilderOne[C] =
        EffectBuilderOne[C](handler)

      def persistAll(handler: PartialFunction[C, Try[immutable.Seq[Event]]]): EffectBuilderAll[C] =
        EffectBuilderAll[C](handler)

    }

    object optionally {
      def persistOne(handler: PartialFunction[C, Option[Event]]): EffectBuilderOptionOne[C] =
        EffectBuilderOptionOne[C](handler)
    }

  }


}
