package dev.surefold.cluster.saga

import dev.surefold.Settled
import dev.surefold.Shared
import dev.surefold.TestClock
import dev.surefold.Undo
import dev.surefold.actor.InMemoryJournal
import dev.surefold.actor.Journal
import dev.surefold.actor.JournalConflict
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.StateCodec
import dev.surefold.assert.shouldThrow
import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import java.time.Instant
import kotlin.time.Duration.Companion.days

private object Strings : StateCodec<String> {
    override fun encode(state: String): ByteArray = state.toByteArray()

    override fun decode(bytes: ByteArray): String = String(bytes)
}

private object Go : SagaSignal<String>("go", Strings)

/** A ledger that applies each key once, as a keyed entity would, and remembers why each undo ran. */
private class Holds {
    val applied = Shared(emptyMap<String, String>())
    val why = Shared(emptyList<Undo<String>>())

    fun apply(key: String, what: String): String {
        applied.update { if (key in it) it else it + (key to what) }
        return applied.peek().getValue(key)
    }

    fun find(key: String): Settled<String> = applied.peek()[key]?.let { Settled.Happened(it) } ?: Settled.DidNot
}

/** Holds, waits for a go-ahead, and pays; [during] runs inside the hold, as a cancel arriving mid-step would. */
private fun payout(holds: Holds, during: () -> Unit = {}) =
    durableSaga("payout", Strings, Strings, Strings) { claim ->
        step(
            "hold",
            Strings,
            { key -> holds.apply(key, "hold $claim").also { during() } },
            holds::find,
        ) { key, _, why ->
            holds.why.update { it + why }
            holds.apply(key, "release $claim")
        }
        receive(Go, within = 3.days) ?: fail("no go")
        step("pay", Strings, { key -> holds.apply(key, "pay $claim") }, holds::find)
    }

private class NodeGone : RuntimeException("the node died")

/** Dies, as a node would, instead of the append numbered [dieAt]. */
private class Dies(private val inner: Journal, private val dieAt: Int) : Journal by inner {
    private var appends = 0

    override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>): Either<JournalConflict, Long> {
        if (++appends == dieAt) throw NodeGone()
        return inner.append(id, expected, events)
    }
}

private val start: Instant = Instant.parse("2026-11-24T09:00:00Z")

object CancelSuite : Suite() {
    init {
        test("a cancel that arrives during a step undoes it at the next boundary, and the saga ends called off") {
            val holds = Holds()
            val journal = InMemoryJournal()
            lateinit var saga: Durable<String, String, String>
            saga = payout(holds) { saga.cancel(journal, "c", "customer withdrew", dedupe = "ops-1") }

            clock.locally(TestClock(start)) {
                shouldThrow<CalledOff> { saga.run(journal, "c", "claim-81") }.reason shouldBe "customer withdrew"
                shouldThrow<CalledOff> { saga.resume(journal, "c") }.reason shouldBe "customer withdrew"
            }

            holds.applied.peek() shouldBe mapOf("c/hold" to "hold claim-81", "c/hold/undo" to "release claim-81")
            holds.why.peek().single().let { (it as Undo.Cancelled).interrupt.message } shouldBe "customer withdrew"
        }

        test("a saga parked in receive is called off when resumed after a cancel") {
            val holds = Holds()
            val journal = InMemoryJournal()
            val saga = payout(holds)
            clock.locally(TestClock(start)) {
                shouldThrow<Waiting> { saga.run(journal, "c", "claim-81") }

                saga.cancel(journal, "c", "customer withdrew", dedupe = "ops-1") shouldBe Delivered.Accepted
                shouldThrow<CalledOff> { saga.resume(journal, "c") }
            }

            holds.applied.peek() shouldBe mapOf("c/hold" to "hold claim-81", "c/hold/undo" to "release claim-81")
        }

        test("a cancel is never taken by receive, and a go-ahead after it changes nothing") {
            val holds = Holds()
            val journal = InMemoryJournal()
            val saga = payout(holds)
            clock.locally(TestClock(start)) {
                shouldThrow<Waiting> { saga.run(journal, "c", "claim-81") }
                saga.cancel(journal, "c", "customer withdrew", dedupe = "ops-1")
                saga.signal(journal, "c", Go, "ops", dedupe = "t-1")

                shouldThrow<CalledOff> { saga.resume(journal, "c") }
            }
            holds.applied.peek().keys shouldBe setOf("c/hold", "c/hold/undo")
        }

        test("a saga already undoing a refusal ignores a cancel and ends refused") {
            val holds = Holds()
            val journal = InMemoryJournal()
            lateinit var saga: Durable<String, String, String>
            saga =
                durableSaga("payout", Strings, Strings, Strings) { claim ->
                    step("hold", Strings, { key -> holds.apply(key, "hold $claim") }, holds::find) { key, _, _ ->
                        saga.cancel(journal, "c", "too late", dedupe = "ops-1")
                        holds.apply(key, "release $claim")
                    }
                    fail("overdrawn")
                }

            saga.run(journal, "c", "claim-81") shouldBe "overdrawn".left()
            saga.resume(journal, "c") shouldBe "overdrawn".left()
        }

        test("a cancel answers like a signal: Ended for a saga that ended, Unknown for one never started") {
            val journal = InMemoryJournal()
            val holds = Holds()
            val saga = payout(holds)
            clock.locally(TestClock(start)) {
                shouldThrow<Waiting> { saga.run(journal, "c", "claim-81") }
                saga.cancel(journal, "c", "customer withdrew", dedupe = "ops-1")
                shouldThrow<CalledOff> { saga.resume(journal, "c") }
            }

            saga.cancel(journal, "c", "again", dedupe = "ops-2") shouldBe
                Delivered.Ended(Durable.Outcome.Cancelled("customer withdrew"))
            saga.cancel(journal, "never", "nobody", dedupe = "ops-3") shouldBe Delivered.Unknown
        }

        test("a node that dies at any append after the cancel leaves a saga called off with its hold released once") {
            for (dieAt in 1..6) {
                val holds = Holds()
                val journal = InMemoryJournal()
                val dying = Dies(journal, dieAt)
                lateinit var saga: Durable<String, String, String>
                saga = payout(holds) { saga.cancel(journal, "c", "customer withdrew", dedupe = "ops-1") }
                clock.locally(TestClock(start)) {
                    // Started, the hold begun and ended, Cancelling, the hold's undo, and Cancelled: six appends.
                    shouldThrow<NodeGone> { saga.run(dying, "c", "claim-81") }
                    shouldThrow<CalledOff> { saga.run(dying, "c", "claim-81") }
                }
                holds.applied.peek() shouldBe mapOf("c/hold" to "hold claim-81", "c/hold/undo" to "release claim-81")
            }
        }

        test("a signal may not take a name Surefold keeps for itself") {
            shouldThrow<IllegalArgumentException> { SagaSignal("surefold:cancel", Strings) }.message shouldBe
                "surefold:cancel: a signal's name may not start with surefold:"
        }
    }
}
