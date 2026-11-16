package dev.surefold.actor.remote.philosophers

import dev.surefold.Shared
import dev.surefold.actor.ActorRef
import dev.surefold.actor.Reply
import dev.surefold.actor.become
import dev.surefold.actor.behaviour
import dev.surefold.actor.onStart
import dev.surefold.actor.remote.Codecs
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.stay
import dev.surefold.actor.stop
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

val NAMES = listOf("plato", "kant", "hume", "locke", "hegel")
val SEATS = NAMES.size

// ── what crosses the wire ─────────────────────────────────────────────────────────────

sealed interface ForkMsg

data class Take(val who: String, val replyTo: ActorRef<DinerMsg>) : ForkMsg

data class Put(val who: String) : ForkMsg

data class Status(val reply: Reply<String>) : ForkMsg

sealed interface DinerMsg

data class Granted(val fork: Int) : DinerMsg

data object Hungry : DinerMsg

data object Reach : DinerMsg

data object Full : DinerMsg

object DinerCodec : MessageCodec<DinerMsg> {
    override fun write(message: DinerMsg, out: WireOut) =
        if (message is Granted) out.int(message.fork) else error("only Granted crosses the wire")

    override fun read(input: WireIn): DinerMsg = Granted(input.int())
}

object ForkCodec : MessageCodec<ForkMsg> {
    override fun write(message: ForkMsg, out: WireOut) =
        when (message) {
            is Take -> {
                out.int(1)
                out.string(message.who)
                out.ref(message.replyTo, DinerCodec)
            }
            is Put -> {
                out.int(2)
                out.string(message.who)
            }
            is Status -> {
                out.int(3)
                out.reply(message.reply, Codecs.string)
            }
        }

    override fun read(input: WireIn): ForkMsg =
        when (val tag = input.int()) {
            1 -> Take(input.string(), input.ref(DinerCodec))
            2 -> Put(input.string())
            3 -> Status(input.reply(Codecs.string))
            else -> error("no fork message has tag $tag")
        }
}

// ── a fork: hands itself to one philosopher at a time, and queues the rest ──────────────

data class ForkState(
    val holder: String? = null,
    val queue: List<Take> = emptyList(),
    val handedOut: Int = 0,
    val misuse: Int = 0,
) {
    override fun toString() =
        "held by ${holder ?: "nobody"}" + (if (queue.isEmpty()) "" else ", ${queue.joinToString { it.who }} waiting")
}

fun fork(n: Int) =
    behaviour<ForkMsg, ForkState>(ForkState()) { _, s, msg ->
        when (msg) {
            is Take ->
                if (s.holder == null) {
                    msg.replyTo.tell(Granted(n))
                    become(s.copy(holder = msg.who, handedOut = s.handedOut + 1))
                } else {
                    become(s.copy(queue = s.queue + msg))
                }

            is Put -> {
                val misuse = s.misuse + if (msg.who != s.holder) 1 else 0 // only the holder may put a fork down
                val next = s.queue.firstOrNull()
                next?.replyTo?.tell(Granted(n))
                become(ForkState(next?.who, s.queue.drop(1), s.handedOut + if (next != null) 1 else 0, misuse))
            }

            is Status -> stay().also { msg.reply("$s") }
        }
    }

// ── a philosopher: think, take two forks, eat, put them back ───────────────────────────

sealed interface Phase

data object Thinking : Phase

data class Holding(val forks: Set<Int>) : Phase

data object Eating : Phase

data class Diner(val phase: Phase = Thinking, val meals: Int = 0)

/**
 * With [ordered], everyone takes the lower-numbered fork first, so no cycle of waiting can form. Without it, everyone
 * takes the left fork first, and a table where all five do so at once is stuck for good.
 */
fun philosopher(seat: Int, forks: List<ActorRef<ForkMsg>>, seen: Seen, meals: Int, ordered: Boolean, seed: Long) =
    behaviour<DinerMsg, Diner>(Diner()) { ctx, d, msg ->
            val random = Random(seed * 31 + seat * 7 + d.meals)
            val (left, right) = seat to (seat + 1) % SEATS
            val (first, second) = if (ordered) minOf(left, right) to maxOf(left, right) else left to right
            when (msg) {
                Hungry -> {
                    forks[first].tell(Take(NAMES[seat], ctx.self))
                    become(d.copy(phase = Holding(emptySet())))
                }

                is Granted -> {
                    val held = (d.phase as Holding).forks + msg.fork
                    if (held.size == 1) {
                        ctx.timers.after("reach", 30.milliseconds, Reach) // a moment before the other hand moves
                        become(d.copy(phase = Holding(held)))
                    } else {
                        seen.starts(seat)
                        ctx.timers.after("eat", random.nextLong(20, 60).milliseconds, Full)
                        become(d.copy(phase = Eating))
                    }
                }

                Reach -> stay().also { forks[second].tell(Take(NAMES[seat], ctx.self)) }

                Full -> {
                    seen.ends(seat) // before the forks go back
                    forks[left].tell(Put(NAMES[seat]))
                    forks[right].tell(Put(NAMES[seat]))
                    if (d.meals + 1 == meals) {
                        stop()
                    } else {
                        ctx.timers.after("think", random.nextLong(10, 40).milliseconds, Hungry)
                        become(Diner(Thinking, d.meals + 1))
                    }
                }
            }
        }
        .onStart { ctx -> ctx.timers.after("think", 1.milliseconds, Hungry) } // everyone sits down hungry at once

// ── what the test sees: who eats when, written as it happens rather than told ─────────

/**
 * Each philosopher writes here as it starts eating and before it puts its forks back, so a neighbour granted a fork can
 * only start after the write that ended the meal. A message to an observer could be overtaken; this cannot.
 */
class Seen(private val now: () -> Long) {
    private data class Table(
        val eating: Set<Int> = emptySet(),
        val meals: List<List<Pair<Long, Long>>> = List(SEATS) { emptyList() },
        val mostAtOnce: Int = 0,
        val clashes: Int = 0,
    )

    private val table = Shared(Table(), "seen")

    val mostAtOnce: Int
        get() = table.peek().mostAtOnce

    val clashes: Int
        get() = table.peek().clashes

    fun starts(seat: Int) {
        val at = now()
        table.update { t ->
            val clash = t.eating.any { it == (seat + 1) % SEATS || it == (seat + SEATS - 1) % SEATS }
            val eating = t.eating + seat
            Table(
                eating,
                t.meals.mapIndexed { i, m -> if (i == seat) m + (at to -1L) else m },
                maxOf(t.mostAtOnce, eating.size),
                t.clashes + if (clash) 1 else 0,
            )
        }
    }

    fun ends(seat: Int) {
        val at = now()
        table.update { t ->
            t.copy(
                eating = t.eating - seat,
                meals = t.meals.mapIndexed { i, m -> if (i == seat) m.dropLast(1) + (m.last().first to at) else m },
            )
        }
    }

    fun meals(): List<List<Pair<Long, Long>>> = table.peek().meals
}
