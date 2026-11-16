# 0312 — Threads and latches named at compile time

## Problem

0132's checker names four things a service hand-rolls: a scheduler, a test sleep, a secret read as a string, and a
`run` that ignores shutdown. It says nothing about the habits agents fall back on most: a `Thread` started by hand, a
`CountDownLatch`, a `synchronized` block, a `BlockingQueue`. Each of these has a surefold replacement, and the
skill and `llms.txt` now list them. But a list only works if it is read, and an agent pointed at a project
still writes `Thread.ofPlatform().start { }` with two latches and gets a green build. A thread started by hand belongs
to no scope, so its failure never reaches the caller and it can outlive the code that started it.

Spec 0309 bans threads started by hand in this repository's main sources with detekt. Consumers don't run that
detekt config. The compiler plugin is what they do run.

## Not doing

- **Errors.** Every diagnostic here is a warning, suppressible by name, like 0132's.
- **Atomics.** `AtomicInteger` and friends are right in hot paths. 0309 left them out for the same reason (its
  question 2).
- **Java sources.** The checker is a K2 plugin and sees Kotlin only.
- **This repository's own tests.** Extending 0309's detekt rule to `src/test` is 0307's job (its `no-sleep` entry),
  or a spec of its own.
- **`System.nanoTime` and `Instant.now`.** Code reading the time is often correct. Question 2 asks whether to add it.

## Shape

Four new diagnostics from `HandRolledChecker`, each naming what to use instead, in main and test compilations alike:

```kotlin
Thread.ofPlatform().start { serve() }       // SUREFOLD_THREAD_BY_HAND: scope { async { … } }
val ready = CountDownLatch(1)                // SUREFOLD_LATCH_BY_HAND: Promise<Nothing, Unit>
synchronized(lock) { seen += seat }          // SUREFOLD_LOCK_BY_HAND: Shared, changed with update or transact
val rows = LinkedBlockingQueue<Row>(64)      // SUREFOLD_QUEUE_BY_HAND: channel<Row>(64)
```

| Diagnostic | Matches |
|---|---|
| `SUREFOLD_THREAD_BY_HAND` | `Thread(..)`, `Thread.ofVirtual`, `Thread.ofPlatform`, `Thread.startVirtualThread`, Kotlin's `thread { }`, `Executors.new*` other than the scheduled ones (already 0132's), `ThreadPoolExecutor(..)`, `ForkJoinPool(..)` |
| `SUREFOLD_LATCH_BY_HAND` | `CountDownLatch(..)`, `CyclicBarrier(..)`, `Phaser(..)`, `CompletableFuture<T>()` with no arguments |
| `SUREFOLD_LOCK_BY_HAND` | Kotlin's `synchronized(..) { }`, `@Synchronized`, `ReentrantLock(..)`, `ReentrantReadWriteLock(..)`, `StampedLock(..)` |
| `SUREFOLD_QUEUE_BY_HAND` | `LinkedBlockingQueue(..)`, `ArrayBlockingQueue(..)`, `SynchronousQueue(..)`, `LinkedTransferQueue(..)` |

Calls and constructors are matched by resolved `CallableId`, as `sleeps` and `schedulers` are now. `@Synchronized`
needs a `FirFunctionChecker` next to the call checker. `docs/app.md`'s warnings table, `SKILL.md` and `llms.txt` each
get the four new names.

## Why this shape

One diagnostic per habit, as 0132 does, so a project can silence the one it means and keep the rest:
`@file:Suppress("SUREFOLD_LOCK_BY_HAND")` on a file that really implements a lock. **Alternative:** one
`SUREFOLD_JDK_PRIMITIVE` diagnostic whose message names the replacement. That's fewer names, but suppressing it
silences all four at once. Recommended: four.

Unlike `SLEEP_IN_TEST`, these warn in main code too. 0309 found the same habits in main sources, where they cost
more than in tests.

## Stack

- [ ] **`spec-0312-threads`**: `SUREFOLD_THREAD_BY_HAND` and `SUREFOLD_LATCH_BY_HAND`, with their rows in
      `docs/app.md`, `SKILL.md` and `llms.txt`.
      Done when: `SurefoldWiringPluginTest` compiles a project using each matched call and sees each warning,
      in main and test sources, and none from a file that suppresses it.
- [ ] **`spec-0312-locks-queues`**: `SUREFOLD_LOCK_BY_HAND`, including the `@Synchronized` function checker, and
      `SUREFOLD_QUEUE_BY_HAND`, with the same docs rows.
      Done when: the same, for these two.

## Acceptance

```bash
./gradlew :surefold-app-gradle:test --tests '*SurefoldWiringPluginTest*'
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Warn in test code too?** Tests are where agents reach for latches most, and 0307 replaces them there.
   Recommended: yes, both.
2. **A fifth, `SUREFOLD_TIME_BY_HAND`, for `System.currentTimeMillis`, `nanoTime` and `Instant.now`?** A
   `TestClock` can't move them. But reading the time to log it or measure a benchmark is correct, so it would be
   noisy. Recommended: not now. Revisit if review keeps finding it.
3. **`ConcurrentHashMap`?** It is often fine as a cache, and `Shared` is a transaction. Recommended: no, for the
   same reason as atomics.
4. **Severity.** Warnings fail the build only where a project sets `allWarningsAsErrors`. Recommended: warnings,
   and say in `docs/app.md` that `-Werror` makes them blocking.
