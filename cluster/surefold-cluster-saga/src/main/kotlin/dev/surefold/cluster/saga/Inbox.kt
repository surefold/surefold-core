package dev.surefold.cluster.saga

import dev.surefold.actor.Journal
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.StateCodec
import dev.surefold.fail.fold
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** A message, named [name], that a durable saga's body waits for (spec 0316). */
open class SagaSignal<T>(val name: String, val codec: StateCodec<T>)

/** What became of a signal sent to a saga. */
sealed interface Delivered<out E, out A> {
    /** In the inbox of a saga that has not ended; whether its body takes it is the body's. */
    data object Accepted : Delivered<Nothing, Nothing>

    /** Too late: the saga ended, `Done` or `Refused`, and the signal was not kept. */
    data class Ended<out E, out A>(val outcome: Durable.Outcome<E, A>) : Delivered<E, A>

    /** Nothing is journalled for the id, and the signal was not kept. */
    data object Unknown : Delivered<Nothing, Nothing>
}

/**
 * Sends [value] to the saga [id] on [journal]: kept in its inbox while it has started and not ended. A second signal
 * with the same [dedupe] is answered [Delivered.Accepted] and not kept again.
 */
fun <I, E, A, T> Durable<I, E, A>.signal(
    journal: Journal,
    id: String,
    signal: SagaSignal<T>,
    value: T,
    dedupe: String,
): Delivered<E, A> {
    val inbox = inboxOf(kind, id)
    val kept = journal.read(inbox)
    if (kept.any { InboxRecord.read(it.bytes).dedupe == dedupe }) return Delivered.Accepted
    standing(journal, id)?.let {
        return it
    }
    val record = InboxRecord(signal.name, dedupe, signal.codec.encode(value))
    return journal
        .append(inbox, kept.lastOrNull()?.sequence ?: 0, listOf(record.bytes()))
        .fold({ signal(journal, id, signal, value, dedupe) }, { Delivered.Accepted })
}

/** The saga [id]'s inbox: a persistence id of its own, so a signal never overtakes the saga's runner. */
internal fun inboxOf(kind: String, id: String) = PersistenceId("$kind-signals", id)

/** One signal as its inbox holds it. */
internal class InboxRecord(val name: String, val dedupe: String, val value: ByteArray) {
    fun bytes(): ByteArray =
        ByteArrayOutputStream()
            .also { out ->
                DataOutputStream(out).use { data ->
                    data.writeUTF(name)
                    data.writeUTF(dedupe)
                    data.write(value)
                }
            }
            .toByteArray()

    companion object {
        fun read(bytes: ByteArray): InboxRecord =
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                InboxRecord(data.readUTF(), data.readUTF(), data.readAllBytes())
            }
    }
}
