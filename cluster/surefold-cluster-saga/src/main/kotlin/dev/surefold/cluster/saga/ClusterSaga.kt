package dev.surefold.cluster.saga

import dev.surefold.Clock
import dev.surefold.Metrics
import dev.surefold.actor.ActorRef
import dev.surefold.actor.AskFailure
import dev.surefold.actor.Behaviour
import dev.surefold.actor.Reply
import dev.surefold.actor.ask
import dev.surefold.actor.become
import dev.surefold.actor.behaviour
import dev.surefold.actor.remote.Codecs
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.WireException
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.stay
import dev.surefold.actor.stop
import dev.surefold.clock
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.Sharded
import dev.surefold.cluster.sharding
import dev.surefold.fail.Either
import dev.surefold.fail.fold
import dev.surefold.fail.map
import dev.surefold.metrics
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A durable saga run across a cluster: each saga is an entity of its own, sharded by its id (spec 0299). */
class Sagas<I, E, A>
internal constructor(
    private val saga: Durable<I, E, A>,
    private val sharded: Sharded<SagaCommand>,
) {
    /** Starts the saga [id] with [input], answering once `Started` is journalled; one that has it runs nothing more. */
    fun start(id: String, input: I, within: Duration = 10.seconds): Either<AskFailure, Unit> =
        sharded.entity(id).ask(within) { SagaCommand.Start(saga.input.encode(input), it) }

    /** How the saga [id] stands, waiting up to [within] for it to end; [Durable.Outcome.Running] if it has not. */
    fun await(id: String, within: Duration): Durable.Outcome<E, A> =
        sharded
            .entity(id)
            .ask<SagaCommand, Ending>(within + ANSWER_WITHIN) { SagaCommand.Await(within, it) }
            .fold({ Durable.Outcome.Running }, { it.outcome(saga) })

    /**
     * Sends [value] to the saga [id], waking it if it waits for one: kept while it has started and not ended. A second
     * signal with the same [dedupe] is answered [Delivered.Accepted] and not kept again.
     */
    fun <T> signal(
        id: String,
        signal: SagaSignal<T>,
        value: T,
        dedupe: String,
        within: Duration = 10.seconds,
    ): Either<AskFailure, Delivered<E, A>> =
        delivering(id, InboxRecord(signal.name, dedupe, signal.codec.encode(value)), within)

    /**
     * Calls off the saga [id] for [reason]: kept while it has started and not ended, waking it if it waits, and seen by
     * its body at its next boundary (spec 0317). A second cancel with the same [dedupe] is kept once.
     */
    fun cancel(
        id: String,
        reason: String,
        dedupe: String,
        within: Duration = 10.seconds,
    ): Either<AskFailure, Delivered<E, A>> = delivering(id, InboxRecord(CANCEL, dedupe, reason.toByteArray()), within)

    private fun delivering(id: String, record: InboxRecord, within: Duration): Either<AskFailure, Delivered<E, A>> =
        sharded
            .entity(id)
            .ask<SagaCommand, Ending>(within) { SagaCommand.Signal(record, it) }
            .map { it.delivered(saga) }
}

/**
 * This saga run on [cluster], as one sharded entity per id on whichever member owns it. Every node calls this with the
 * same saga, on its cluster's scope, as it calls `cluster` and `sharding`: a body reads the clock and metrics bound
 * there. An entity has nothing for [passivateAfter] only once its body has stopped.
 */
fun <I, E, A> Durable<I, E, A>.on(cluster: Cluster, passivateAfter: Duration = 10.minutes): Sagas<I, E, A> {
    val waker = cluster.waker()
    val runs = Runs(clock.get(), metrics.get(), waker, passivateAfter / 2)
    val sharded = cluster.sharding("saga-$kind", SagaCodec, passivateAfter) { id -> entity(id, runs) }
    runs.wake = { id -> sharded.entity(id).tell(SagaCommand.Wake) }
    waker.kind(kind, runs.wake)
    return Sagas(this, sharded)
}

/**
 * What every run of a kind's sagas on this node shares: the clock and metrics bound where `on` was called, since a turn
 * runs on a runner that inherits neither, as `Scope.cluster` reads its clock; the waker; and a word to an entity
 * through its region, sent every [keepEvery] while its body runs, so its idle timer never stops it part way.
 */
private class Runs(val time: Clock, val measured: Metrics, val waker: Waker, val keepEvery: Duration) {
    @Volatile lateinit var wake: (id: String) -> Unit

