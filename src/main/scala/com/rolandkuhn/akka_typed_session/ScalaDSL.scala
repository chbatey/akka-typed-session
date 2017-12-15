/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com/>
 */
package com.rolandkuhn.akka_typed_session

import akka.typed._
import scala.concurrent.duration._
import akka.{ actor ⇒ a }
import scala.util.control.NoStackTrace
import shapeless.{ Coproduct, :+:, CNil }
import shapeless.ops.coproduct
import akka.typed.receptionist.Receptionist
import akka.Done

/**
 * A DSL for writing reusable behavior pieces that are executed concurrently
 * within Actors.
 *
 * Terminology:
 *
 *  - a Process has a 1:1 relationship with an ActorRef
 *  - an Operation is a step that a Process takes and that produces a value
 *  - Processes are concurrent, but not distributed: all failures stop the entire Actor
 *  - each Process has its own identity (due to ActorRef), and the Actor has its own
 *    identity (an ActorRef[ActorCmd[_]]); processSelf is the Process’ identity, actorSelf is the Actor’s
 *  - process timeout means failure
 *  - every Actor has a KV store for state
 *
 *      - querying by key (using a single element per slot)
 *      - updating is an Operation that produces events that are applied to the state
 *      - persistence can be plugged in transparently (NOT YET IMPLEMENTED)
 *      - recovery means acquiring state initially (which might trigger internal replay)
 */
object ScalaDSL {

  /**
   * Exception type that is thrown by the `retry` facility when after the
   * given number of retries still no value has been obtained.
   */
  final class RetriesExceeded(message: String) extends RuntimeException(message) with NoStackTrace

  /**
   * This is a compile-time marker for the type of self-reference expected by
   * the process that is being described. No methods can be called on a value
   * of this type. It is used as follows:
   *
   * {{{
   * OpDSL[MyType] { implicit opDSL =>
   *   ... // use Operation operators here
   * }
   * }}}
   */
  sealed trait OpDSL[Self] extends Any

  /**
   * This object offers different constructors that provide a scope within
   * which [[Operation]] values can be created using the `op*` methods. The
   * common characteristic of these constructors is that they lift their
   * contents completely into the resulting process description, in other
   * words the code within is only evaluated once the [[Operation]] has been
   * called, forked, or spawned within an Actor.
   *
   * It is strongly recommended to always use the same name for the required
   * implicit function argument (`opDSL` in the examples below) in order to
   * achieve proper scoping for nested declarations.
   *
   * Usage for single-shot processes:
   * {{{
   * OpDSL[MyType] { implicit opDSL =>
   *   for {
   *     x &lt;- step1
   *     y &lt;- step2
   *     ...
   *   } ...
   * }
   * }}}
   *
   * Usage for bounded repetition (will run the whole process three times
   * in this example and yield a list of the three results):
   * {{{
   * OpDSL.loop[MyType](3) { implicit opDSL =>
   *   for {
   *     x &lt;- step1
   *     y &lt;- step2
   *     ...
   *   } ...
   * }
   * }}}
   *
   * Usage for infinite repetition, for example when writing a server process:
   * {{{
   * OpDSL.loopInf[MyType] { implicit opDSL =>
   *   for {
   *     x &lt;- step1
   *     y &lt;- step2
   *     ...
   *   } ...
   * }
   * }}}
   */
  object OpDSL {
    private val _unit: Operation[Nothing, Null, HNil] = opUnit(null)(null: OpDSL[Nothing])
    private def unit[S, Out]: Operation[S, Out, HNil] = _unit.asInstanceOf[Operation[S, Out, HNil]]

    def loopInf[S]: NextLoopInf[S] = _nextLoopInf.asInstanceOf[NextLoopInf[S]]
    trait NextLoopInf[S] {
      def apply[U, E <: Effects](body: implicit OpDSL[S] ⇒ Operation[S, U, E]): Operation[S, Nothing, Loop[E]] = {
        lazy val l: Operation[S, Nothing, E] = unit[S, OpDSL[S]].flatMap(body).withEffects[HNil].flatMap(_ ⇒ l)
        l.withEffects[Loop[E]]
      }
    }
    private object _nextLoopInf extends NextLoopInf[Nothing]

