# Persistence API Proposal
This repo is a sandbox to experiment with a new Persistence API for Lagom. At the moment, it only has a very minimal, Scala only, implementation based on also experimental Akka Typed Persistence API. The purpose is to work on the shape of this API and get the types on the right place. The same can be achieved in Java, but much less concise. 

The original motivation for reviewing the current API comes from feedback we got in Gitter and Mailing list. Part of the discussion is documented at [Lagom issue #919](https://github.com/lagom/lagom/issues/919).

This current proposal has a few new features and/or advantages over the current API. 

1. `Behavior` is a function from `Option[State] => Actions`
2. No explicit initial value needed. Initial value of a model is always None. We don't need to force users to come up with a definition of empty. For legacy reasons, provide the means for defining an initial state.
3. Command handlers have only one argument, the `Command`. No `Context` or `State` passed around. `Context` become obsolete and `State` is available in scope by other means (see bellow).
4. `Effect` is the new black. A command handler is a function from `Command => Effect` and we provide a `EffectBuilder` DSL for different types. At the moment we have support for `Effects` emitting: `Event`, `Seq[Event]` and `Option[Event]`. 
5. No need for `onReadOnlyCommand`. A read-only directive is a general `Effect` that does not emit events.

Please, feel free to open issues to make suggestions and discuss any topic in detail.

## Behavior as Option[State] => Actions
This is a important change that alleviates the API in many places and removes the need to come up with an artificial initial state.

The main observation backing this change is that at the beginning there isn't an `Entity` and like any other event that may take place, there is an initial event that creates the `Entity`, much like we are used to create an object by calling its constructor. At some moment an `Entity` is created and the creation is expressed as an `Event`.

The `Behavior` can be decomposed in two main functions.
* `None => Actions` when the `Entity` doesn't exists yet. At API level this is a `() => Actions`
* `Some[State] => Actions` when the `Entity` was already created.  At API level this is a `PartialFunction[State, Actions]`

There is an alternative `Behavior` builder where we first must defined an initial value followed by a function `State => Actions`.

At API level, the developers doesn't need to deal with `Option`. They only need to provide the Actions before and after creation. This will also allow the usage of ADTs to express model transition.

## Simplified Command Handlers
The current API (Lagom 1.3.19) defines a command handler as a `PartialFunction[(Command, CommandContext[Reply], State), Persist]`. In this new API it is simplified to `PartialFunction[Command, Effect]`. 

The `Context` becomes obsolete because of a new Fluent / Intention Driven API.

## Code examples 

There are  three examples in the test folder to demonstrate the available API and its look-and-feel.

* [`Hello World`](https://github.com/lagom/persistence-api-experiments/blob/master/src/test/scala/com/lightbend/lagom/hello/model/Hello.scala) 
* [`Account`](https://github.com/lagom/persistence-api-experiments/blob/master/src/test/scala/com/lightbend/lagom/account/model/Account.scala) 
* [`Raffle`](https://github.com/lagom/persistence-api-experiments/blob/master/src/test/scala/com/lightbend/lagom/raffle/model/Raffle.scala) 
      
