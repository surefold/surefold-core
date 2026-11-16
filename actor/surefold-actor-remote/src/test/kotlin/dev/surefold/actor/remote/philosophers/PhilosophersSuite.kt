package dev.surefold.actor.remote.philosophers

import dev.surefold.Promise
import dev.surefold.Scope
import dev.surefold.actor.ActorRef
import dev.surefold.actor.Address
import dev.surefold.actor.Ordering
import dev.surefold.actor.ask
import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.freePort
import dev.surefold.actor.remote.node
import dev.surefold.actor.spawn
import dev.surefold.actor.stop
import dev.surefold.actor.testActors
import dev.surefold.actor.watch
import dev.surefold.awaitValue
import dev.surefold.clock
import dev.surefold.fail.getOrElse
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.scope
import dev.surefold.timeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** One table under `testActors`: every delivery in an order picked from [seed], on the test's own clock. */
private fun seeded(ordered: Boolean, seed: Long) =
    testActors(order = Ordering.seeded(seed)) {
        val forks = List(SEATS) { spawn("fork-$it", fork(it)) }
        val seen = Seen { trace.size.toLong() }
        val diners =
            List(SEATS) { spawn(NAMES[it], philosopher(it, forks, seen, meals = 3, ordered = ordered, seed = seed)) }
        settle()
        Outcome(diners.all { it.stopped }, seen, forks.map { it.state })
    }

private data class Outcome(val finished: Boolean, val seen: Seen, val forks: List<ForkState>)

/**
 * The forks on a node of their own, `forks`, in a fork of this scope, for as long as [block] runs. The node's scope
 * ends when the fork is cancelled, which closes the node.
 */
private fun <A> withForkNode(block: (port: Int) -> A): A = scope {
    val port = freePort()
    val up = Promise<Nothing, Unit>("forks node up")
    val forks = async {
        scope {
            val node = node("forks", port)
            repeat(SEATS) { node.expose(spawn("fork-$it", fork(it)), ForkCodec) }
            up.succeed(Unit)
            Promise<Nothing, Unit>("until cancelled").awaitValue()
        }
    }
    up.awaitValue()
    block(port).also { forks.cancel() }
}

/** The philosophers on node `diners`, the forks they reach across TCP on node `forks`. */
private fun Scope<Nothing>.acrossTwoNodes(forkPort: Int, ordered: Boolean): Pair<Boolean, Pair<Seen, List<String>>> {
    val node = node("diners", freePort())
    val at = Node("forks", "127.0.0.1", forkPort)
    val forks: List<ActorRef<ForkMsg>> =
        List(SEATS) { node.remote(Address(at.toString(), "/user/fork-$it", 0), ForkCodec) }
    val start = clock.get().now()
    val seen = Seen { java.time.Duration.between(start, clock.get().now()).toMillis() }
    val diners = List(SEATS) { spawn(NAMES[it], philosopher(it, forks, seen, meals = 3, ordered = ordered, seed = 7)) }
    val finished = timeoutOrNull(3.seconds) { diners.forEach { watch(it).await() } } != null
    val states = forks.map { f -> f.ask(2.seconds) { Status(it) }.getOrElse { "did not answer: $it" } }
    diners.forEach { stop(it).await() }
    return finished to (seen to states)
}

object PhilosophersSuite : Suite() {
    init {
        test("with the forks taken in order, every seeded order finishes with no neighbours eating together") {
            val runs = (1L..200L).map { seeded(ordered = true, seed = it) }
            runs.count { it.finished } shouldBe 200
            runs.sumOf { it.seen.clashes } shouldBe 0
            runs.sumOf { r -> r.forks.sumOf { it.misuse } } shouldBe 0
            println(banner("ordered · 200 seeded orders under testActors"))
            println(verdict(true, "200 of 200 tables finished, 3 meals each"))
            println(verdict(true, "0 neighbours ever ate together, 0 forks put down by a non-holder"))
            println("\n  seed 1, by delivery:\n")
            println(timeline(runs.first().seen, "deliveries"))
        }

        test("with each philosopher taking the left fork first, some seeded order deadlocks") {
            val stuck = (1L..200L).map { it to seeded(ordered = false, seed = it) }.filterNot { it.second.finished }
            (stuck.isNotEmpty()) shouldBe true
            val (seed, outcome) = stuck.first()
            println(banner("left fork first · 200 seeded orders under testActors"))
            println(verdict(false, "${stuck.size} of 200 tables deadlocked; the first was seed $seed"))
            println("\n  seed $seed, where it stopped:\n")
            println(forks(outcome.forks.map { "$it" }))
        }

        test("across two nodes, the ordered table finishes and the naive one deadlocks") {
            listOf(true, false).forEach { ordered ->
                val (finished, seen) = withForkNode { port -> scope { acrossTwoNodes(port, ordered) } }
                val (table, states) = seen
                finished shouldBe ordered
                table.clashes shouldBe 0
                println(
                    banner(
                        if (ordered) "ordered · node diners ⇄ node forks over TCP"
                        else "left fork first · node diners ⇄ node forks over TCP",
                    ),
                )
                if (finished) {
                    println(timeline(table, "ms"))
                    println()
                    println(
                        verdict(
                            true,
                            "everyone finished · most eating at once: ${table.mostAtOnce}" +
                                " · neighbour clashes: ${table.clashes}",
                        ),
                    )
                } else {
                    println(verdict(false, "nobody moved for 3 s: deadlock. The forks, asked across the wire:"))
                    println()
                    println(forks(states))
                }
            }
        }
    }
}