    def apply[T]: Next[T] = _next.asInstanceOf[Next[T]]
    trait Next[T] {
      def apply[U, E <: Effects](body: implicit OpDSL[T] ⇒ Operation[T, U, E]): Operation[T, U, E] =
        unit[T, OpDSL[T]].flatMap(body)
    }
    private object _next extends Next[Nothing]

    trait NextStep[T] {
      def apply[U, E <: Effects, TO](mailboxCapacity: Int, body: implicit OpDSL[T] ⇒ Operation[T, U, E])(
        implicit opDSL: OpDSL[TO]): Operation[TO, U, E] =
        Impl.Call(Process("nextStep", Duration.Inf, mailboxCapacity, body(null)), None)
    }
    object _nextStep extends NextStep[Nothing]
  }

  /*
   * The core operations: keep these minimal!
   */

  /**
   * Obtain a reference to the ActorSystem in which this process is running.
   */
  def opSystem[S](implicit opDSL: OpDSL[S]): Operation[S, ActorSystem[Nothing], HNil] = Impl.System

  /**
   * Read a message from this process’ input channel.
   */
  def opRead[S](implicit opDSL: OpDSL[S]): Operation[S, S, E.Read[S] :: HNil] = Impl.Read

  /**
   * Send a message to the given target actor.
   */
  def opSend[T, S](target: ActorRef[T], msg: T)(implicit opDSL: OpDSL[S]): Operation[S, a.Cancellable, E.Send[T] :: HNil] =
    opSchedule(Duration.Zero, target, msg)

  /**
   * Obtain this process’ [[ActorRef]], not to be confused with the ActorRef of the Actor this process is running in.
   */
  def opProcessSelf[S](implicit opDSL: OpDSL[S]): Operation[S, ActorRef[S], HNil] = Impl.ProcessSelf

  /**
   * Obtain the [[ActorRef]] of the Actor this process is running in.
   */
  def opActorSelf[S](implicit opDSL: OpDSL[S]): Operation[S, ActorRef[ActorCmd[Nothing]], HNil] = Impl.ActorSelf

  /**
   * Lift a plain value into a process that returns that value.
   */
  def opUnit[U, S](value: U)(implicit opDSL: OpDSL[S]): Operation[S, U, HNil] = Impl.Return(value)

  /**
   * Start a list of choices. The effects of the choices are accumulated in
   * reverse order in the Coproduct within the Choice effect.
   *
   * {{{
   * opChoice(x > 5, opRead)
   *   .elseIf(x > 0, opUnit(42))
   *   .orElse(opAsk(someActor, GetNumber))
   * : Operation[Int, Int, Choice[
   *     (Send[GetNumber] :: Read[Int] :: _0) :+:
   *     _0 :+:
   *     (Read[Int] :: _0) :+:
   *     CNil] :: _0]
   * }}}
   */
  def opChoice[S, O, E <: Effects](p: Boolean, op: ⇒ Operation[S, O, E]): OpChoice[S, Operation[S, O, HNil], CNil, E :+: CNil, Operation[S, O, HNil], O] =
    if (p) new OpChoice(Some(Coproduct(op.ignoreEffects)))
    else new OpChoice(None)

  class OpChoice[S, H <: Operation[S, _, _], T <: Coproduct, E0 <: Coproduct, +O <: Operation[S, Output, _], Output](ops: Option[H :+: T])(
      implicit u: coproduct.Unifier.Aux[H :+: T, O]) {

    def elseIf[O1 >: Output, E1 <: Effects](p: => Boolean, op: => Operation[S, O1, E1])(
      implicit u1: coproduct.Unifier.Aux[Operation[S, O1, HNil] :+: H :+: T, Operation[S, O1, HNil]]): OpChoice[S, Operation[S, O1, HNil], H :+: T, E1 :+: E0, Operation[S, O1, HNil], O1] = {
      val ret = ops match {
        case Some(c) => Some(c.extendLeft[Operation[S, O1, HNil]])
        case None    => if (p) Some(Coproduct[Operation[S, O1, HNil] :+: H :+: T](op.ignoreEffects)) else None
      }
      new OpChoice(ret)
    }

    def orElse[O1 >: Output, E1 <: Effects](op: => Operation[S, O1, E1])(
      implicit u1: coproduct.Unifier.Aux[Operation[S, O1, HNil] :+: H :+: T, Operation[S, O1, HNil]]): Operation[S, O1, E.Choice[E1 :+: E0] :: HNil] = {
      val ret = ops match {
        case Some(c) => c.extendLeft[Operation[S, O1, HNil]]
        case None    => Coproduct[Operation[S, O1, HNil] :+: H :+: T](op.ignoreEffects)
      }
      Impl.Choice[S, O1, E1 :+: E0](u1(ret))
    }
  }

