package dev.surefold.cluster.saga

import dev.surefold.TestClock
import dev.surefold.actor.InMemoryJournal
import dev.surefold.actor.Journal
import dev.surefold.actor.JournalConflict
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.StateCodec
import dev.surefold.assert.shouldThrow
import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import java.time.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

private object Lines : StateCodec<String> {
    override fun encode(state: String): ByteArray = state.toByteArray()

    override fun decode(bytes: ByteArray): String = String(bytes)
}

private object Approval : SagaSignal<String>("approval", Lines)

private object Remark : SagaSignal<String>("remark", Lines)

private val start: Instant = Instant.parse("2026-11-24T09:00:00Z")

private val approving =
    durableSaga("payout", Lines, Lines, Lines) { claim ->
        val by = receive(Approval, within = 3.days) ?: fail("not approved")
        "paid $claim, approved by $by"
    }

private val twice =
    durableSaga("payout", Lines, Lines, Lines) { _ ->
        val first = receive(Approval, within = 1.days)
        val second = receive(Approval, within = 1.days)
        "$first then $second"
    }

private class NodeDied : RuntimeException("the node died")

/** Dies, as a node would, instead of the append numbered [dieAt]. */
private class DiesAtAppend(private val inner: Journal, private val dieAt: Int) : Journal by inner {
    private var appends = 0

    override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>): Either<JournalConflict, Long> {
        if (++appends == dieAt) throw NodeDied()
        return inner.append(id, expected, events)
    }
}

private fun TestClock.later(by: kotlin.time.Duration) = setTime(start.plus(by.toJavaDuration()))

object ReceiveSuite : Suite() {
    init {
        test("a body with nothing to take stops Waiting until the deadline it journalled when it first asked") {
            val journal = InMemoryJournal()
            val time = TestClock(start)
            clock.locally(time) {
                shouldThrow<Waiting> { approving.run(journal, "c", "c") }.until shouldBe
                    start.plus(3.days.toJavaDuration())

                time.later(1.hours)
                shouldThrow<Waiting> { approving.resume(journal, "c") }.until shouldBe
                    start.plus(3.days.toJavaDuration())
            }
        }

        test("a saga resumed after a signal takes it") {
            val journal = InMemoryJournal()
            val time = TestClock(start)
            clock.locally(time) {
                shouldThrow<Waiting> { approving.run(journal, "c", "c") }
                approving.signal(journal, "c", Approval, "ops", dedupe = "t-1")
                time.later(1.hours)

                approving.resume(journal, "c") shouldBe "paid c, approved by ops".right()
            }
        }

        test("a saga resumed past its deadline gets null, and the body decides what that means") {
            val journal = InMemoryJournal()
            val time = TestClock(start)
            clock.locally(time) {
                shouldThrow<Waiting> { approving.run(journal, "c", "c") }
                time.later(3.days)

                approving.resume(journal, "c") shouldBe "not approved".left()
            }
        }

        test("a signal that arrives after the timeout was journalled changes nothing on replay") {
            val journal = InMemoryJournal()
            val time = TestClock(start)
            clock.locally(time) {
                shouldThrow<Waiting> { approving.run(journal, "c", "c") }
                time.later(3.days)
                // The timeout is journalled, then the node dies before the undo begins.
                shouldThrow<NodeDied> { approving.resume(DiesAtAppend(journal, 2), "c") }

                approving.signal(journal, "c", Approval, "ops", dedupe = "t-1") shouldBe Delivered.Accepted
                approving.resume(journal, "c") shouldBe "not approved".left()
            }
        }

        test("a signal and a passed deadline both there when the body asks: the signal is taken") {
            val journal = InMemoryJournal()
            val time = TestClock(start)
            clock.locally(time) {
                shouldThrow<Waiting> { approving.run(journal, "c", "c") }
                approving.signal(journal, "c", Approval, "ops", dedupe = "t-1")
                time.later(4.days)

                approving.resume(journal, "c") shouldBe "paid c, approved by ops".right()
                approving.resume(journal, "c") shouldBe "paid c, approved by ops".right()
            }
        }

        test("each receive takes the next signal of its name in inbox order, and one that timed out takes none") {
            val journal = InMemoryJournal()
            val time = TestClock(start)
            clock.locally(time) {
                shouldThrow<Waiting> { twice.run(journal, "c", "c") }
                time.later(1.days)
                shouldThrow<Waiting> { twice.resume(journal, "c") }.until shouldBe start.plus(2.days.toJavaDuration())

                twice.signal(journal, "c", Remark, "looks fine", dedupe = "t-1")
                twice.signal(journal, "c", Approval, "ops", dedupe = "t-2")

                twice.resume(journal, "c") shouldBe "null then ops".right()
            }
        }

        test("two signals already in the inbox are taken in the order they came") {
            val journal = InMemoryJournal()
            clock.locally(TestClock(start)) {
                shouldThrow<Waiting> { twice.run(journal, "c", "c") }
                twice.signal(journal, "c", Approval, "ops", dedupe = "t-1")
                twice.signal(journal, "c", Remark, "looks fine", dedupe = "t-2")
                twice.signal(journal, "c", Approval, "finance", dedupe = "t-3")

                twice.resume(journal, "c") shouldBe "ops then finance".right()
            }
        }

        test("a node that dies at any append leaves a saga that ends as it would have without a crash") {
            for (dieAt in 1..4) {
                val journal = InMemoryJournal()
                val dying = DiesAtAppend(journal, dieAt)
                clock.locally(TestClock(start)) {
                    // Started and the deadline are the first two appends; the signal taken and the answer the last two.
                    if (dieAt <= 2) shouldThrow<NodeDied> { approving.run(dying, "c", "c") }
                    shouldThrow<Waiting> { approving.run(dying, "c", "c") }
                    approving.signal(journal, "c", Approval, "ops", dedupe = "t-1")
                    if (dieAt > 2) shouldThrow<NodeDied> { approving.resume(dying, "c") }

                    approving.resume(dying, "c") shouldBe "paid c, approved by ops".right()
                    journal.read(PersistenceId("payout", "c")).size shouldBe 4
                }
            }
        }
    }
}
