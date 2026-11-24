package dev.surefold.cluster.saga

import dev.surefold.actor.InMemoryJournal
import dev.surefold.actor.Journal
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.StateCodec
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow

private object Notes : StateCodec<String> {
    override fun encode(state: String): ByteArray = state.toByteArray()

    override fun decode(bytes: ByteArray): String = String(bytes)
}

private object ApprovedSignal : SagaSignal<String>("approved", Notes)

private object CommentSignal : SagaSignal<String>("comment", Notes)

/** A saga that stops part way, as a node dying would, so its journal holds `Started` and no end. */
private val halted =
    durableSaga("claim", Notes, Notes, Notes) { _ ->
        step("hold", Notes, { _ -> error("the node died") })
        "paid"
    }

private val paying = durableSaga("claim", Notes, Notes, Notes) { claim -> "paid $claim" }

private val refusing = durableSaga("claim", Notes, Notes, Notes) { _ -> fail("overdrawn") }

private fun Journal.halt(id: String) = shouldThrow<IllegalStateException> { halted.run(this, id, "c") }

private fun Journal.inbox(id: String): List<String> =
    read(PersistenceId("claim-signals", id)).map { InboxRecord.read(it.bytes) }.map { "${it.name}:${String(it.value)}" }

object InboxSuite : Suite() {
    init {
        test("a signal to a saga that has started and not ended is Accepted and kept in its inbox") {
            val journal = InMemoryJournal()
            journal.halt("c-1")

            halted.signal(journal, "c-1", ApprovedSignal, "ops", dedupe = "t-1") shouldBe Delivered.Accepted

            journal.inbox("c-1") shouldBe listOf("approved:ops")
        }

        test("a repeat with the same dedupe is Accepted again and kept once") {
            val journal = InMemoryJournal()
            journal.halt("c-1")
            halted.signal(journal, "c-1", ApprovedSignal, "ops", dedupe = "t-1")

            halted.signal(journal, "c-1", ApprovedSignal, "ops again", dedupe = "t-1") shouldBe Delivered.Accepted

            journal.inbox("c-1") shouldBe listOf("approved:ops")
        }

        test("signals are kept in the order they came, whatever their names") {
            val journal = InMemoryJournal()
            journal.halt("c-1")

            halted.signal(journal, "c-1", ApprovedSignal, "ops", dedupe = "t-1")
            halted.signal(journal, "c-1", CommentSignal, "looks fine", dedupe = "t-2")
            halted.signal(journal, "c-1", ApprovedSignal, "finance", dedupe = "t-3")

            journal.inbox("c-1") shouldBe listOf("approved:ops", "comment:looks fine", "approved:finance")
        }

        test("a signal to a saga that finished answers how it ended, and keeps nothing") {
            val journal = InMemoryJournal()
            paying.run(journal, "c-2", "c")

            paying.signal(journal, "c-2", ApprovedSignal, "ops", dedupe = "t-1") shouldBe
                Delivered.Ended(Durable.Outcome.Done("paid c"))
            journal.inbox("c-2") shouldBe emptyList()
        }

        test("a signal to a saga that was refused answers its error, and keeps nothing") {
            val journal = InMemoryJournal()
            refusing.run(journal, "c-3", "c")

            refusing.signal(journal, "c-3", ApprovedSignal, "ops", dedupe = "t-1") shouldBe
                Delivered.Ended(Durable.Outcome.Refused("overdrawn"))
            journal.inbox("c-3") shouldBe emptyList()
        }

        test("a signal to an id with nothing journalled is Unknown, and keeps nothing") {
            val journal = InMemoryJournal()

            paying.signal(journal, "c-4", ApprovedSignal, "ops", dedupe = "t-1") shouldBe Delivered.Unknown
            journal.inbox("c-4") shouldBe emptyList()
        }
    }
}
