package dev.surefold.app.compiler

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldNotContain
import dev.surefold.junit.withClue

/**
 * The warnings for code that hand-rolls what surefold ships (specs 0132 and 0312), each named, each silenced by name,
 * compiled in-process as main code or, with `test`, as a test compilation is.
 */
object HandRolledSuite : Suite() {
    init {
        test("a sleep in a test is named, with eventually beside it") {
            val result = compile(SLEEPS, test = true)

            result.output shouldContain "Thread.sleep waits a fixed time"
            result.output shouldContain "TimeUnit.sleep waits a fixed time"
            result.output shouldContain "surefold-test's eventually(within)"
        }

        test("a sleep in main code is not a test's, and is left alone") {
            compile(SLEEPS).output shouldNotContain "waits a fixed time"
        }

        test("a scheduler built by hand in main code is named, with Stream tick beside it") {
            val result = compile(SCHEDULERS)

            result.output shouldContain "Executors.newScheduledThreadPool schedules work by hand"
            result.output shouldContain "Executors.newSingleThreadScheduledExecutor schedules work by hand"
            result.output shouldContain "ScheduledThreadPoolExecutor(..) schedules work by hand"
            result.output shouldContain "Timer(..) schedules work by hand"
            result.output shouldContain "Stream.tick(every) started on the graph's StreamBackend"
            withClue("an executor that schedules nothing is not a scheduler") {
                result.output shouldNotContain "newVirtualThreadPerTaskExecutor schedules work by hand"
            }
        }

        test("a thread or pool started by hand is named, in main and test code, with scope async beside it") {
            listOf(false, true).forEach { test ->
                val result = compile(THREADS, test = test)
                listOf(
                        "Thread(..)",
                        "Thread.ofVirtual",
                        "Thread.ofPlatform",
                        "Thread.startVirtualThread",
                        "thread { }",
                        "Executors.newFixedThreadPool",
                        "Executors.newCachedThreadPool",
                        "Executors.newSingleThreadExecutor",
                        "Executors.newWorkStealingPool",
                        "Executors.newVirtualThreadPerTaskExecutor",
                        "Executors.newThreadPerTaskExecutor",
                        "ThreadPoolExecutor(..)",
                        "ForkJoinPool(..)",
                    )
                    .forEach { result.output shouldContain "$it starts threads by hand" }
                result.output shouldContain "fork with scope.async"
            }
        }

        test("a scheduled pool is the scheduler warning's, not the thread warning's") {
            val result = compile(SCHEDULERS)

            result.output shouldNotContain "newScheduledThreadPool starts threads by hand"
            result.output shouldNotContain "ScheduledThreadPoolExecutor(..) starts threads by hand"
        }

        test("a latch, a barrier or an empty future is named, in main and test code, with Promise beside it") {
            listOf(false, true).forEach { test ->
                val result = compile(LATCHES, test = test)
                listOf("CountDownLatch(..)", "CyclicBarrier(..)", "Phaser(..)", "CompletableFuture(..)").forEach {
                    result.output shouldContain "$it hands a signal between threads by hand"
                }
                result.output shouldContain "Promise<Nothing, Unit> for a latch"
                withClue("a future handed back with a value is not a latch") {
                    result.output shouldNotContain "supplyAsync"
                    result.output shouldNotContain "completedFuture"
                }
            }
        }

        test("a thread or latch that means it is silenced by name") {
            compile("@file:Suppress(\"SUREFOLD_THREAD_BY_HAND\")\n$THREADS").output shouldNotContain
                "starts threads by hand"
            compile("@file:Suppress(\"SUREFOLD_LATCH_BY_HAND\")\n$LATCHES").output shouldNotContain
                "hands a signal between threads by hand"
        }

        test("a lock taken by hand is named, in main and test code, with Shared beside it") {
            listOf(false, true).forEach { test ->
                val result = compile(LOCKS, test = test)
                listOf(
                        "synchronized { }",
                        "@Synchronized",
                        "ReentrantLock(..)",
                        "ReentrantReadWriteLock(..)",
                        "StampedLock(..)",
                    )
                    .forEach { result.output shouldContain "$it guards shared state with a lock by hand" }
                result.output shouldContain "keep the state in a Shared"
            }
        }

        test("a blocking queue is named, in main and test code, with channel beside it") {
            listOf(false, true).forEach { test ->
                val result = compile(QUEUES, test = test)
                listOf(
                        "LinkedBlockingQueue(..)",
                        "ArrayBlockingQueue(..)",
                        "SynchronousQueue(..)",
                        "LinkedTransferQueue(..)",
                    )
                    .forEach { result.output shouldContain "$it hands values between threads by hand" }
                result.output shouldContain "channel(capacity)"
                withClue("a queue nothing blocks on is not a hand-off between threads") {
                    result.output shouldNotContain "ConcurrentLinkedQueue"
                }
            }
        }

        test("a lock or queue that means it is silenced by name") {
            compile("@file:Suppress(\"SUREFOLD_LOCK_BY_HAND\")\n$LOCKS").output shouldNotContain "with a lock by hand"
            compile("@file:Suppress(\"SUREFOLD_QUEUE_BY_HAND\")\n$QUEUES").output shouldNotContain
                "hands values between threads by hand"
        }

        test("a run that parks on its root is named, with awaitShutdown beside it") {
            val result = compile(PARKED)

            result.output shouldContain "run parks on block()"
            result.output shouldContain "park on awaitShutdown()"
        }

        test("a run that awaits shutdown, or returns, is left alone") {
            compile(AWAITED).output shouldNotContain "run parks on"
            compile(SOUND).output shouldNotContain "run parks on"
        }

        test("a sleep that means it is silenced by name") {
            compile("@file:Suppress(\"SUREFOLD_SLEEP_IN_TEST\")\n$SLEEPS", test = true).output shouldNotContain
                "waits a fixed time"
        }

        test("every source here compiles, so a warning is the checker's and not the compiler's") {
            listOf(SLEEPS, SCHEDULERS, THREADS, LATCHES, LOCKS, QUEUES, PARKED, AWAITED, SOUND).forEach {
                compile(it).succeeded shouldBe true
            }
        }
    }
}

