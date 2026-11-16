# 0311 — Philosophers at a table across two nodes

## Problem

The guides show remote actors one tell and one ask at a time (`docs/cluster.md`, "Two nodes"). Nothing in the repo
shows two nodes doing sustained, concurrent work over the wire. Nothing shows `testActors` hunting a protocol bug
across seeded orders, then the same behaviours running for real. A reader who wants to see both has to write it.

The dining philosophers is the textbook case. Forks on one node and philosophers on another make every take, grant
and put-down cross TCP. Taking the left fork first deadlocks, ordering the forks fixes it, and both can be checked.

## Not doing

- **Reliable delivery.** A lost `Granted` starves a philosopher. "Commands that must arrive" covers that.
- **A cluster, TLS, or a second JVM.** Two plain nodes in one JVM, as `RemoteSuite` runs them.
- **A new module, or a guide fence.** It is a suite in the module it exercises.

## Shape

One package, `actor/surefold-actor-remote/src/test/kotlin/dev/surefold/actor/remote/philosophers/`:

- `Philosophers.kt`: the protocol and its `MessageCodec`s, the `fork` and `philosopher` behaviours, and `Seen`.
- `Render.kt`: the timeline and fork-ring printing below.
- `PhilosophersSuite.kt`: a `Suite`, `surefold-assert` checks, with ports from the `freePort()` fixture.

The same behaviours run twice: under `testActors(order = Ordering.seeded(seed))` and `settle()` for 200 seeds,
then on two nodes over TCP. `Seen` is written by each philosopher as it starts eating and before it puts a fork
back, so no message can overtake it. It stamps time from a function: delivery count under `testActors`, wall
clock on nodes.

```kotlin
test("with the forks taken in order, every seeded order finishes with no neighbours eating together") {
    val runs = (1L..200L).map { seeded(ordered = true, seed = it) }
    runs.count { it.finished } shouldBe 200
    runs.sumOf { it.seen.clashes } shouldBe 0
}
test("with each philosopher taking the left fork first, some seeded order deadlocks") { … }
test("across two nodes, the ordered table finishes and the naive one deadlocks") { … }
```

Each test prints what it found, so a run by hand reads as a demonstration:

```
  ━━ ordered · node diners ⇄ node forks over TCP ━━━━━━━━━━━━━━━━━━━━━━━━━━━
  plato  │······················████·········█████···········██████·······│ 3 meals
  kant   │··················█████·····█████··········█████················│ 3 meals
  ...
  ✔ everyone finished · most eating at once: 2 · neighbour clashes: 0

  ━━ left fork first · node diners ⇄ node forks over TCP ━━━━━━━━━━━━━━━━━━━
  ✘ nobody moved for 3 s: deadlock. The forks, asked across the wire:
  fork-0  held by plato, hegel waiting
  ...
```

A short paragraph in `docs/cluster.md` under "Two nodes" points at the suite.

## Why this shape

The seeded runs are the proof: 200 orders, deterministic, replayable by seed. The two-node run is the showcase,
and shows `testActors` and the wire agree. **Alternative:** a guide fence compiled by `GuideExampleTest`. That
compiles but does not run, and at about 300 lines it would be the longest fence by far. Recommended: the suite.

A prototype passes today: 200/200 ordered seeds finish with 0 clashes, the naive table deadlocks on every seed,
and the two-node run matches both.

## Stack

- [x] **`spec-0311-philosophers`**: the package above, and the link in `docs/cluster.md`. (surefold/surefold-core#1108)
      Done when: the suite passes in `./gradlew :surefold-actor-remote:test`, and flipping the ordering fails it.

## Acceptance

```bash
./gradlew :surefold-actor-remote:test --tests '*PhilosophersSuite*' -i
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Print or log?** Printing gives the readable output above. `logInfo` would route through the logger and
   need `capturingLogs` to show. Recommended: print, since the output is the point.
2. **`Faults(drop = …)`?** A fourth test could show a lost `Granted` starving a philosopher. Recommended: leave
   it out, and link "Commands that must arrive" instead.
3. **Seed count.** 200 seeds take under a second. Recommended: keep 200.
