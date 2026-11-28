# 0324 — Gens that hit gotchas first

## Problem

A suite today only has fixed cases (`testEach`) or a hand-built list (`forAll` over an
`Iterable`). There is no way to draw random inputs, compose them into domain types, or
run a property across many samples. Authors either invent ad-hoc `Random` loops (no seed,
no edges) or skip the checks that catch empty strings, range ends and the other common
gotchas. Spec 0244 deferred generated inputs and shrinking to a later property-testing
spec; this is that cut, without shrinking.

## Not doing

- Shrinking a failing sample.
- Suite/`testEach` containers per sample, or wiring `check` into `Expectations`.
- Auto-derive for data classes; a `Sized` test aspect.
- Folding into `surefold-test` or `surefold-junit`: own module and package.
- Pulling in Kotest/jqwik/ScalaCheck.

## Shape

New module `core/surefold-gen`, package `dev.surefold.gen`, depends on `surefold` only.

```kotlin
import dev.surefold.gen.Gen
import dev.surefold.gen.check

data class Person(val name: String, val age: Int)

val people: Gen<Person> =
    Gen.string.zip(Gen.int(1..120)) { name, age -> Person(name, age) }

val one = people.sample()
val fixed = people.sample(seed = 42L)

check(people, samples = 100) { person ->
    require(person.age in 1..120)
}
```

- **`Gen<A>`:** `map`, `flatMap`, `zip`, `filter` (prefer map/flatMap), `const`, `oneOf`,
  `from(iterable)`. Built-ins: `int` / `int(range)`, `long`, `boolean`, `double`,
  `string` (optional max length), `list(gen)` (optional max size).
- **`sample(seed?)`:** one value. Same seed → same value for that gen.
- **Edges first:** every gen carries a small ordered gotcha list. `check` runs edges
  (capped) before random fills to `samples`. Ints: `MIN`/`-1`/`0`/`1`/`MAX` and range
  ends. Strings: empty, whitespace, newline/tab, long-ish, non-ASCII. Lists: empty,
  singleton, near max; elements from the element gen. Composed gens product/concat edges
  in a bounded way.
- **`check`:** overloads for one or two gens (zip for more). Default `samples = 100`.
  Optional `seed`. Stops on first failure; message names seed, sample, and whether it
  was an `edge`; cause is the thrown assertion/error. Replay with `sample(seed)` /
  `check(..., seed = …)`.

Wiring: `settings.gradle.kts`, POM description, `docs/modules.md`, `llms.txt` /
skills Modules line, `NoOtherDependenciesTest` (see `docs/agents/new-module.md`).

## Why this shape

ZIO-shaped `Gen` + `check` transfers cleanly; `sample` covers fixtures without a
property loop. **Own module** keeps `surefold-test` assertion-free and avoids fattening
junit. **Edges before random** finds empty/boundary bugs in the first samples instead of
hoping the PRNG lands on them. **Alternative:** Suite-only `check` with per-sample
reporting — better IDE tree, but forces the engine for every property; deferred.
**Alternative:** shrink in this spec — too much for one stack; follow-up.

## Stack

- [ ] **`spec-0324-gen`** — module, `Gen`, combinators, built-ins, edges, `sample`.
      Done when: `:surefold-gen:check` proves `sample(seed)` is stable, `map`/`zip` build
      a domain type, and string/int gens expose the documented edges.
- [ ] **`spec-0324-check`** — `check`, edge-then-random, failure message with seed/sample.
      Done when: a property that rejects `""` fails on an edge before random; a seeded
      `check` replays the same first random sample; message includes seed and value.
- [ ] **`spec-0324-docs`** — `docs/testing.md` fence + `modules.md` / agent module lists.
      Done when: the guide fence compiles under `CoreGuidesSuite` and
      `./gradlew :surefold-gen:check` is green.

## Acceptance

```bash
./gradlew :surefold-gen:check
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Default string max length / list max size?** **Answered: 32 / 16.**
2. **Cap how many composed edge products `check` runs?** **Answered: at most 32** before
   random.
3. **Should `surefold-junit` depend on `surefold-gen` later for a Suite helper?**
   **Answered: deferred**; `check` throwing is enough for this spec.