/** Both sleeps the JDK has, the way a test reaches for them. */
private const val SLEEPS =
    """
import java.util.concurrent.TimeUnit

fun waits() {
    Thread.sleep(200)
    TimeUnit.MILLISECONDS.sleep(200)
}
"""

/** Every way the JDK builds a scheduler, and one executor that is not one. */
private const val SCHEDULERS =
    """
import java.util.Timer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor

fun schedulers() = listOf(
    Executors.newScheduledThreadPool(1),
    Executors.newSingleThreadScheduledExecutor(),
    ScheduledThreadPoolExecutor(1),
    Timer(),
    Executors.newVirtualThreadPerTaskExecutor(),
)
"""

/** Every way the JDK starts a thread or builds a pool of them, as code written on surefold reaches for one. */
private const val THREADS =
    """
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

fun threads(): List<Any> = listOf(
    Thread {},
    Thread.ofVirtual(),
    Thread.ofPlatform(),
    Thread.startVirtualThread {},
    thread(start = false) {},
    Executors.newFixedThreadPool(1),
    Executors.newCachedThreadPool(),
    Executors.newSingleThreadExecutor(),
    Executors.newWorkStealingPool(),
    Executors.newVirtualThreadPerTaskExecutor(),
    Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory()),
    ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, LinkedBlockingQueue()),
    ForkJoinPool(1),
)
"""

/** Every way the JDK hands one signal between threads, and two futures that carry a value and are not latches. */
private const val LATCHES =
    """
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Phaser

fun latches(): List<Any> = listOf(
    CountDownLatch(1),
    CyclicBarrier(2),
    Phaser(),
    CompletableFuture<Unit>(),
    CompletableFuture.supplyAsync { 1 },
    CompletableFuture.completedFuture(1),
)
"""

/** Every way the JDK and Kotlin take a lock by hand. */
private const val LOCKS =
    """
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.locks.StampedLock

class Counter {
    private var count = 0
    private val locks = listOf(ReentrantLock(), ReentrantReadWriteLock(), StampedLock())

    fun add() = synchronized(this) { count++ }

    @Synchronized
    fun total() = count + locks.size
}
"""

/** Every blocking queue the JDK hands values between threads with, and one nothing blocks on. */
private const val QUEUES =
    """
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.LinkedTransferQueue
import java.util.concurrent.SynchronousQueue

fun queues(): List<Any> = listOf(
    LinkedBlockingQueue<Int>(),
    ArrayBlockingQueue<Int>(1),
    SynchronousQueue<Int>(),
    LinkedTransferQueue<Int>(),
    ConcurrentLinkedQueue<Int>(),
)
"""

/** A root whose own wait ends only when it is stopped, which is the release, after run returns. */
private const val PARKED =
    """
import dev.surefold.app.AppScope
import dev.surefold.app.SurefoldApp
import dev.surefold.app.Module
import dev.surefold.app.single

class Server { fun block() = Unit }

object Under : SurefoldApp<Server>() {
    override val module: Module = single<Server> { Server() }
    override fun AppScope.run(root: Server) { root.block() }
}
"""

/** The same root, with run parked where surefold can end it. */
private const val AWAITED =
    """
import dev.surefold.app.AppScope
import dev.surefold.app.SurefoldApp
import dev.surefold.app.Module
import dev.surefold.app.single

class Server { fun block() = Unit }

object Under : SurefoldApp<Server>() {
    override val module: Module = single<Server> { Server() }
    override fun AppScope.run(root: Server) { awaitShutdown() }
}
"""