  /**
   * Execute the given process within the current Actor, await and return that process’ result.
   * If the process does not return a result (due to a non-matching `filter` expression), the
   * replacement value is used if the provided Option contains a value.
   */
  def opCall[Self, Out, E <: Effects, S](process: Process[Self, Out, E], replacement: Option[Out] = None)(
    implicit opDSL: OpDSL[S]): Operation[S, Out, E] =
    Impl.Call(process, replacement)

  /**
   * Create and execute a process with a self reference of the given type,
   * await and return that process’ result. This is equivalent to creating
   * a process with [[OpDSL]] and using `call` to execute it. A replacement
   * value is not provided; if recovery from a halted subprocess is desired
   * please use `opCall` directly.
   */
  def opNextStep[T]: OpDSL.NextStep[T] =
    OpDSL._nextStep.asInstanceOf[OpDSL.NextStep[T]]

  /**
   * Execute the given process within the current Actor, concurrently with the
   * current process. The value computed by the forked process cannot be
   * observed, instead you would have the forked process send a message to the
   * current process to communicate results. The returned [[SubActor]] reference
   * can be used to send messages to the forked process or to cancel it.
   */
  def opFork[Self, E <: Effects, S](process: Process[Self, Any, E])(implicit opDSL: OpDSL[S]): Operation[S, SubActor[Self], E.Fork[E] :: HNil] =
    Impl.Fork(process)

  /**
   * Execute the given process in a newly spawned child Actor of the current
   * Actor. The new Actor is fully encapsulated behind the [[ActorRef]] that
   * is returned.
   *
   * The mailboxCapacity for the Actor is configured using the optional
   * [[DeploymentConfig]] while the initial process’ process mailbox is
   * limited based on the [[Process]] configuration as usual. When sizing
   * the Actor mailbox capacity you need to consider that communication
   * between the processes hosted by that Actor and timeouts also go through
   * this mailbox.
   */
  def opSpawn[Self, E <: Effects, S](process: Process[Self, Any, E], deployment: Props = Props.empty)(
    implicit opDSL: OpDSL[S]): Operation[S, ActorRef[ActorCmd[Self]], E.Spawn[E] :: HNil] =
    Impl.Spawn(process, deployment)

  /**
   * Schedule a message to be sent after the given delay has elapsed.
   */
  def opSchedule[T, S](delay: FiniteDuration, target: ActorRef[T], msg: T)(implicit opDSL: OpDSL[S]): Operation[S, a.Cancellable, E.Send[T] :: HNil] =
    Impl.Schedule(delay, msg, target)

  /**
   * Watch the given [[ActorRef]] and send the specified message to the given
   * target when the watched actor has terminated. The returned Cancellable
   * can be used to unwatch the watchee, which will inhibit the message from
   * being dispatched—it might still be delivered if it was previously dispatched.
   *
   * If `onFailure` is provided it can override the value to be sent if the
   * watched Actor failed and was a child Actor of the Actor hosting this process.
   */
  def opWatch[T, S](watchee: ActorRef[Nothing], target: ActorRef[T], msg: T, onFailure: Throwable ⇒ Option[T] = any2none)(
    implicit opDSL: OpDSL[S]): Operation[S, a.Cancellable, HNil] =
    Impl.WatchRef(watchee, target, msg, onFailure)

  val any2none = (_: Any) ⇒ None
  private val _any2Nil = (state: Any) ⇒ Nil -> state
  private def any2Nil[T] = _any2Nil.asInstanceOf[T ⇒ (Nil.type, T)]

  /**
   * Read the state stored for the given [[StateKey]], suspending this process
   * until after all outstanding updates for the key have been completed if
   * `afterUpdates` is `true`.
   */
  def opReadState[T, Ev, S](key: StateKey[T, Ev], afterUpdates: Boolean = true)(implicit opDSL: OpDSL[S]): Operation[S, T, HNil] =
    Impl.State[T, Ev, T](key, afterUpdates, any2Nil)

