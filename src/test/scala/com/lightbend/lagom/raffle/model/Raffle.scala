package com.lightbend.lagom.raffle.model

import java.time.OffsetDateTime

import akka.Done
import com.lightbend.lagom.core.es.PersistentEntity
import com.lightbend.lagom.core.es.PersistentEntity.ReplyType

import scala.util.Random

object Raffle extends PersistentEntity {

  type State = Raffle
  type Command = RaffleCommand
  type Event = RaffleEvent

  override def behavior =
    Behavior
      .initialState(EmptyRaffle)
      .update {

        case EmptyRaffle =>
          EmptyRaffle.acceptParticipants and
            EmptyRaffle.canNotRunWithoutParticipants and
            EmptyRaffle.ignoreRemovals

        case raffle: NonEmptyRaffle =>
          raffle.acceptParticipants and
            raffle.removeParticipants and
            raffle.run

        case FinishedRaffle =>
          FinishedRaffle.rejectAllCommands
      }
}

import com.lightbend.lagom.raffle.model.Raffle._

sealed trait Raffle

case object EmptyRaffle extends Raffle {


  val acceptParticipants =
    actions
      .onCommand[AddParticipant] {
        case AddParticipant(name) =>
          Effect.persist(ParticipantAdded(name))
      }
      .onEvent {
        case ParticipantAdded(name) => NonEmptyRaffle(List(name))
      }

  val canNotRunWithoutParticipants =
    actions
      .rejectCommand[Run.type]("Raffle has no participants")

  val ignoreRemovals =
    actions
      .onCommand[RemoveAllParticipants.type] {
        case _ => Effect.ignore
      }
      .onCommand[RemoveParticipant] {
        case _ => Effect.ignore
      }
}

case class NonEmptyRaffle(participants: List[String]) extends Raffle {

  def hasParticipant(name: String) = participants.contains(name)

  val acceptParticipants =
    actions
      .onCommand[AddParticipant] {
        // reject double booking
        case AddParticipant(name) if hasParticipant(name) =>
          Effect.reject(s"""Participant $name already added!""" )

        case AddParticipant(name) =>
          Effect.persist(ParticipantAdded(name))
      }
      .onEvent {
        case ParticipantAdded(name) => copy(participants = participants :+ name)
      }


  val removeParticipants =
    actions
      .onCommand[RemoveParticipant] {
        case RemoveParticipant(name) =>
          Effect.persist {
            participants.find(_ == name).map(ParticipantRemoved)
          }
      }
      .onCommand[RemoveAllParticipants.type] {
        case RemoveAllParticipants =>
          Effect.persist {
            participants.map(ParticipantRemoved)
          }
      }
      .onEvent {
        case ParticipantRemoved(name) =>
          val remaining = participants.filter(_ != name)
          // NOTE: if last participant is removed, transition back to EmptyRaffle
          if (remaining.isEmpty)
            EmptyRaffle
          else
            copy(participants = remaining)
      }

  val run =
    actions
      .onCommand[Run.type] {
        case Run =>
          val index  = Random.nextInt(participants.size)
          val winner = participants(index)
          Effect.persist(WinnerSelected(winner, OffsetDateTime.now))
      }
     .onEvent {
       case _: WinnerSelected => FinishedRaffle
     }

}

case object FinishedRaffle extends Raffle {
  def rejectAllCommands = actions.rejectAll("Raffle is already finished")
}

sealed trait RaffleCommand extends ReplyType[Done]

case class AddParticipant(name: String) extends RaffleCommand

case class RemoveParticipant(name: String) extends RaffleCommand

case object RemoveAllParticipants extends RaffleCommand

case object Run extends RaffleCommand

sealed trait RaffleEvent

sealed trait RaffleUpdateEvent extends RaffleEvent
case class ParticipantAdded(name: String) extends RaffleEvent
case class ParticipantRemoved(name: String) extends RaffleEvent
case class WinnerSelected(winner: String, date: OffsetDateTime) extends RaffleEvent
