package dev.surefold.app.compiler

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldNotContain
import dev.surefold.junit.withClue

/** What a step may not do inside `transact`, and what it may do once it commits (spec 0210), compiled in-process. */
object TransactSuite : Suite() {
    init {
        test("I-O, blocking and forks inside a step are errors, each named") {
            val result = compile(EFFECTS_IN_A_STEP)

            result.succeeded shouldBe false
            listOf(
                    "FileInputStream(..)",
                    "Socket(..)",
                    "DriverManager.getConnection",
                    "Thread.sleep",
                    "TimeUnit.sleep",
                    "Future.get",
                    "Deferred.await",
                    "Lock.lock",
                    "synchronized",
                    "parMap",
                    "parZip",
                    "raceN",
                    "scope",
                    "Scope.async",
                    "readText",
                    "writeText",
                    "readln",
                )
                .forEach { call -> result.output shouldContain "$call runs again each time the step does" }
            withClue("kotlin.io.encoding only computes, so Base64 is left alone") {
                result.output shouldNotContain "Base64"
            }
            withClue("a helper taking Transaction, and a firstOf branch, are inside the step too") {
                result.output.split("Thread.sleep runs again").size shouldBe 4
            }
        }

        test("a write to state from outside a step, and peek, are errors inside it") {
            val result = compile(CAPTURES)

            result.succeeded shouldBe false
            result.output shouldContain "count is a var from outside the step, written again each time the step runs"
            result.output shouldContain "sold is a var from outside the step, written again each time the step runs"
            result.output shouldContain "total is a var from outside the step, written again each time the step runs"
            result.output shouldContain "peek reads outside the step; read it with get()"
            withClue("a var declared inside the step, or inside a helper taking Transaction, is the step's own") {
                result.output shouldNotContain "mine is a var"
                result.output shouldNotContain "tally is a var"
            }
            withClue("a property of an object the step was handed is left alone") {
                result.output shouldNotContain "seen is a var"
            }
        }

        test("Surefold's own effects, and printing, inside a step are errors that point at afterCommit") {
            val result = compile(SUREFOLD_EFFECTS)

            result.succeeded shouldBe false
            listOf(
                    "logInfo",
                    "Logger.log",
                    "Counter.increment",
                    "Gauge.set",
                    "Histogram.record",
                    "Hub.publish",
                    "println",
                    "print",
                )
                .forEach { call ->
                    result.output shouldContain
                        "$call runs again each time the step does; once the step commits, run it in afterCommit { }"
                }
            result.output shouldContain
                "ActorRef.tell runs again each time the step does; once the step commits, " +
                    "tell it with tellAfterCommit(ref, message)"
        }

        test("what an afterCommit block does is left alone, since it runs once the step commits") {
            val alone = compile(AFTER_COMMIT)
            alone.succeeded shouldBe true
            alone.output shouldNotContain "the step does"

            withClue("a transact inside afterCommit is a step of its own, and is checked again") {
                val nested = compile(AFTER_COMMIT.replace("Thread.sleep(1)", "transact { Thread.sleep(1) }"))
                nested.succeeded shouldBe false
                nested.output shouldContain "Thread.sleep runs again each time"
            }
        }

        test("the same calls outside a step are left alone") {
            val result = compile(EFFECTS_OUTSIDE_A_STEP)

            result.succeeded shouldBe true
            result.output shouldNotContain "runs again each time the step does"
        }
    }
}