  /**
   * Update the state stored for the given [[StateKey]] by emitting events that
   * are applied to the state in order, suspending this process
   * until after all outstanding updates for the key have been completed if
   * `afterUpdates` is `true`. The return value is determined by the transform
   * function based on the current state; if you want to return the state that
   * results from having applied the emitted events then please see
   * [[ScalaProcess#opUpdateAndReadState]].
   */
  def opUpdateState[T, Ev, Ex, S](key: StateKey[T, Ev], afterUpdates: Boolean = true)(
    transform: T ⇒ (Seq[Ev], Ex))(implicit opDSL: OpDSL[S]): Operation[S, Ex, HNil] =
    Impl.State(key, afterUpdates, transform)

  /**
   * Update the state by emitting a sequence of events, returning the updated state. The
   * process is suspended until after all outstanding updates for the key have been
   * completed if `afterUpdates` is `true`.
   */
  def opUpdateAndReadState[T, Ev, S](key: StateKey[T, Ev], afterUpdates: Boolean = true)(
    transform: T ⇒ Seq[Ev])(implicit opDSL: OpDSL[S]): Operation[S, T, HNil] =
    Impl.StateR(key, afterUpdates, transform)

  /**
   * FIXME not yet implemented
   *
   * Instruct the Actor to persist the state for the given [[StateKey]] after
   * all currently outstanding updates for this key have been completed,
   * suspending this process until done.
   */
  //def opTakeSnapshot[T](key: PersistentStateKey[T])(implicit opDSL: OpDSL): Operation[S, T] =
  //  Snapshot(key)

  /**
   * FIXME not yet implemented
   *
   * Restore the state for the given [[StateKey]] from persistent event storage.
   * If a snapshot is found it will be used as the starting point for the replay,
   * otherwise events are replayed from the beginning of the event log, starting
   * with the given initial data as the state before the first event is applied.
   */
  //def opReplayPersistentState[T](key: PersistentStateKey[T])(implicit opDSL: OpDSL): Operation[S, T] =
  //  Replay(key)

  /**
   * Remove the given [[StateKey]] from this Actor’s storage. The slot can be
   * filled again using `updateState` or `replayPersistentState`.
   */
  def opForgetState[T, Ev, S](key: StateKey[T, Ev])(implicit opDSL: OpDSL[S]): Operation[S, akka.Done, HNil] =
    Impl.Forget(key)

  /**
   * Run the given cleanup handler after the operations that will be chained
   * off of this one, i.e. this operation must be further transformed to make
   * sense.
   *
   * Usage with explicit combinators:
   * {{{
   * opCleanup(() => doCleanup())
   *   .flatMap { _ =>
   *     ...
   *   } // doCleanup() will run here
   *   .flatMap { ... }
   * }}}
   *
   * Usage with for-expressions:
   * {{{
   * (for {
   *     resource &lt;- obtainResource
   *     _ &lt;- opCleanup(() => doCleanup(resource))
   *     ...
   *   } yield ...
   * ) // doCleanup() will run here
   * .flatMap { ... }
   * }}}
   *
   * Unorthodox usage:
   * {{{
   * (for {
   *     resource &lt;- obtainResource
   *     ...
   *   } yield opCleanup(() => doCleanup(resource))
   * ) // doCleanup() will run here
   * .flatMap { ... }
   * }}}
   */
  def opCleanup[S](cleanup: () ⇒ Unit)(implicit opDSL: OpDSL[S]): Operation[S, akka.Done, HNil] =
    Impl.Cleanup(cleanup)

  /**
   * Terminate processing here, ignoring further transformations. If this process
   * has been called by another process then the `replacement` argument to `opCall`
   * determines whether the calling process continues or halts as well: if no
   * replacement is given, processing cannot go on.
   */
  def opHalt[S](implicit opDSL: OpDSL[S]): Operation[S, Nothing, E.Halt :: HNil] = Impl.ShortCircuit

  // FIXME opChildList
  // FIXME opProcessList
  // FIXME opTerminate
  // FIXME opStopChild
  // FIXME opAsk(Main)
  // FIXME opParallel
  // FIXME opUpdate(Read)SimpleState

  /*
   * Derived operations
   */

