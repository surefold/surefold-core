package dev.surefold.actor

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A message that must not call [become], and may run beside other [Concurrent] messages for the same entity (spec
 * 0319). Exclusive messages wait until every Concurrent turn for that entity has finished.
 */
interface Concurrent

/**
 * This behaviour with Concurrent turns forked on virtual threads, exclusive turns waiting for them. A Concurrent turn
 * that returns [become] fails the actor on its next exclusive turn (spec 0319).
 */
internal fun <M : Any, S, E> Behaviour<M, S, E>.concurrentReads(): Behaviour<M, S, E> {
    val flight = AtomicInteger()
    val lock = ReentrantLock()
    val gate = lock.newCondition()
    val defect = AtomicReference<Throwable>()
    val innerTurn = turn
    return Behaviour(
        initial,
        { ctx, state, message ->
            if (message is Concurrent) {
                flight.incrementAndGet()
                val step = this
                Thread.ofVirtual().start {
                    try {
                        val next = innerTurn.invoke(step, ctx, state, message)
                        if (next is Next.Become<*>) {
                            defect.compareAndSet(null, IllegalStateException("a Concurrent turn must not become"))
                        }
                    } catch (thrown: Throwable) {
                        defect.compareAndSet(null, thrown)
                    } finally {
                        if (flight.decrementAndGet() == 0) lock.withLock { gate.signalAll() }
                    }
                }
                stay()
            } else {
                lock.withLock {
                    while (flight.get() > 0) gate.await()
                }
                defect.get()?.let { error ->
                    endAsks(message, AskFailure.Stopped)
                    throw error
                }
                innerTurn.invoke(this, ctx, state, message)
            }
        },
        signal,
        start,
        turns,
        batch,
    )
}