    fun <T> bound(block: () -> T): T = clock.locally(time) { metrics.locally(measured, block) }
}

/** How long past its own wait an `await` gives the answer to cross back. */
private val ANSWER_WITHIN = 2.seconds

/** How a saga stands, as it crosses between nodes: its answer or error still in their codecs' bytes. */
internal sealed interface Ending {
    class Done(val bytes: ByteArray) : Ending

    class Refused(val bytes: ByteArray) : Ending

    data object Running : Ending

    data class Stuck(val reason: String) : Ending

    data object Forgotten : Ending

    /** Called off by `cancel` for [reason], its ended steps undone. */
    data class Cancelled(val reason: String) : Ending

    /** Waiting for a signal until [until]: not ended, so it never answers an `await` and never leaves its node. */
    data class Parked(val until: Instant) : Ending
}

private fun <I, E, A> Ending.outcome(saga: Durable<I, E, A>): Durable.Outcome<E, A> =
    when (this) {
        is Ending.Done -> Durable.Outcome.Done(saga.answer.decode(bytes))
        is Ending.Refused -> Durable.Outcome.Refused(saga.error.decode(bytes))
        Ending.Running -> Durable.Outcome.Running
        is Ending.Stuck -> Durable.Outcome.Stuck(reason)
        Ending.Forgotten -> Durable.Outcome.Forgotten
        is Ending.Parked -> Durable.Outcome.Running
        is Ending.Cancelled -> Durable.Outcome.Cancelled(reason)
    }

/** What a saga's entity handles: the first three from any node, the rest from its own runner and timers. */
internal sealed interface SagaCommand {
    class Start(val input: ByteArray, val reply: Reply<Unit>) : SagaCommand

    class Await(val within: Duration, val reply: Reply<Ending>) : SagaCommand

    /** Drive the saga if nothing here is: the `surefold-sagas` waker's word after a member is removed. */
    data object Wake : SagaCommand

    /** Keep [record] in the saga's inbox, answering as `Sagas.signal` reads it through [Ending.delivered]. */
    class Signal(val record: InboxRecord, val reply: Reply<Ending>) : SagaCommand

    /** A signal was kept: drive the saga if it waits, or again once the run that may have missed it parks. */
    data object Signalled : SagaCommand

    /** A running entity's own timer: word to itself through its region, which counts as not idle. */
    data object Busy : SagaCommand

    /** The runner found `Started` in the journal, or wrote it. */
    data object Begun : SagaCommand

    /** The runner stopped: how the saga ended, or null when another runner overtook it. */
    class Ran(val ending: Ending?) : SagaCommand

    class Expired(val reply: Reply<Ending>) : SagaCommand
}

/** Where a saga's entity is: nothing read yet, a runner driving it, or how that runner left it. */
private sealed interface Phase {
    data object Fresh : Phase

    data class Running(
        val started: Boolean,
        val input: ByteArray?,
        val starting: List<Reply<Unit>> = emptyList(),
        val awaiting: List<Reply<Ending>> = emptyList(),
        val signalled: Boolean = false,
    ) : Phase

    data class Ended(val ending: Ending) : Phase

    /** Waiting for a signal, with no runner: the waker or a signal wakes it, and it may passivate meanwhile. */
    data class Parked(val awaiting: List<Reply<Ending>> = emptyList()) : Phase
}

/**
 * The entity of the saga [id]. Its turns never block: the body runs in a child's turn, which may, and tells the entity
 * how it went. The child stops with the entity and the node's scope, and a second runner, on a node the shard has just
 * left, is stopped by the journal as [Overtaken].
 */
private fun <I, E, A> Durable<I, E, A>.entity(id: String, runs: Runs): Behaviour<SagaCommand, Phase, Nothing> {
    var launched = 0
    return behaviour<SagaCommand, Phase>(Phase.Fresh) { ctx, phase, message ->
        val run = { input: ByteArray?, running: Phase.Running ->
            ctx.spawn("run-${++launched}", runner(id, input, ctx.self, runs)).tell(Go)
            ctx.timers.every(SagaCommand.Busy, runs.keepEvery, SagaCommand.Busy)
            running
        }
        when (message) {
            is SagaCommand.Start -> starting(phase, message, run)

            is SagaCommand.Await -> {
                if (phase !is Phase.Ended) {
                    ctx.timers.after(message.reply, message.within, SagaCommand.Expired(message.reply))
                }
                awaited(phase, message.reply, run)
            }

            is SagaCommand.Signal ->
                stay().also { ctx.spawn("signal-${++launched}", signaller(id, message, ctx.self)).tell(Go) }

            SagaCommand.Wake,
            SagaCommand.Signalled -> woken(phase, run, signalled = message == SagaCommand.Signalled)

            SagaCommand.Busy ->
                stay().also {
                    if (phase is Phase.Running) runs.wake(id) else ctx.timers.cancel(SagaCommand.Busy)
                }

            is SagaCommand.Expired if phase is Phase.Parked && message.reply in phase.awaiting -> {
                message.reply(Ending.Running)
                become(phase.copy(awaiting = phase.awaiting - message.reply))
            }

            SagaCommand.Begun,
            is SagaCommand.Ran,
            is SagaCommand.Expired -> (phase as? Phase.Running)?.let { become(heard(it, message, run)) } ?: stay()
        }
    }
}

