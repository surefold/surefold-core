package dev.surefold.actor

import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import dev.surefold.scope
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.minutes

private data class Ping(val reply: Reply<Int>)

object AroundSuite : Suite() {
    init {
        test("around wraps each turn, and a throw from it fails the actor") {
            val log = ConcurrentLinkedQueue<String>()
            val wrapped =
                behaviour<Ping, Int>(0) { _, n, message ->
                        message.reply(n + 1)
                        become(n + 1)
                    }
                    .around(
                        message = { _, run ->
                            log += "before"
                            run()
                            log += "after"
                        },
                    )
                    .test("counter")

            wrapped.ask(1.minutes) { Ping(it) } shouldBe 1.right()
            log.toList() shouldContainExactly listOf("before", "after")
        }

        test("an around that throws fails the actor as a turn would") {
            val answer = scope {
                val wrapped =
                    spawn(
                        "counter",
                        behaviour<Ping, Int>(0) { _, n, message ->
                                message.reply(n)
                                stay()
                            }
                            .around(message = { _, _ -> error("boom") }),
                    )
                wrapped.ask(1.minutes) { Ping(it) }
            }

            answer shouldBe AskFailure.Stopped.left()
        }
    }
}
