# Persistence API Proposal
This repo is a sandbox to experiment with a new Persistence API for Lagom. At the moment, it only has a very minimal, Scala only, implementation that doesn't even use Akka. The purpose is to work on the shape of the API and get the types on the right place. The same can be achieved in Java, but much less concise. 

The original motivation for reviewing the current API comes from feedback we got in Gitter and Mailing list. Part of the discussion is documented at [Lagom issue #919](https://github.com/lagom/lagom/issues/919).

This current proposal has a few new features and/or advantages over the current API. 

1. `Behavior` is a function from `Option[State] => Actions`
2. No explicit initial value needed. Initial value of a model is always None. We don't need to force users to come up with a definition of empty. For legacy reasons, we may need to provide the means for defining an initial state.
3. Command handlers have only one argument, the `Command`. No `Context` or `State` passed around. `Context` become obsolete and `State` is available in scope by other means (see bellow).
4. `Effect` is the new black. A command handler is a function from `Command => Effect[Command]` and we provide a `EffectBuilder` DSL for different types. At the moment we have support for `Effects` emitting: `Event`, `Seq[Event]`, `Option[Event]`, `Try[Event]` and `Try[Seq[Event]]`. 
5. No need for `onReadOnlyCommand`. A read-only directive is a general `Effect` that does not emit events.

## Behavior as Option[State] => Actions
This is a important change that aleviates the API in many places and removes the need to come up with an artificial initial state. 

The main observation backing this change is that at the beginning there isn't an `Entity` and like any other event that may take place, there is an initial event that creates the `Entity`, much like we are used to create an object by calling its constructor. At some moment an `Entity` is created and the creation is expressed as an `Event`.

The `Behavior` can be decomposed in two main functions.
* `None => Actions` when the `Entity` doesn't exists yet. At API level this is a `() => Actions`
* `Some[State] => Actions` when the `Entity` was already created.  At API level this is a `PartialFunction[State, Actions]`

At API level, the developers doesn't need to deal with `Option`. They only need to provide the actions before and after creation. This will also allow the usage of ADTs to express model transition. 

## Simplified Command Handlers
The current API (Lagom 1.3.8) defines a command handler as a `PartialFunction[(Command, CommandContext[Reply], State), Persist]`. In this new API it is simplified to `PartialFunction[Command, Effect[Command]]`. 

The `Context` becomes obsolete because of a new Fluent / Intention Driven API.

## Code snippet 

This is only a short example of the main features. Check the [`AccountEntity`](https://github.com/lagom/persistence-api-experiments/blob/master/src/main/scala/com/lightbend/lagom/core/persistence/effects/core/persistence/AccountEntity.scala) for full example. 

As mentioned before, the `Behavior` is defined as two functions pre-construction and post-construction. The DSL exposes the fact that there is two phases in the life of an `Entity`, `first` we create it `and then` we may update it. 

```scala
Behavior
  .first {  // () => Actions equivalent to None => Actions
    depositCommandActions orElse atCreationEvents
  }
  .andThen { // PartialFunction[State, Actions] equivalent to Some[State] => Actions
    case account => // Account is a single type, but it could be an ADT 
      readOnlyCommands orElse
        withdrawCommandActions(account) orElse
        depositCommandActions orElse
        afterCreationEvents(account)
  }
```

We can defined `Actions` and combine then using `orElse`. Note that not all `Actions` need to have the `State` available in scope. `Withdraw` need it because we don't won't to go bellow zero. `Actions` defining `Events Handlers` do need the State. Read only actions don't need it passed, they will be made available on the `replyWith` method (see below).

### Deposit Command Handler
```scala
case class Deposit(amount: Double) extends AccountCommand[Double]

val depositCommandActions =
  actions
    .onCommand {
      // this is directive builder that expects one single Event
      Handler[Deposit]
        .persistOne {
          case cmd => DepositExecuted(cmd.amount)
        }
        .andThen {  (evt, state) =>
          println(s"Deposit ${evt.amount}, current balance is ${state.amount}")
        }
        .replyWith(_.amount) // reply with new balance
    }
```
The `Deposit` handler start with the definition of a `Handler[Deposit]` where we fix the type of the command. Next we can declare if it will emit one or many events (in this example only one), then we may declare zero or more `andThen` callabacks. Finally, we declare what needs to be returned as a reply. Must be a `Double` as defined by the `Deposit` command.

### Withdraw Command Handler
```scala
case class Withdraw(amount: Double) extends AccountCommand[Done]

def withdrawCommandActions(account: Account) =
  actions
    .onCommand {
      Handler[Withdraw]
        .attempt.persistOne { // <- this Effect builder expects a Try[Event]
          case cmd if account.amount - cmd.amount >= 0 => Success(WithdrawExecuted(cmd.amount))
          case cmd => Failure(new RuntimeException("Insufficient balance"))
        }
        // NOTE: we don't need reply because Withdraw replies with Done
        // and there is a implicit for it
    }
```

Different than the `Deposit` variation, the `Withdraw` command handler do need the current `Account` state in scope. This is made available as an argument to `withdrawCommandActions` method. 

Because a `Withdraw` may fail, we use a variation of an `EffectBuilder` that expects a `Try[Event]`. This gives us the possibility to validate the command using idiomatic Scala. 
That won't be possible in the Java variation unless we introduce a `Try` type or similar. 

This `Withdraw` doesn't need to define a reply because it replies with `Done` and there is a implicit for it. We convert implicitly from `EffectBuilder` to `Effect`. That won't be possible in Java neither.

### ReadOnly Commands

A read-only command is no different and doesn't require special API. We only need a `EffectBuilder` that never emits events and doesn't provide `andThen` callbacks. 

```scala

case object GetBalance extends AccountCommand[Double]
case object GetState extends AccountCommand[Account]`

val readOnlyCommands =
  actions
    .onCommand {
      ReadOnly[GetBalance.type].replyWith(_.amount)
    }
    .onCommand {
      // there is also an implicit for reply with State
      // but intellij gives an error hence the explicit call here
      ReadOnly[GetState.type].replyWith(identity)
    }
```

      
