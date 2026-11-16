# 0314 — Checker tests without a Gradle build

## Problem

`surefold-app-gradle`'s `SurefoldWiringPluginTest` has 44 tests, and each one runs a Gradle build through TestKit: a
project written to a temporary directory, a Gradle daemon, then a compile. For 37 of them, Gradle is only the way to
reach the compiler. They test what `surefold-app-compiler`'s checker says about a source:

- 19 on graph findings;
- 13 on the hand-rolled warnings;
- 5 on `transact`.

Only 7 test something Gradle does: the wiring task, the test task's colour settings and the diagram (6), and
`failOn`, which fails a build on a warning. Two of the 37 should also stay as end-to-end proofs that the plugin
hands the checker `verbose` and `test` (question 1).

The rest pay for a daemon and a project directory per test, which makes this module one of the slowest in the build.

`surefold-compiler` already tests its checks the cheaper way: `Compiling.kt` runs the Kotlin compiler in-process on
a snippet. 0313's second entry would have moved all 44 onto `Suite` and `withTempDirectory` where they are. This spec
replaces that entry: move the 37, then convert the 9 that stay.

0313 also said `surefold-app-compiler`'s tests compile on 21. They don't: its build file compiles and runs them on
25, as `surefold-app-gradle`'s does. So it can take `surefold-junit` too.

## Not doing

- **Changing what a test checks, or its name.** Each moves with its sentence and its checks.
- **`SurefoldForksPluginSuite`.** It is the end-to-end test of the other plugin, and stays a TestKit build.
- **Compiling without files.** The compiler reads sources from disk. A scratch directory per compile, made and
  deleted inside the helper as `Compiling.kt` does, is not a fixture a test manages.
- **Kover for the compiler modules.** They stay outside the floor.

## Shape

A `compile` helper in `surefold-app-compiler`'s tests, on the shape of `surefold-compiler`'s:

```kotlin
internal fun compile(source: String, test: Boolean = false, verbose: Boolean = false): Compiled
// K2JVMCompiler in-process, this module's classes as the plugin, surefold-app and surefold-app-actor on the
// classpath, and -P plugin:<PLUGIN_ID>:test=… and :verbose=… as the Gradle plugin passes them.

object HandRolledSuite : Suite() {
    init {
        test("a lock taken by hand is named, in main and test code, with Shared beside it") {
            listOf(false, true).forEach { test ->
                val said = compile(LOCKS, test = test).surefold
                said shouldContain "synchronized { } guards shared state with a lock by hand"
            }
        }
    }
}
```

The 37 move into three suites in `surefold-app-compiler`: `GraphFindingsSuite`, `HandRolledSuite` and
`TransactSuite`. The fixture sources they use move with them. The 7, and the two proofs, stay in `surefold-app-gradle` as one
`WiringPluginSuite(concurrent = false)` using `withTempDirectory`. The root build drops `surefold-app-compiler` from
`compilerPlugins`' `surefold-junit` exemption.

## Why this shape

A checker test should run the checker, not a build tool. In-process compiles should take the 37 from a daemon each to
well under a second each, and they can run concurrently. The 9 still prove the plugin wires the checker into a real
build. **Alternative:** keep all 44 as TestKit builds and share one project per suite. That's fewer moves, but it
keeps the daemon cost and makes tests depend on each other's files. Recommended: move them.

## Stack

- [ ] **`spec-0314-compile`**: `surefold-junit` for `surefold-app-compiler`'s tests; the `compile` helper; `VersionsTest`
      to a suite; `TransactSuite`, the smallest group, moved in.
      Done when: the 5 transact tests pass in `:surefold-app-compiler:test` under their names, and are gone from
      `surefold-app-gradle`.
- [ ] **`spec-0314-hand-rolled`**: the 13 hand-rolled tests moved.
      Done when: the same, for those 13.
- [ ] **`spec-0314-findings`**: the 19 graph-finding tests moved.
      Done when: the same, for those 19.
- [ ] **`spec-0314-plugin`**: the 9 that stay become `WiringPluginSuite`, with no `@TempDir` left in either module.
      Done when: all 44 names pass, two of them in both modules, and `grep -rn @TempDir app/` finds nothing.

## Acceptance

```bash
./gradlew :surefold-app-compiler:test :surefold-app-gradle:test
grep -rn "@TempDir\|org.junit.jupiter.api.Test" app/surefold-app-compiler/src/test app/surefold-app-gradle/src/test || echo none
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Which two tests stay as the end-to-end proofs of `verbose` and `test`?** Recommended: "the checker runs inside
   the compiler, which is what puts a fault in the editor" for `verbose`, and "a sleep in a test is named, with
   eventually beside it" for `test`. The in-process copies of both also stay, under the same sentences in the
   compiler module.
2. **Concurrent compiles?** `K2JVMCompiler` in-process is meant to be re-entrant, but the plugin's registrar holds
   state. Recommended: `Suite()`'s default concurrency, falling back to `concurrent = false` if a test shows shared
   state.
3. **0313's second entry.** Recommended: mark it superseded by this spec, and leave 0313 otherwise as written.
