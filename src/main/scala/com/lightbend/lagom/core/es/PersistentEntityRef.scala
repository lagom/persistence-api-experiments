package com.lightbend.lagom.core.es

import akka.actor.Scheduler
import akka.typed.ActorRef
import akka.typed.persistence.scaladsl.PersistentActor
import akka.typed.persistence.scaladsl.PersistentActor.{Actions, SideEffect}
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

    PersistentActor
      .persistentEntity[CommandEnvelop[entity.Command, entity.Command#ReplyType], entity.Event, Option[entity.State]](
        persistenceIdFromActorName = (name: String) => name,
        initialState = None,
        actions = Actions { (_, cmd, state) =>

          val effect =
            entity
              .behavior(state)
              .applyCommand(cmd)

          PersistentActor
            .PersistAll[entity.Event, Option[entity.State]](effect.events)
            .andThen { stateOpt =>
              stateOpt.foreach { st =>
                effect.sideEffects.collect {
                  case SideEffect(effectFunc) => effectFunc(st)
                }
              }
            }
        },

        applyEvent = (evt, stateOpt) => {
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
