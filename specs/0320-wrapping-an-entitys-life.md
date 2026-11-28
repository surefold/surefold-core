# 0320 — Wrapping an entity's life

## Problem

Tracing, timing and log annotations around an entity today are hand-rolled
inside every behaviour: wrap `onStart`, wrap each turn, remember to annotate
by id. A service with several kinds copies the same preamble, or ships with
none and cannot tell which entity a span belonged to. Carriers already cross
asks (spec 0122); nothing wraps the entity's own life from start to
passivate as one unit.

## Not doing

- **No per-message metrics on the hot path by default.** An around that records
  is the service's choice; surefold does not add a timer to every turn.
- **No change to supervision, passivation, or sharding handoff.**
- **No global interceptor registry.** Wrapping is per `entities` /
  `sharding` call, where the kind is named.

## Shape

A small `Around` passed into `entities` and `Cluster.sharding`, built on a
`Behaviour` combinator so a single actor can be wrapped in tests without a
manager.

```kotlin
data class Around<M : Any>(
    val entity: (id: String, run: () -> Unit) -> Unit = { _, run -> run() },
    val message: (id: String, message: M, run: () -> Unit) -> Unit = { _, _, run -> run() },
)

val baskets = cluster.sharding(
    "basket",
    BasketCodec,
    passivateAfter = 10.minutes,
    around = Around(
        entity = { id, run -> logAnnotated("entity" to "basket/$id") { run() } },
        message = { _, _, run -> timed("entity.turn", "kind" to "basket") { run() } },
    ),
) { basket() }
```

- `entity` wraps the entity's life from start through stop/passivate (what
  runs under it sees the annotations or span for every turn).
- `message` wraps each turn; the service may pattern-match on `message`.
- Arounds compose: `a then b` means `a` wraps `b`.
- Default around is identity; today's kinds need no change.

## Why this shape

Hooks at registration keep behaviours free of boilerplate and stay off the
path when unused. The alternative is a `Behaviour.around` combinator alone;
useful, but every kind still has to remember to apply it. Recommended: both a
registration `Around` on entities/sharding and a `Behaviour` combinator the
hooks are built from, so tests can wrap one actor without a manager.

## Stack

- [ ] **`cursor/behaviour-around-2d0b`** — `Behaviour` combinator that wraps
      life/start and each turn; unit tests on a single actor.
      Done when: a wrapped behaviour's turn runs inside the around, and an
      around that throws fails the actor as the turn would.
- [ ] **`cursor/entity-around-2d0b`** — `Around` on `entities` and `sharding`,
      composition, guide example with log annotations by id.
      Done when: two entities of one kind each see their own id in annotations
      for every turn, without the behaviour naming annotations itself.

## Acceptance

```bash
./gradlew :surefold-actor:check :surefold-cluster:check
```

## Open questions

1. **Is `around` a parameter or a separate `sharding(…).around { }` builder?**
   Recommended: a parameter with a default, so every node still calls
   `sharding` with the same arguments for kind/codec/shards; around must be
   identical on every node too, or only local observability differs — say so
   in the guide.
2. **Does message-around see Concurrent turns (0319)?** Recommended: yes, each
   Concurrent turn is wrapped on its own thread.
3. **Should surefold ship one Around for OTel spans?** Recommended: defer; this
   spec is the hook. A follow-up in `surefold-otel` can provide one.
