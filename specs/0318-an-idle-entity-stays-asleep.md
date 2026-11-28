# 0318 — An idle entity stays asleep

## Problem

Every message for an entity starts it when it is not running: passivation is
undone the moment anything arrives. Cleanup and no-op commands
(`Leave`, `Expire`, `Forget`) are often told for ids that are already idle.
Today each one wakes the entity, rebuilds state from the journal when it is
persistent, runs a turn that changes nothing, and passivates again. Under a
bulk cleanup that is a storm of starts for work the service did not need.

## Not doing

- **No change to passivation timers, handoff, or reliable delivery.** A command
  a producer must land still starts the entity; this only gates starts.
- **No change to `tell`'s return type.** A discarded tell is a dead letter, as
  a full mailbox already is.
- **No per-message policy outside entities and sharding.** Ordinary actors are
  unchanged.

## Shape

`entities` and `Cluster.sharding` take an optional predicate. When the entity
is not running and the predicate says no, the message is not delivered and the
entity is not started.

```kotlin
fun <M : Any, S, E> entities(
    passivateAfter: Duration,
    startIf: (id: String, message: M) -> Boolean = { _, _ -> true },
    // …
    entity: (id: String) -> Behaviour<M, S, E>,
): Behaviour<Entities<M>, Any?, Nothing>

val baskets = cluster.sharding(
    "basket",
    BasketCodec,
    passivateAfter = 10.minutes,
    startIf = { _, message -> message !is Expire },
) { basket() }

baskets.entity("customer-42").tell(Expire) // idle: discarded, no start
baskets.entity("customer-42").tell(Put("sku-1")) // starts as today
```

- A discarded tell is a dead letter with a new `DeadLetter.Why.Discarded`.
- A discarded ask ends with a new `AskFailure.Discarded` (same family as
  `TimedOut` / `Stopped`), not a hang until timeout.
- An entity that is already running, or stopping with messages kept for it,
  still receives every message; `startIf` only gates a cold start.

## Why this shape

A predicate at the manager is one place to decide, and keeps protocols free of
a second "maybe start" API. The alternative is a marker interface on messages
(`DoNotStart`); that forces every no-op message to opt in, and a kind that
wants id-dependent rules cannot express them. Recommended: the predicate, with
the default keeping today's start-on-any-message behaviour.

## Stack

- [ ] **`cursor/idle-entity-stays-asleep-2d0b`** — `startIf` on `entities` and
      `sharding`, `DeadLetter.Why.Discarded`, `AskFailure.Discarded`, guide
      sentence, and tests for tell and ask against an idle id.
      Done when: an idle entity told a rejected message does not start, the
      tell is a Discarded dead letter, and an ask returns `AskFailure.Discarded`
      before its timeout. Without the change the entity starts.

## Acceptance

```bash
./gradlew :surefold-actor:check :surefold-cluster:check
```

## Open questions

1. **Does `startIf` see the bare message or the sharded envelope?** Recommended:
   the kind's message only, as the sketch shows; hops and federation stay
   inside the region.
2. **Should a reliable producer's command ever be discarded?** Recommended: no.
   `startIf` still runs, but a `Delivery` that would be discarded is a
   programmer error surfaced in tests; producers exist to start the entity.
   Defer a hard refuse if that claim needs its own suite.
