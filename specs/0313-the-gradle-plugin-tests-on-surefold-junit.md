# 0313 — The Gradle plugin's tests on surefold-junit

## Problem

`surefold-app-gradle`'s tests are the last in the repo written the way `AGENTS.md` now says not to copy: Jupiter
classes, with a `@TempDir` parameter on each of 43 tests. `SurefoldWiringPluginTest` alone is 1,107 lines and 41
tests, and it is the class a new compiler warning's tests are added to. surefold/surefold-core#1111 added four there in the same style,
because nothing else was available in that module.

The module is on Jupiter because spec 0197 left it there: "`surefold-app-gradle` and `surefold-app-compiler`
build for JDK 21, and `surefold-junit` is built for 25." The root build enforces this with
`testedByRunningABuild`, which keeps `surefold-junit` off the module's test classpath.

That reason no longer holds for this module's tests. Its build file now compiles and runs them on 25 ("What runs
inside the build tools stays at 21, but only the main code does"). Only the plugin itself targets 21. A probe
confirmed it on 2026-11-16: with `surefold-junit` on the test classpath, an `object … : Suite()` ran a TestKit build
in a `withTempDirectory` directory and passed, beside the Jupiter classes.

## Not doing

- **`surefold-app-compiler`.** Its tests compile snippets on 21, inside the build tools' JDK, so 0197's reason
  still holds there.
- **Changing what a test checks, or its name.** As 0197: each test keeps its sentence and its checks.
- **Kover.** `testedByRunningABuild` modules stay outside the coverage floor. Only the `surefold-junit` exemption
  changes.
- **Speeding up the TestKit builds.** Question 1 is about not making them slower or flakier.

## Shape

The root build stops exempting the module from `surefold-junit`, keeping every other exemption:

```kotlin
if (name !in surefoldJunitRunsOn + compilerPlugins + benchmarkModules) { … } // testedByRunningABuild dropped
```

Each class becomes a suite, its fixture helpers top-level functions taking a `Path`:

```kotlin
object WiringWarningsSuite : Suite(concurrent = false) {
    init {
        test("a thread or pool started by hand is named, in main and test code, with scope async beside it") {
            withTempDirectory { dir ->
                project(dir, "", SOUND)
                source(dir, "main", THREADS)
                runner(dir, "compileKotlin").build().output shouldContain "starts threads by hand"
            }
        }
    }
}
```

`SurefoldWiringPluginTest` splits by subject into suites of a reviewable size: the task and its failures, the
checker's graph findings, the hand-rolled warnings, and transactions. They share the fixtures in a `Fixtures.kt`.

## Why this shape

One way of writing tests across the repo is what `AGENTS.md` and the plugin's skill now tell agents to copy. This
module is where new compiler warnings get tested, so it's the one copied most. **Alternative:** keep Jupiter and
replace only `@TempDir` with a hand-rolled helper. That's fewer lines, but it leaves the second way of writing
tests in place. Recommended: move it.

## Stack

- [ ] **`spec-0313-fixtures`**: `surefold-junit` on the module's test classpath; `project`, `runner`, `source` and the
      fixture strings move to `Fixtures.kt`, taking a `Path`; `SurefoldForksPluginTest` and
      `ReaderKnowsFactoriesTest` become suites.
      Done when: `:surefold-app-gradle:test` runs the same test names, those two classes through the surefold engine.
- [ ] **`spec-0313-wiring`**: `SurefoldWiringPluginTest` becomes the four suites, with no `@TempDir` left in the module.
      Done when: the same 44 test names pass through the surefold engine, and `grep -r @TempDir app/surefold-app-gradle`
      finds nothing.

## Acceptance

```bash
./gradlew :surefold-app-gradle:test
grep -rn "@TempDir\|org.junit.jupiter" app/surefold-app-gradle/src/test || echo none
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Concurrent or one at a time?** Each test starts a TestKit build, which is a Gradle daemon of its own. Jupiter
   runs them one at a time today. A suite's default would run up to one per core, which is faster but uses several
   daemons' worth of memory on a 4-core runner. Recommended: `Suite(concurrent = false)`, then measure concurrency
   separately. Taken as recommended when the spec was built (2026-11-16).
2. **The 10-minute timeout.** The build file sets `surefold.junit.timeout.default` to 10m already, beside
   Jupiter's. Recommended: keep it, and drop the Jupiter property once nothing in the module uses Jupiter. Taken as recommended when the spec was built (2026-11-16).
3. **0197's record.** Recommended: leave 0197 as written, and name this spec in the root build's comment where the
   exemption was. Taken as recommended when the spec was built (2026-11-16).
