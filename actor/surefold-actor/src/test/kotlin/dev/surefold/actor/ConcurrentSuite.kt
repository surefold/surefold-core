package dev.surefold.actor

import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeGreaterThan
import dev.surefold.scope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private sealed interface Ledger

private data class Credit(val pence: Long) : Ledger

/** Exclusive read: waits behind Concurrent turns (spec 0319). */
private data class HowMuch(val reply: Reply<Long>) : Ledger

private data class SlowHowMuch(
    val began: CountDownLatch,
    val gate: CountDownLatch,
    val reply: Reply<Long>,
) : Ledger, Concurrent

private data class BlockedCredit(val began: CountDownLatch, val gate: CountDownLatch) : Ledger

private data class BecomeOnRead(val reply: Reply<Long>) : Ledger, Concurrent

private fun ledger() =
    behaviour<Ledger, Long>(0L) { _, balance, message ->
        when (message) {
            is Credit -> become(balance + message.pence)
            is HowMuch -> stay().also { message.reply(balance) }
            is SlowHowMuch -> {
                message.began.countDown()
                message.gate.await()
                stay().also { message.reply(balance) }
            }
            is BlockedCredit -> {
                message.began.countDown()
                message.gate.await()
                become(balance + 1)
            }
            is BecomeOnRead -> become(balance).also { message.reply(balance) }
        }
    }

object ConcurrentSuite : Suite() {
    init {
        test("two Concurrent asks overlap while an exclusive message waits") {
            val began = CountDownLatch(2)
            val gate = CountDownLatch(1)
            val overlapped = AtomicInteger()
            val answers = scope {
                val books = spawn("books", entities(passivateAfter = 10.minutes) { _ -> ledger() })
                val a = books.entity("a")
                a.tell(Credit(10))
                awaitIdle()
                val first =
                    Thread.ofVirtual().start {
                        a.ask(1.minutes) { SlowHowMuch(began, gate, it) }
                    }
                val second =
                    Thread.ofVirtual().start {
                        a.ask(1.minutes) { SlowHowMuch(began, gate, it) }
                    }
                began.await(5, TimeUnit.SECONDS) shouldBe true
                overlapped.set(2)
                val exclusiveBegan = CountDownLatch(1)
                val exclusiveGate = CountDownLatch(1)
                val writer =
                    Thread.ofVirtual().start {
                        a.tell(BlockedCredit(exclusiveBegan, exclusiveGate))
                    }
                Thread.sleep(50)
                exclusiveBegan.count shouldBe 1
                gate.countDown()
                first.join()
                second.join()
                exclusiveBegan.await(5, TimeUnit.SECONDS) shouldBe true
                exclusiveGate.countDown()
                writer.join()
                a.ask(1.seconds) { HowMuch(it) }
            }

            overlapped.get() shouldBeGreaterThan 0
            answers shouldBe 11L.right()
        }

        test("a Concurrent turn that becomes fails the entity") {
            val answer = scope {
                val books = spawn("books", entities(passivateAfter = 10.minutes) { _ -> ledger() })
                val a = books.entity("a")
                a.tell(
                    BecomeOnRead(
                        object : Reply<Long> {
                            override val address = a.address

                            override fun invoke(answer: Long) {}
                        },
                    ),
                )
                Thread.sleep(100)
                a.ask(1.seconds) { HowMuch(it) }
            }

            answer shouldBe AskFailure.Stopped.left()
        }
    }
}