/**
 * Where an `await` leaves the entity: answered if the saga ended, else waiting with the others, a run begun if none
 * was.
 */
private fun awaited(phase: Phase, reply: Reply<Ending>, run: (ByteArray?, Phase.Running) -> Phase.Running) =
    when (phase) {
        is Phase.Ended -> stay().also { reply(phase.ending) }
        is Phase.Parked -> become(phase.copy(awaiting = phase.awaiting + reply))
        is Phase.Running -> become(phase.copy(awaiting = phase.awaiting + reply))
        Phase.Fresh -> become(run(null, Phase.Running(false, null, awaiting = listOf(reply))))
    }

/**
 * A `Wake` or a kept signal drives a saga nothing here is driving: one not read yet, or one parked waiting. A running
 * one may have read its inbox before the signal was kept, so a signal has it run again should it park.
 */
private fun woken(phase: Phase, run: (ByteArray?, Phase.Running) -> Phase.Running, signalled: Boolean) =
    when (phase) {
        Phase.Fresh -> become(run(null, Phase.Running(false, null)))
        is Phase.Parked -> become(run(null, Phase.Running(true, null, awaiting = phase.awaiting)))
        is Phase.Running -> if (signalled) become(phase.copy(signalled = true)) else stay()
        is Phase.Ended -> stay()
    }

private fun starting(phase: Phase, start: SagaCommand.Start, run: (ByteArray?, Phase.Running) -> Phase.Running) =
    when (phase) {
        is Phase.Running if phase.started -> stay().also { start.reply(Unit) }

        is Phase.Parked -> stay().also { start.reply(Unit) }

        is Phase.Running ->
            become(phase.copy(input = phase.input ?: start.input, starting = phase.starting + start.reply))

        is Phase.Ended if phase.ending != Ending.Forgotten -> stay().also { start.reply(Unit) }

        Phase.Fresh,
        is Phase.Ended -> become(run(start.input, Phase.Running(false, start.input, listOf(start.reply))))
    }

/** What a running entity does with its runner's and its timers' word. */
private fun heard(phase: Phase.Running, message: SagaCommand, run: (ByteArray?, Phase.Running) -> Phase.Running) =
    when (message) {
        SagaCommand.Begun -> {
            phase.starting.forEach { it(Unit) }
            phase.copy(started = true, starting = emptyList())
        }

        is SagaCommand.Ran -> ran(message.ending, phase, run)

        is SagaCommand.Expired if message.reply in phase.awaiting -> {
            message.reply(Ending.Running)
            phase.copy(awaiting = phase.awaiting - message.reply)
        }

        is SagaCommand.Start,
        is SagaCommand.Await,
        SagaCommand.Wake,
        is SagaCommand.Signal,
        SagaCommand.Signalled,
        SagaCommand.Busy,
        is SagaCommand.Expired -> phase
    }

/** Where a runner's end leaves the entity: a start that came while it found nothing is run now. */
private fun ran(ending: Ending?, phase: Phase.Running, run: (ByteArray?, Phase.Running) -> Phase.Running): Phase {
    if (ending == Ending.Forgotten && phase.input != null) return run(phase.input, phase.copy(started = false))
    if (ending is Ending.Parked) {
        phase.starting.forEach { it(Unit) }
        if (phase.signalled) return run(null, Phase.Running(true, null, awaiting = phase.awaiting))
        return Phase.Parked(phase.awaiting)
    }
    // Overtaken: the other runner wrote `Started`, and the next message here reads what it wrote since.
    val answer = ending ?: Ending.Running
    phase.awaiting.forEach { it(answer) }
    phase.starting.forEach { it(Unit) }
    return if (ending == null) Phase.Fresh else Phase.Ended(ending)
}