  /**
   * Suspend the process for the given time interval and deliver the specified
   * value afterwards. This is especially useful as a timeout value for `firstOf`.
   */
  def delay[T](time: FiniteDuration, value: T): Operation[T, T, HNil] =
    OpDSL[T] { implicit opDSL ⇒
      for {
        self ← opProcessSelf
        _ ← opSchedule(time, self, value)
      } yield opRead
    }.ignoreEffects

  /**
   * Fork the given process, but also fork another process that will cancel the
   * first process after the given timeout.
   */
  def forkAndCancel[T, E <: Effects, S](timeout: FiniteDuration, process: Process[T, Any, E])(
    implicit opDSL: OpDSL[S]): Operation[S, SubActor[T], E.Fork[E] :: E.Fork[E.Send[Boolean] :: E.Read[Boolean] :: E.Choice[(E.Halt :: HNil) :+: HNil :+: CNil] :: HNil] :: HNil] = {
    def guard(sub: SubActor[T]) = OpDSL[Boolean] { implicit opDSL ⇒
      for {
        self ← opProcessSelf
        _ ← opWatch(sub.ref, self, false)
        _ ← opSchedule(timeout, self, true)
        cancel ← opRead
        if cancel
      } yield sub.cancel()
    }

    for {
      sub ← opFork(process)
      _ ← opFork(guard(sub).named("cancelAfter"))
    } yield sub
  }

  /**
   * Fork the given processes the return the first value emitted by any one of
   * them. As soon as one process has yielded its value all others are canceled.
   *
   * TODO figure out effects
   */
  def firstOf[T](processes: Process[_, T, _ <: Effects]*): Operation[T, T, HNil] = {
    def forkAll(self: ActorRef[T], index: Int = 0,
                p: List[Process[_, T, _ <: Effects]] = processes.toList,
                acc: List[SubActor[Nothing]] = Nil)(implicit opDSL: OpDSL[T]): Operation[T, List[SubActor[Nothing]], HNil] =
      p match {
        case Nil ⇒ opUnit(acc)
        case x :: xs ⇒
          opFork(x.copy(name = s"$index-${x.name}").map(self ! _))
            .map(sub ⇒ forkAll(self, index + 1, xs, sub :: acc))
            .ignoreEffects
      }
    OpDSL[T] { implicit opDSL ⇒
      for {
        self ← opProcessSelf
        subs ← forkAll(self)
        value ← opRead
      } yield {
        subs.foreach(_.cancel())
        value
      }
    }.ignoreEffects
  }

  /**
   * Retry the given process the specified number of times, always bounding
   * the wait time by the given timeout and canceling the fruitless process.
   * If the number of retries is exhausted, the entire Actor will be failed.
   *
   * FIXME effects need more thought
   */
  def retry[Self, T, E <: Effects, S](timeout: FiniteDuration, retries: Int, ops: Process[Self, T, E])(implicit opDSL: OpDSL[S]): Operation[S, T, E] = {
    opCall(firstOf(ops.map(Some(_)), delay(timeout, None).named("retryTimeout")).named("firstOf"))
      .flatMap {
        case Some(res)           ⇒ opUnit(res).withEffects[E]
        case None if retries > 0 ⇒ retry(timeout, retries - 1, ops)
        case None                ⇒ throw new RetriesExceeded(s"process ${ops.name} has been retried $retries times with timeout $timeout")
      }
  }

  // FIXME effects
  def getService[T](key: Receptionist.ServiceKey[T]): Operation[Receptionist.Listing[T], ActorRef[T], HNil] = {
    import Receptionist._
    OpDSL[Listing[T]] { implicit opDSL =>
      retry(1.second, 10, (for {
        sys <- opSystem
        self <- opProcessSelf
        _ <- opSend(sys.receptionist, Find(key)(self))
        Listing(_, addresses) <- opRead
      } yield addresses.headOption.map(opUnit(_)).getOrElse(opHalt.ignoreEffects)).named("askReceptionist")).ignoreEffects
    }
  }

  def registerService[T](key: Receptionist.ServiceKey[T], service: ActorRef[T]) //
  : Operation[Receptionist.Registered[T], Done, E.Send[Receptionist.Register[T]] :: E.Read[Receptionist.Registered[T]] :: HNil] = {
    import Receptionist._
    OpDSL[Registered[T]] { implicit opDSL =>
      for {
        sys <- opSystem
        self <- opProcessSelf
        _ <- opSend(sys.receptionist, Register(key, service)(self))
        _ <- opRead
      } yield Done
    }
  }

}
