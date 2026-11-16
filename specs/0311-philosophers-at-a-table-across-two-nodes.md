# 0311 — Philosophers at a table across two nodes

## Problem

The guides show remote actors one tell and one ask at a time (`docs/cluster.md`, "Two nodes"). Nothing in the repo
shows two nodes doing sustained, concurrent work over the wire, or what a protocol between them looks like when it
can go wrong. A reader who wants to see `surefold-actor-remote` actually coordinate something has to write it
themselves.

The dining philosophers is the textbook case. Forks on one node and philosophers on another make every take, grant
and put-down cross TCP. The naive order deadlocks, ordering the forks fixes it, and both outcomes can be checked.

## Not doing

- **Reliable delivery.** A lost `Granted` would starve a philosopher. That is what "Commands that must arrive"
  covers, and this example names that limit rather than solving it.
- **A cluster.** No membership, sharding or downing. Two plain nodes with fixed addresses.
- **A new module or published artifact.** It is a test, in the module it exercises.
- **TLS.**

## Shape

Two files in `actor/surefold-actor-remote/src/test/kotlin/dev/surefold/actor/remote/example/`:

- `DiningPhilosophers.kt`: the protocol (`Take`, `Put`, `Status`, `Granted`) and its codecs, the `fork` and
  `philosopher` behaviours, a local `watcher`, and a `main` that runs the table node.
- `DiningPhilosophersSuite.kt`: starts the table as a second JVM, runs the diners node, and asserts.

```kotlin
@Test fun `with the forks in order everyone eats and no neighbours eat together`() {
    val result = dine(ordered = true, meals = 3)
    result.meals shouldBe List(5) { 3 }
    result.neighbourClashes shouldBe 0
    result.forkViolations shouldBe 0
}

@Test fun `with each philosopher taking the left fork first the table deadlocks`() {
    val result = dine(ordered = false, meals = 3, giveUpAfter = 2.seconds)
    result.finished shouldBe false
    result.waitingFor shouldBe listOf("hegel", "plato", "kant", "hume", "locke") // the cycle, as the forks report it
}
```

A short paragraph in `docs/cluster.md` under "Two nodes" points at the suite.

## Why this shape

A test that asserts is an example that cannot rot, and it lives beside the code it uses. **Alternative:** a fence
in `docs/cluster.md` compiled by `GuideExampleTest`. That keeps it on the page, but the guide test only compiles
fences, and at roughly 200 lines this would be the longest one by far. Recommended: the suite, plus a link.

## Stack

- [ ] **`spec-0311-philosophers`**: the two files above, and the link in `docs/cluster.md`.
      Done when: both tests pass in `./gradlew :surefold-actor-remote:test`, and the naive test fails if the
      philosophers are ordered.

## Acceptance

```bash
./gradlew :surefold-actor-remote:test --tests '*DiningPhilosophersSuite*'
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Two JVMs or two nodes in one JVM?** Two processes are the more honest demo. Two `node(...)` scopes in one JVM
   still talk over real TCP, start faster, and need no child-process handling in a test. Recommended: two JVMs,
   since it is a showcase. Fall back to one JVM if CI timing makes it flaky.
2. **Keep the deadlock test?** It shows why the ordering matters, but it has to wait for the deadlock (2 s here).
   Recommended: keep it.
3. **Logging.** The prototype prints a timestamped line per event, which is the most readable part when run by
   hand. Recommended: log through `logInfo`, assert only on the result, and say in the KDoc how to see the log.
4. **Random think and eat times.** Recommended: a fixed seed, so a failure can be replayed.
