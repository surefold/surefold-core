---
name: surefold
description: Use whenever code depends on surefold (dev.surefold:surefold* in a build file, or a dev.surefold import), and load it before writing any concurrency, actor or test code there: a thread, an executor, a latch, a lock, `synchronized`, an atomic, a future, a blocking queue, a sleep or a poll, a timeout, a race, a `try`/`catch` or a `throw`, as well as a scheduler, a background job, a retry or backoff, a circuit breaker, a test wait, a config read, a secret, a resource's close, a database migration, a SurefoldApp's run, logging, metrics or tracing. surefold already ships each of these; this says which call to use instead of the JDK's.
---

# surefold

surefold is structured concurrency, resources, streams, actors and application wiring
on the JDK's virtual threads, with Surefold's own `Fail` and `Either` as the error model.
Blocking is fine everywhere: every fork is a virtual thread, so there is no
`suspend`, no dispatcher and no `CompletableFuture` to reach for.

Before writing any of the code below, use the surefold call beside it. The JDK's
concurrency primitives are what surefold is built on, not what code on surefold
uses: a thread, latch, lock or future written by hand belongs to no scope, so its
failure never reaches the caller and it can outlive the code that started it. If a
row below is missing what you are about to write, look in `llms.txt` and the core
guide before reaching for `java.util.concurrent`.

## Concurrency: before you reach for the JDK

| About to write | Use instead |
|---|---|
| `Thread { }`, `Thread.ofVirtual()`, `Thread.ofPlatform()`, an `ExecutorService` | `scope { async { … } }`; the scope joins it, and its failure reaches you |
| `executor.invokeAll`, a loop of futures over a list | `parMap`, `parTraverse`, or `traverse`/`mapOrAccumulate` to keep every error |
| `CountDownLatch`, a `CompletableFuture` used once as a signal | `Promise<E, A>`; a latch is a `Promise<Nothing, Unit>`, awaited with `awaitValue()` |
| `synchronized`, a `ReentrantLock`, `AtomicReference` or a `ConcurrentHashMap` as shared state | `Shared(value)`, changed with `update`/`modify`, or several together in `transact { }`; `awaitUntil` to wait on it |
| `BlockingQueue` between producer and consumer | `channel<A>(capacity)` in a scope, and `select { }` to wait on several |
| `Future.get(timeout)`, a timer thread to give up | `timeout(d) { … }` or `timeoutOrNull(d) { … }` |
| first answer wins, `CompletableFuture.anyOf` | `raceN(a, b)`, or a `Promise` completed by whichever fork answers first |
| a `Semaphore` to cap concurrent calls | `policy { guard(Bulkhead(name, maxConcurrent)) }` |
| `try`/`finally` closing several things | `resourceScope { install(acquire) { it, _ -> release } }` |
| `System.currentTimeMillis()`, `System.nanoTime()`, `Instant.now()` | `clock.get().now()`, so a `TestClock` can move it |
| a `ThreadLocal` | `SurefoldLocal`, which crosses into forks |
| a `CompletableFuture` from or to other code | `future.await()`; `surefoldFuture { … }` to hand one back |
| a hand-rolled mailbox, a thread per stateful object | an actor: `behaviour` and `spawn` from `surefold-actor` |

## Errors: before you reach for `try`/`catch`

A failure the caller was promised is a value: a `Fail<E>` or an `Either<E, A>`, never an exception it has to know to
catch. Throwing is for what nobody declared, a bug or a broken dependency, and that goes up on its own.

