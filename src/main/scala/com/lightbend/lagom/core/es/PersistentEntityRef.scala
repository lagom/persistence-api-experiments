package com.lightbend.lagom.core.es

import akka.actor.Scheduler
import akka.typed.ActorRef
import akka.typed.persistence.scaladsl.PersistentActor
import akka.typed.persistence.scaladsl.PersistentActor.{ChainableEffect, CommandHandler, SideEffect}
import com.lightbend.lagom.core.es.PersistentEntity.{CommandEnvelop, WithReply}
import akka.typed.scaladsl.AskPattern._
import akka.util.Timeout

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.Future

object PersistentEntityRef {
  def refFor[P <: PersistentEntity](entityId: String, entityFactory: => PersistentEntity): PersistentEntityRef[P#Command]
    = new PersistentEntityRef(entityId, entityFactory)
}

class PersistentEntityRef[Cmd <: WithReply](entityId: String, entityFactory: => PersistentEntity) {


  def akkaTypedBehavior = {

    // just a trial for now to see we can build a Akka Typed Behavior using Lagom's PersistentEntity Behavior
    val entity = entityFactory

    type Command = entity.Command
    type Event = entity.Event
    type StateOpt = Option[entity.State]

    PersistentActor
      .persistentEntity[CommandEnvelop[Command, Command#ReplyType], Event, StateOpt](
        persistenceIdFromActorName = (name: String) => name,
        initialState = None,
        commandHandler = CommandHandler { (_, state, cmd) =>
          entity.behavior(state).applyCommand(cmd)
        },

      eventHandler = (stateOpt, evt) => {
          Option( // <-- need to wrap it back in a Option
            entity
              .behavior(stateOpt)
              .applyEvent(evt)
          )
        }
      )
  }


  def ?(cmd: Cmd): Future[cmd.ReplyType] = {

    lazy val actorRef: ActorRef[CommandEnvelop[Cmd, cmd.ReplyType]] = ???

    implicit val timeout = Timeout(5.seconds)
    implicit lazy val scheduler: Scheduler = ??? // TODO: get scheduler from Actor System

    val answer =
      actorRef ? { reply: ActorRef[PersistentEntity.CommandResult[cmd.ReplyType]] =>
        CommandEnvelop(entityId, cmd, reply)
      }

    answer.flatMap[cmd.ReplyType] {
      case Right(result) => Future.successful(result.asInstanceOf[cmd.ReplyType])
      case Left(exc: Throwable) => Future.failed(exc)
    }
  }

}
