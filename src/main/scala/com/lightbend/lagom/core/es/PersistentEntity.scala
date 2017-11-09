package com.lightbend.lagom.core.es

import akka.Done
import akka.typed.ActorRef
import akka.typed.persistence.scaladsl.PersistentActor
import akka.typed.persistence.scaladsl.PersistentActor.{CompositeEffect, Effect, SideEffect}
import com.lightbend.lagom.core.es.PersistentEntity.{ReplyType, WithReply}

import scala.collection.{immutable => im}
import scala.concurrent.Future
import scala.language.implicitConversions
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}
import akka.typed.scaladsl.AskPattern._

import scala.util.control.NoStackTrace

object PersistentEntity {

  trait ReplyType[R] extends WithReply {
    type ReplyType = R
  }

  /**
    * This is the type we should use at API level so we avoid
    * having a extra R types hanging around.
    * API users may use it, but they can also use ReplyType if they feel more comfortable with
    */
  trait WithReply {
    type ReplyType
  }

  type CommandResult[R] = Either[Throwable, R]
  case class CommandEnvelop[C <: WithReply, R](entityId: String, userCommand: C, replyTo: ActorRef[CommandResult[R]])

}

abstract class PersistentEntity {

  import PersistentEntity.CommandEnvelop

  type State
  type Command  <: WithReply
  type Event

  type Behavior = PartialFunction[Option[State], Actions]