| About to write | Use instead |
|---|---|
| `throw SomeException(…)` for an outcome the caller should handle | `fail(error)` inside `attempt { … }` or a `Fail<E>` scope, with `E` a sealed type |
| `if (!ok) throw …`, `value ?: throw …` | `ensure(ok) { error }`, `ensureNotNull(value) { error }` |
| `try { … } catch (e: SomeException) { … }` to turn one exception into a result | `Either.catchOrThrow<SomeException, _> { … }` (snapshots after 0.18.0; on 0.18.0, `Either.catch` and check the type); anything else goes on up unchanged |
| `try { … } catch (e: Exception)` or `Throwable` | `Either.catch { … }` at a boundary only; it rethrows fatals, interrupts and a `fail` on its way out |
| `catch (e: InterruptedException) { }` | nothing: let it go up, so cancellation works |
| a nullable or a hand-written `Result` class as the return | `Either<E, A>`, or a function on `Fail<E>` |
| `catch` to fall back to a default | `recover({ … }) { error -> fallback }`, or `getOrElse` on the `Either` |
| `try`/`catch` around each item to collect every error | `mapOrAccumulate` or `zipOrAccumulate` |
| an `Either` unwrapped with `when` to rethrow | `.bind()` inside a `Fail<E>` scope |
| `try`/`finally` to close something | `resourceScope { install(acquire) { it, _ -> release } }` |
| `catch` and retry in a loop | `policy("…") { retry(Schedule.exponential(…)) }` |

## Testing: before you reach for the JDK

| About to write | Use instead |
|---|---|
| a JUnit class with `@BeforeAll`/`@AfterAll` holding a resource | an `object … : Suite()` from `surefold-junit`, its resource shared by its tests |
| `assertEquals`, `assertTrue(a == b)` | `shouldBe`, `shouldBeRight`, `withClue` from `surefold-assert` |
| a latch or a sleep to wait for something | `eventually(within) { … }`, or `Promise.await(within)` |
| several runs hoping to hit a race | `explore(runs) { … }` and `replay(order) { … }` from `surefold-replay` |
| real threads to test actors | `behaviour.test()` or `testActors { }`; `Ordering.seeded(seed)` and `Faults(…)` for other orders and lost messages |
| a second node on a thread and latches | each node in its own `scope`, started in an `async` fork; a `Promise` says it is up; `cancel()` closes it |
| reading log output by hand | `capturingLogs { … }` and `capturingMetrics { … }` |

## Applications: before you write it

| About to write | Use instead |
|---|---|
| `object Main { fun main() … }` wiring things by hand | a `Module` graph and `object App : SurefoldApp<Root>()`, run by `runApp(App)` |
| `run` that calls `server.block()` or `join()` | `awaitShutdown()`; the graph's release stops the server after `run` returns |
| `{ x -> x.close() }` as a node's release | `closingOf(::Thing)` |
| a class that closes what it built | each resource as its own node; the graph releases them in reverse order |
| `Executors.newScheduledThreadPool`, `Timer`, a polling thread | `Stream.tick(every)` started on the graph's `StreamBackend` (`Forks()`) |
| a retry loop, `Thread.sleep` between attempts | `Schedule.exponential(…)` or `Schedule.spaced(…)`, in a `policy { retry(…) }` |
| a down-detector, a failure counter around a client | `policy("…") { guard(CircuitBreaker("…", maxFailures, resetAfter)) }` |
| a stream that dies on one bad dependency call | `restartOnDefect(schedule)` |
| `ConfigFactory.load().getString(…)` in a node | `config<Settings>("section") { Settings(string(…), duration(…)) }` from surefold-integrations' `surefold-app-typesafe` |
| `string("password")` | `secret("password")`, a `Secret` that prints as a mask |
| `if (config.getString("kind") == …)` choosing a module | `choice<Settings, Provided> { chosen -> … }` or `conf.choosing("section", read) { chosen -> … }` |
| `CREATE TABLE IF NOT EXISTS` at start-up | `migrations("db/changelog.sql")` from surefold-integrations' `surefold-app-liquibase`; readers take a `Migrated` |
| a startup wait for a dependency | `.probe(name, timeout, attempts, interval) { … }` |
| a logger or `MeterRegistry` passed into a class | `logInfo(…)`, `counter(…).increment()`, `timed(…) { … }`; put surefold-integrations' `surefold-slf4j` and `surefold-micrometer` on the classpath |
| MDC or a trace context carried by hand across threads | `logAnnotated(…) { … }`; surefold-integrations' `surefold-otel` on the classpath |
| `Thread.sleep` in a test | `eventually(within) { … }` from `surefold-test` |
| a test of something on a timer | `TestStreams` and `TestClock` from `surefold-stream-test` |
| a fake swapped in by hand | `.overriding(single<T> { … })`, and `.subgraph<Root>()` for a smaller graph |
| `runCatching` | catch the exception you expect, by name, or `fail` with a declared failure |