/** Every kind of call the step checker refuses, inside transact, a firstOf branch and a Transaction helper. */
private const val EFFECTS_IN_A_STEP =
    """
import dev.surefold.Deferred
import dev.surefold.Shared
import dev.surefold.Transaction
import dev.surefold.parMap
import dev.surefold.parZip
import dev.surefold.raceN
import dev.surefold.scope
import dev.surefold.transact
import java.io.FileInputStream
import java.net.Socket
import java.sql.DriverManager
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.io.File
import java.nio.file.Path
import kotlin.io.encoding.Base64
import kotlin.io.path.writeText

val seats = Shared(10)
val guard = ReentrantLock()

fun Transaction.take() {
    Thread.sleep(1)
    seats.update { it - 1 }
}

fun effects(future: Future<Int>, deferred: Deferred<Int>) = transact {
    FileInputStream("seats.txt")
    Socket("box-office", 80)
    DriverManager.getConnection("jdbc:h2:mem:")
    Thread.sleep(1)
    TimeUnit.MILLISECONDS.sleep(1)
    future.get()
    deferred.await()
    guard.lock()
    synchronized(guard) { seats.get() }
    parMap(listOf(1)) { it }
    parZip({ 1 }, { 2 }) { a, b -> a + b }
    raceN({ 1 }, { 2 })
    scope { async { 1 }.await() }
    firstOf({ Thread.sleep(1) }, { take() })
    File("seats.txt").readText()
    Path.of("seats.txt").writeText("10")
    readln()
    Base64.encode(byteArrayOf(1))
}
"""

/** The same calls, in a function that is not a step. */
private const val EFFECTS_OUTSIDE_A_STEP =
    """
import dev.surefold.Deferred
import dev.surefold.Shared
import dev.surefold.parMap
import dev.surefold.parZip
import dev.surefold.raceN
import dev.surefold.scope
import dev.surefold.transact
import java.io.FileInputStream
import java.net.Socket
import java.sql.DriverManager
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

val seats = Shared(10)
val guard = ReentrantLock()

fun effects(future: Future<Int>, deferred: Deferred<Int>) {
    FileInputStream("seats.txt")
    Socket("box-office", 80)
    DriverManager.getConnection("jdbc:h2:mem:")
    Thread.sleep(1)
    TimeUnit.MILLISECONDS.sleep(1)
    future.get()
    deferred.await()
    guard.lock()
    synchronized(guard) { seats.peek() }
    parMap(listOf(1)) { it }
    parZip({ 1 }, { 2 }) { a, b -> a + b }
    raceN({ 1 }, { 2 })
    scope { async { 1 }.await() }
    println(seats.peek())
    transact { seats.update { it - 1 } }
}
"""

/** Writes to state from outside a step, a peek, and the writes a step owns or cannot judge. */
private const val CAPTURES =
    """
import dev.surefold.Shared
import dev.surefold.Transaction
import dev.surefold.transact

var total = 0

class Order { var seen = 0 }

class BoxOffice {
    private val free = Shared(10)
    private var sold = 0

    fun hold(order: Order): Int {
        var count = 0
        transact {
            count += 1
            sold++
            total = free.peek()
            var mine = free.get()
            mine -= 1
            free.set(mine)
            order.seen = mine
        }
        return count
    }
}

fun Transaction.counted(free: Shared<Int>): Int {
    var tally = 0
    tally += free.get()
    return tally
}
"""

/** Surefold's own effects, and printing, each the kind a step wants to happen once it has committed. */
private const val SUREFOLD_EFFECTS =
    """
import dev.surefold.Counter
import dev.surefold.Gauge
import dev.surefold.Histogram
import dev.surefold.LogLine
import dev.surefold.Logger
import dev.surefold.Shared
import dev.surefold.actor.ActorRef
import dev.surefold.logInfo
import dev.surefold.stream.Hub
import dev.surefold.transact

val free = Shared(10)

fun hold(
    log: Logger,
    line: LogLine,
    sold: Counter,
    left: Gauge,
    sizes: Histogram,
    orders: ActorRef<String>,
    events: Hub<String>,
) =
    transact {
        free.update { it - 1 }
        logInfo("held one")
        log.log(line)
        sold.increment(1.0)
        left.set(free.get().toDouble())
        sizes.record(1.0)
        orders.tell("held")
        events.publish("held")
        println("held")
        print("held")
    }
"""

/** Every kind of effect, each inside an afterCommit block in a step. */
private const val AFTER_COMMIT =
    """
import dev.surefold.Counter
import dev.surefold.Shared
import dev.surefold.actor.ActorRef
import dev.surefold.logInfo
import dev.surefold.transact

val free = Shared(10)

fun hold(sold: Counter, orders: ActorRef<String>) = transact {
    free.update { it - 1 }
    afterCommit {
        logInfo("held one")
        sold.increment(1.0)
        orders.tell("held")
        Thread.sleep(1)
    }
}
"""