  type StateToActions = PartialFunction[State, Actions]
  type CommandHandler = PartialFunction[CommandEnvelop[_, _], Effect[Event, State]]
  type CommandHandlerTyped[C <: Command] = PartialFunction[CommandEnvelop[C, C#ReplyType], Effect[Event, State]]
  type EventHandler = PartialFunction[Event, State]



  def behavior: Behavior

  def Behavior = BehaviorBuilderFirst


  final case class Actions(private val commandHandlers: CommandHandler = PartialFunction.empty,
                           private val eventHandlers: EventHandler = PartialFunction.empty) {

    def applyCommand[C <: Command](cmd: CommandEnvelop[C, C#ReplyType]): Effect[Event, State] =
      commandHandlers(cmd)

    def applyEvent(evt: Event): State = eventHandlers(evt)

    def onCommand[C <: Command](handler: PartialFunction[C, EffectBuilder[C#ReplyType]])(implicit targetCmd: ClassTag[C]): Actions = {

      val commandHandler: CommandHandlerTyped[C] = {
        case CommandEnvelop(_, cmd, replyTo)
          if cmd.getClass == targetCmd.runtimeClass &&
             handler.isDefinedAt(cmd.asInstanceOf[C]) =>

          val builder = handler(cmd.asInstanceOf[C])
          PersistentActor
            .PersistAll[Event, State](builder.events)
              .andThen(state => builder.andThenCallbacks(state))
              .andThen { state =>
                Try(builder.reply(state)) match {
                  case Success(replyValue) => replyTo ! Right(replyValue)
                  case Failure(e) => replyTo ! Left(e)
                }
                ()
              }
      }
      copy(commandHandlers = commandHandlers.orElse(commandHandler.asInstanceOf[CommandHandler]))
    }

    def rejectCommand[C <: Command](message: String)(implicit targetCmd: ClassTag[C]): Actions =
      rejectCommand(InvalidCommandException(message))

    /**
      * Declare a command to be rejected.
      * A rejected command won't be processed and the passed throwable will be used to signaling the failure.
      */
    def rejectCommand[C <: Command](throwable: => Throwable)(implicit targetCmd: ClassTag[C]): Actions = {
      val commandHandler: CommandHandlerTyped[C] = {
        case CommandEnvelop(_, cmd, replyTo)
          if cmd.getClass == targetCmd.runtimeClass =>
          PersistentActor
            .PersistNothing[Event, State]()
            .andThen(_ => replyTo ! Left(throwable))
      }
      copy(commandHandlers = commandHandlers.orElse(commandHandler.asInstanceOf[CommandHandler]))
    }


    def rejectAll(message: String): Actions =
      rejectAll(InvalidCommandException(message))

    def rejectAll(throwable: => Throwable): Actions = {
      val commandHandler: CommandHandlerTyped[Command] = {
        case CommandEnvelop(_, _, replyTo) =>
          PersistentActor
            .PersistNothing[Event, State]()
            .andThen(_ => replyTo ! Left(throwable))
      }
      copy(commandHandlers = commandHandlers.orElse(commandHandler.asInstanceOf[CommandHandler]))
    }

    def onEvent(eventHandler: EventHandler): Actions =
      copy(eventHandlers = eventHandlers.orElse(eventHandler))

    /** Concatenate this handler with the passed [[Actions]] */
    def and(handlers: Actions) = {
      copy(
        commandHandlers = this.commandHandlers.orElse(handlers.commandHandlers),
        eventHandlers = this.eventHandlers.orElse(handlers.eventHandlers)
      )
    }
  }

  def actions = Actions()

  object Actions {
    def empty = Actions()
  }


  trait Replyable[C <: Command] {
    def replyWith(reply: State => C#ReplyType): Effect[Event, State]
  }

  case class EffectBuilderStage(private val events: im.Seq[Event],
                                private val andThenCallbacks: List[State => Unit] = List.empty)  {

    /** Convenience method to register a side effect with just a callback function */
    def andThen(callback: State => Unit): EffectBuilderStage =
      copy(andThenCallbacks = andThenCallbacks :+ callback)

    /** Convenience method to register a side effect with just a lazy expression */
    def andThen(callback: => Unit): EffectBuilderStage =
      andThen((_: State) => callback)

    def replyWithDone: EffectBuilder[Done] =
      replyWith((_: State) => Done)

    def replyWithState: EffectBuilder[State] =
      replyWith(s => s)

    def replyWith[R](reply: => R): EffectBuilder[R] =
      replyWith((_: State) => reply)

    def replyWith[R](reply: State => R): EffectBuilder[R] = {

      val andThenCallback: (State) => Unit = {
        state =>
          andThenCallbacks.reverse.foreach { sideEffect =>
            // each side-effect function is called within a Try
            // failures should not impact subsequent callbacks
            // TODO: we may want to log it
            Try(sideEffect(state))
          }
      }

      EffectBuilder(events, andThenCallback, reply)
    }

  }

  case class EffectBuilder[+R](events: im.Seq[Event],
                               andThenCallbacks: State => Unit,
                               reply: State => R)

  object EffectBuilder {
    implicit def builderToEffectDone(eventBuilder:EffectBuilderStage): EffectBuilder[Done] =
      eventBuilder.replyWithDone

    implicit def builderToEffectState(eventBuilder:EffectBuilderStage): EffectBuilder[State] =
      eventBuilder.replyWithState
  }

  object Effect {

    def persist(event: Event, events: Event*): EffectBuilderStage =
      EffectBuilderStage(im.Seq(event) ++ events)

    def persist(events: im.Seq[Event]): EffectBuilderStage =
      EffectBuilderStage(events)

    def persist(eventOpt: Option[Event]): EffectBuilderStage =
      EffectBuilderStage(eventOpt.toIndexedSeq)

    def ignore: EffectBuilderStage = persist(None)

    def reject(message: String): EffectBuilder[Nothing] =
      reject(InvalidCommandException(message))

    def reject(throwable: Throwable): EffectBuilder[Nothing] =
      EffectBuilderStage(im.Seq.empty, List.empty)
        .replyWith(_ => throw throwable)

  }

  object ReplyWith {
    def apply[R](reply: State => R): EffectBuilder[R] =
      EffectBuilderStage(im.Seq.empty, List.empty)
        .replyWith(reply)

    def state = apply(identity)
  }


  object BehaviorBuilderFirst {
    def initialState(state: State) =
      new BehaviorBuilderAndThenWithState(state)

    /**
      * Adds StateToHandlers for construction phase.
      *
      * In construction phase we must declare the Command(s) that will trigger the
      * first event that will fill the constructor of the entity state.
      */
    def create(creationActions: => Actions) =
      new BehaviorBuilderAndThen(creationActions)
  }

  class BehaviorBuilderAndThen(creationHandlers: => Actions) {

    /**
      * Adds actions for post-construction phase.
      *
      * This method receives a [[PartialFunction]] from State to Actions.
      */
    def update(updateHandlers: StateToActions): Behavior = {

      // when None, we need to create it
      case None => creationHandlers

      // when Some, we use actions that update the model
      case Some(state) if updateHandlers.isDefinedAt(state) => updateHandlers(state)
    }

  }

  class BehaviorBuilderAndThenWithState(initialState: State) {

    /**
      * Adds actions for post-construction phase.
      *
      * This method receives a [[PartialFunction]] from State to Actions.
      */
    def update(updateHandlers: StateToActions): Behavior = {

      // when None, use initial state
      case None if updateHandlers.isDefinedAt(initialState) => updateHandlers(initialState)

      // when Some, we use actions that update the model
      case Some(state) if updateHandlers.isDefinedAt(state) => updateHandlers(state)

    }
  }
}

case class InvalidCommandException(message: String) extends IllegalArgumentException(message) with NoStackTrace
