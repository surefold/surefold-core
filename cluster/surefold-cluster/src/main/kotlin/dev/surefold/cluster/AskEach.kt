package dev.surefold.cluster

import dev.surefold.actor.Address
import dev.surefold.actor.AskFailure
import dev.surefold.actor.Reply
import dev.surefold.actor.ask
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.Node
import dev.surefold.fail.Either
import dev.surefold.fail.left
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * Asks every Up, reachable member [message] at [path], including this node, waiting up to [within] for each. The map
 * has one entry per member asked; a member already unreachable is [AskFailure.Unreachable] at once (spec 0323).
 */
fun <M : Any, A : Any> Cluster.askEach(
    within: Duration,
    codec: MessageCodec<M>,
    path: String,
    message: (Reply<A>) -> M,
): Map<Node, Either<AskFailure, A>> {
    val asked = view.members.filter { it.status == Status.Up }.map { it.node }.distinct()
    val answers = ConcurrentHashMap<Node, Either<AskFailure, A>>()
    val threads = asked.map { node ->
        Thread.ofVirtual().start {
            answers[node] =
                if (node in view.unreachable) AskFailure.Unreachable.left()
                else {
                    val ref = remote.remote(Address(node.toString(), path, 0), codec)
                    ref.ask(within, message)
                }
        }
    }
    threads.forEach(Thread::join)
    return asked.associateWith { answers.getValue(it) }
}
