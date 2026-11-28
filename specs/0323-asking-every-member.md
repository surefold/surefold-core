# 0323 — Asking every member

## Problem

A topic publishes to every `Up` member at most once and does not wait for a
reply (spec 0082). Runbooks and admin actions often need the other shape: ask
every live member the same question and collect each answer — flush a cache,
report local entity counts, drain a node-local buffer. Today the service lists
`cluster.view.members`, builds remote refs by hand, and fans out `ask` itself,
with ad-hoc timeout and partial-failure rules.

## Not doing

- **No change to topics.** Publish stays at-most-once fanout without replies.
- **No guaranteed delivery or retry across the set.** Each member is asked
  once; failures stay per member.
- **No asking Leaving/Joining/Down members.** Only `Up` and reachable, same
  population a topic would publish to.
- **No entity broadcast.** This is members, not ids.

## Shape

```kotlin
/**
 * Asks every Up, reachable member [message], including this node when it
 * matches, waiting up to [within] for each. The map has one entry per member
 * asked; a member that times out or is unreachable is Left.
 */
fun <M : Any, A : Any> Cluster.askEach(
    within: Duration,
    codec: MessageCodec<M>,
    path: String,
    message: (Reply<A>) -> M,
): Map<Node, Either<AskFailure, A>>
```

Sketch a slightly nicer overload tied to a local actor every member exposes:

```kotlin
// every member: node.expose(spawn("admin", admin()), AdminCodec)
val answers = cluster.askEach(2.seconds, AdminCodec, "/user/admin") { Flush(it) }
answers.forEach { (node, result) ->
    result.fold(
        { why -> log.warn("flush failed on $node: $why") },
        { n -> log.info("flushed $n on $node") },
    )
}
```

- Keys are the members asked (stable for one call); values use existing
  `AskFailure` (`TimedOut`, `Unreachable`, `Stopped`, …).
- Local member is asked in-process; others through `node.remote` as today's
  remote ask.
- Order of the map is not significant; completion waits for every ask to
  settle (answer or failure), up to `within` each.

## Why this shape

A cluster method owns the membership filter and the parallel asks, so every
service does not reimplement them. The alternative is documenting a recipe
with `view.members` only; that stays possible, but the timeout and
Left-per-node rules are easy to get wrong. Recommended: `askEach` on
`Cluster`, not a change to `Topic`.

## Stack

- [ ] **`cursor/cluster-ask-each-2d0b`** — `Cluster.askEach`, tests with three
      members (one slow past `within`, one local), guide snippet beside topics.
      Done when: three Up members yield a three-entry map, the slow one is
      `TimedOut`, the local one is `Right`, and a Down member is absent. Without
      the change the recipe test is the only coverage and lives in the service.

## Acceptance

```bash
./gradlew :surefold-cluster:check
```

## Open questions

1. **Parallelism cap?** Recommended: ask all Up members at once; clusters are
   small. Revisit if someone runs hundreds of members.
2. **Should `path` be an `Address` template or an `ActorRef` factory?**
   Recommended: path + codec as in the sketch, matching `node.remote(Address(…))`.
3. **Include unreachable Up members as immediate `Unreachable`?** Recommended:
   yes, do not wait `within` on a member the view already marks unreachable.
