package dev.surefold.cluster

import dev.surefold.actor.AskFailure
import dev.surefold.actor.Reply
import dev.surefold.actor.behaviour
import dev.surefold.actor.remote.Codecs
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.remote.freePort
import dev.surefold.actor.remote.node
import dev.surefold.actor.spawn
import dev.surefold.actor.stay
import dev.surefold.fail.left
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.scope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private data class WhoAmI(val reply: Reply<String>)

private val adminCodec =
    object : MessageCodec<WhoAmI> {
        override fun write(message: WhoAmI, out: WireOut) {
            out.int(1)
            out.reply(message.reply, Codecs.string)
        }

        override fun read(input: WireIn): WhoAmI =
            when (val tag = input.int()) {
                1 -> WhoAmI(input.reply(Codecs.string))
                else -> error("no admin message has the tag $tag")
            }
    }

private val brisk =
    Gossiping(probeEvery = 200.milliseconds, ackWithin = 60.milliseconds, formAfter = 1_000.milliseconds)

private class AdminNode(val name: String, port: Int, seeds: Discovery, val hold: CountDownLatch? = null) :
    AutoCloseable {
    private val done = CountDownLatch(1)
    private val ready = CountDownLatch(1)
    private val opened = AtomicReference<Cluster>()
    private val thread =
        Thread.ofPlatform().start {
            scope {
                val remote = node(name, port)
                val cluster = cluster(remote, seeds, brisk)
                remote.expose(
                    spawn(
                        "admin",
                        behaviour<WhoAmI, Unit>(Unit) { _, _, message ->
                            hold?.await()
                            stay().also { message.reply(name) }
                        },
                    ),
                    adminCodec,
                )
                opened.set(cluster)
                ready.countDown()
                done.await()
            }
        }

    val cluster: Cluster
        get() {
            ready.await()
            return opened.get()
        }

    override fun close() {
        done.countDown()
        thread.join()
    }
}

object AskEachSuite : Suite() {
    init {
        test("askEach returns one answer per Up member, and times out a slow one") {
            val ports = List(3) { freePort() }
            val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
            val hold = CountDownLatch(1)
            val nodes =
                listOf(
                    AdminNode("n1", ports[0], seeds),
                    AdminNode("n2", ports[1], seeds),
                    AdminNode("n3", ports[2], seeds, hold = hold),
                )
            try {
                nodes.forEach { n ->
                    n.cluster.await(1.minutes) { view -> view.members.count { it.status == Status.Up } == 3 } shouldBe
                        true
                }
                val answers = nodes[0].cluster.askEach(200.milliseconds, adminCodec, "/user/admin") { WhoAmI(it) }
                answers.size shouldBe 3
                answers.values.count { it.isRight() } shouldBe 2
                answers.values.count { it == AskFailure.TimedOut.left() } shouldBe 1
                hold.countDown()
            } finally {
                hold.countDown()
                nodes.forEach(AdminNode::close)
            }
        }
    }
}