Apply the wiring plugin, `id("dev.surefold.wiring")`. It checks
every graph as it compiles and warns on four of these by name:
`SUREFOLD_RUN_NEVER_RETURNS_ON_SHUTDOWN`, `SUREFOLD_SCHEDULER_BY_HAND`,
`SUREFOLD_SECRET_AS_STRING` and `SUREFOLD_SLEEP_IN_TEST`. Fix the code rather than
suppressing one, unless the call really means it.

## Modules

All on Maven Central as `dev.surefold:<module>`, on one version.

- `surefold`: forks, resources, `Schedule`, `timeout`, `policy`, `Clock`, `TestClock`, logs and metrics, `capturingLogs`, `capturingMetrics`, `logger.unbound()` for what `main` reads.
- `surefold-replay`: `explore` and `replay`, a test's forks in seeded orders.
- `surefold-compiler`: a checker for forks that outlive their scope; plugin `dev.surefold.compiler`.
- `surefold-cache`: a cache with one lookup per key and stale values while the source fails.
- `surefold-stream`: `Stream<E, A>`, described once and run on a backend.
- `surefold-stream-forks`: the usual backend, a pull loop on a virtual thread.
- `surefold-stream-actors`: the `surefold-actor` backend.
- `surefold-stream-test`: `TestStreams` and `TestClock`.
- `surefold-test`: `story`, `eventually`.
- `surefold-junit`: `Suite` objects run by a JUnit Platform engine.
- `surefold-junit-testkit`: `shouldHaveSucceeded`, `shouldHaveFailed` and `failureOf` on a suite's run.
- `surefold-app`: the graph, `SurefoldApp`, `closingOf`, probes, `testApp`, `Migrated`.
- `surefold-app-actor`: a `surefold-actor` scope and actors as nodes.
- `surefold-app-gradle`: the wiring plugin, and the plugin that applies `surefold-compiler`.
- `surefold-app-compiler`: the checker behind the wiring plugin.
- `surefold-assert`: checks for tests, `shouldBe` to `shouldBeRight`, failing at the test's line.
- `surefold-actor`: actors as values on virtual threads.
- `surefold-actor-journal-jdbc`: an actor's journal on JDBC.
- `surefold-actor-projection`: a journal as a resumable `Stream`.
- `surefold-actor-remote`: actors across nodes.
- `surefold-cluster`: gossip membership and downing.
- `surefold-cluster-saga`: durable sagas, replayed after a crash.

Jobs, files, tenancy, auth, idempotency, rate limits, Postgres notifications and their caches, per-tenant flags and Flyway migrations are in [surefold-platform](https://github.com/surefold/surefold-platform).
Every module that wraps a library outside the JDK and Kotlin (Pekko, Kafka, SQS, SNS, Typesafe Config, kotlinx configuration, Liquibase, OpenFeature, the remote codecs, cluster discovery, SLF4J, Micrometer, OpenTelemetry and Arrow) is in [surefold-integrations](https://github.com/surefold/surefold-integrations).

## More

`llms.txt` at https://github.com/surefold/surefold-core/blob/main/llms.txt has the
facts, the full table and links to every guide; `docs/cookbook.md` has one
recipe per application task, and `docs/core.md` covers retries, timeouts and
policies.