/**
 * A child that keeps a signal in the saga [id]'s inbox, answers its sender, tells [entity] if it was kept, and stops.
 */
private fun Durable<*, *, *>.signaller(id: String, signal: SagaCommand.Signal, entity: ActorRef<SagaCommand>) =
    behaviour<Go, Unit>(Unit) { ctx, _, _ ->
        val answer = delivering(ctx.journal, id, signal.record)
        signal.reply(answer)
        if (answer == Ending.Running) entity.tell(SagaCommand.Signalled)
        stop()
    }

/** What starts a runner. */
private data object Go

/** A child that drives the saga [id] in one turn, bound as [runs] says, tells [entity] how it went, and stops. */
private fun <I, E, A> Durable<I, E, A>.runner(
    id: String,
    input: ByteArray?,
    entity: ActorRef<SagaCommand>,
    runs: Runs,
) =
    behaviour<Go, Unit>(Unit) { ctx, _, _ ->
        val ending = runs.bound {
            ending(ctx.journal, id, input, { runs.waker.began(kind, id) }) { entity.tell(SagaCommand.Begun) }
        }
        when (ending) {
            is Ending.Parked -> runs.waker.parked(kind, id, ending.until)
            is Ending.Done,
            is Ending.Refused,
            is Ending.Cancelled,
            Ending.Forgotten -> runs.waker.ended(kind, id)
            null,
            Ending.Running,
            is Ending.Stuck -> Unit
        }
        entity.tell(SagaCommand.Ran(ending))
        stop()
    }

private object EndingCodec : MessageCodec<Ending> {
    override fun write(message: Ending, out: WireOut) =
        when (message) {
            is Ending.Done -> {
                out.int(DONE)
                out.bytes(message.bytes)
            }

            is Ending.Refused -> {
                out.int(REFUSED)
                out.bytes(message.bytes)
            }

            Ending.Running -> out.int(RUNNING)

            is Ending.Stuck -> {
                out.int(STUCK)
                out.string(message.reason)
            }

            Ending.Forgotten -> out.int(FORGOTTEN)

            is Ending.Cancelled -> {
                out.int(CANCELLED)
                out.string(message.reason)
            }

            is Ending.Parked -> error("$message never leaves its node")
        }

    override fun read(input: WireIn): Ending =
        when (val tag = input.int()) {
            DONE -> Ending.Done(input.bytes())
            REFUSED -> Ending.Refused(input.bytes())
            RUNNING -> Ending.Running
            STUCK -> Ending.Stuck(input.string())
            FORGOTTEN -> Ending.Forgotten
            CANCELLED -> Ending.Cancelled(input.string())
            else -> throw WireException("no saga ending has the tag $tag")
        }
}

/** A saga entity's messages on the wire: the input as its codec's bytes, and each reply as an address. */
internal object SagaCodec : MessageCodec<SagaCommand> {
    override fun write(message: SagaCommand, out: WireOut) =
        when (message) {
            is SagaCommand.Start -> {
                out.int(START)
                out.bytes(message.input)
                out.reply(message.reply, Codecs.unit)
            }

            is SagaCommand.Await -> {
                out.int(AWAIT)
                out.long(message.within.inWholeMilliseconds)
                out.reply(message.reply, EndingCodec)
            }

            SagaCommand.Wake -> out.int(WAKE)

            is SagaCommand.Signal -> {
                out.int(SIGNAL)
                out.bytes(message.record.bytes())
                out.reply(message.reply, EndingCodec)
            }

            SagaCommand.Signalled,
            SagaCommand.Busy,
            SagaCommand.Begun,
            is SagaCommand.Ran,
            is SagaCommand.Expired -> error("$message never leaves its node")
        }

    override fun read(input: WireIn): SagaCommand =
        when (val tag = input.int()) {
            START -> SagaCommand.Start(input.bytes(), input.reply(Codecs.unit))
            AWAIT -> SagaCommand.Await(input.long().milliseconds, input.reply(EndingCodec))
            WAKE -> SagaCommand.Wake
            SIGNAL -> SagaCommand.Signal(InboxRecord.read(input.bytes()), input.reply(EndingCodec))
            else -> throw WireException("no saga message has the tag $tag")
        }
}

private const val START = 1
private const val AWAIT = 2
private const val WAKE = 3
private const val SIGNAL = 4
private const val DONE = 1
private const val REFUSED = 2
private const val RUNNING = 3
private const val STUCK = 4
private const val FORGOTTEN = 5
private const val CANCELLED = 6
