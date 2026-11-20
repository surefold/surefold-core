# 0315 — Architecture rules that fail compilation

## Problem

A service's dependency rules ("the app layer does not touch the SQL layer") are kept today by ArchUnit tests or by
regex scans in the build. The first consumer, a private service, does the second: `appSqlRules` in
`buildSrc/.../AgentHygiene.kt` counts `import shop.store.adapters.sql.` and `import ch.qos.logback.` lines in
`app/src/main`, against 95 `app-sql-import` entries in `gradle/hygiene-counts.allowlist`. A regex misses a fully
qualified call, a supertype, a type alias or a star import, and either kind of check fails a later task, never the
editor. An agent writes the violation, gets a green compile, and learns about it at `check`, if at all.

Surefold already ships two K2 checker plugins (`surefold-compiler`, `surefold-app-compiler`). A third, generic one can
resolve what each file actually references and make a broken rule a compile error.

## Not doing

- **Any rule but dependencies.** No naming, layering cycles, annotation or "classes in X are final" rules. Later specs.
- **Any one service's knowledge.** The plugin is generic; a service's rules live in its own build.
- **Java sources, and what inline or generated code references.** Kotlin source as written, as K2 resolves it.
- **Counts per file.** The baseline is file + rule (question 2).
- **Migrating the first consumer.** Its own spec, in its own repository, once this ships to a snapshot.

## Shape

A module `core/surefold-arch-compiler` (plugin id `dev.surefold.arch`), shaped like `surefold-compiler`: a
`CommandLineProcessor`, a `CompilerPluginRegistrar` that checks `builtForThisCompiler()`, a `FirExtensionRegistrar` that
adds checkers only. Its Gradle plugin `dev.surefold.arch` lives in `surefold-app-gradle` beside `SurefoldForksPlugin`.
The first consumer's two rules, in its `app/build.gradle.kts` (package names changed here as `shop`):

```kotlin
plugins { id("dev.surefold.arch") }

surefoldArch {
    baseline = file("gradle/arch-baseline.txt")
    rule("app-sql-import", "application code reaches the database through a store port") {
        code("shop.app..")                      // where the rule applies; `..` = the package and below
        mustNotUse("shop.store.adapters.sql..")
    }
    rule("app-logback", "only the JSON encoder binds the app to the logging backend") {
        onlyIn("shop.app.observability")       // a package pattern or a top-level class
        mayUse("ch.qos.logback..")
    }
}
```

Each rule reaches the compiler as one option, `-P plugin:dev.surefold.arch:rule=<id>|<kind>|<from>|<to>|<reason>`
(`kind` is `forbid` or `only`). The baseline file is registered on the compile task as an input file, so the build
cache keys on its content; its path, and the project directory baseline paths are relative to, go as
`InternalSubpluginOption`s: not inputs, so a cache entry moves between checkouts. (A `FilesSubpluginOption` was the
draft's plan, but outside kapt Kotlin 2.4's Gradle plugin keys one on its path string, and a baseline edit left the
compile up to date.) Rules apply to the main compilation only unless a rule says `compilations("test")`. The
baseline is a checked-in text file:

```text
# path relative to the project directory, then rule id; a comment says why it is still here
src/main/kotlin/shop/app/Bootstrap.kt app-sql-import  # why this file still breaks it
```

A reference is the resolved symbol behind: a type ref anywhere (property, parameter, return, supertype, type argument,
cast), a call, property access, constructor or callable reference, an annotation, and an import, alias included. A type
alias counts as both itself and its expansion. Three diagnostics, all errors:

| Diagnostic | On | Says |
|---|---|---|
| `SUREFOLD_ARCH_FORBIDDEN` | each offending reference | `[app-sql-import] shop.app.Bootstrap uses shop.store.adapters.sql.Tx; application code reaches the database through a store port. Fix the reference, or baseline it: 'src/main/kotlin/shop/app/Bootstrap.kt app-sql-import' in gradle/arch-baseline.txt` |
| `SUREFOLD_ARCH_STALE` | the file's package directive | `[app-sql-import] Bootstrap.kt no longer breaks this rule: delete its line from gradle/arch-baseline.txt` |
| `SUREFOLD_ARCH_CONFIG` | the message collector | a rule that does not parse, a duplicate id, a baseline line naming an unknown rule or a missing file |

The compiler prints file:line itself. Stale is a per-file check, so it holds under incremental compilation: a baselined
file is analysed again exactly when it changes. A baseline line for a deleted file is caught at registration, which
runs on every compile.

## Why this shape

FIR checkers rather than an IR pass: no backend runs in the editor, so only a checker shows the error as the line is
typed, as `surefold-app-compiler`'s do (docs/app.md, the registry flag). Rules as options rather than a rules file:
options are already task inputs, and the IDE receives them from the Gradle import with nothing to find on disk.
**Alternative:** one checked-in rules file passed by path, readable without Gradle, but a second format to parse and
keep in step with the DSL. Recommended: options. Errors rather than 0312's warnings: the point is that a violating
module does not compile, and the baseline is the escape hatch, not `@Suppress`.

## Stack

- [x] **`spec-0315-module`** (surefold/surefold-core#1121) — the module, options, rule parsing,
      `SUREFOLD_ARCH_FORBIDDEN` on calls, property accesses and constructors; `Compiling.kt` and `ForbiddenSuite` of
      failing snippets.
      Done when: a snippet in `a..` calling `b.f()` under `mustNotUse("b..")` fails with the message above, an
      `onlyIn` rule passes inside and fails outside, and a malformed rule is `SUREFOLD_ARCH_CONFIG`.
- [x] **`spec-0315-references`** (surefold/surefold-core#1122) — type refs, supertypes, type arguments, annotations,
      callable references, imports and aliases, type-alias expansion.
      Done when: one snippet per reference kind fails, and a fully qualified call with no import fails.
- [x] **`spec-0315-baseline`** (surefold/surefold-core#1123) — the baseline file, `SUREFOLD_ARCH_STALE`, missing-file
      and unknown-rule checks.
      Done when: a baselined file compiles, a new file with the same reference fails, a baselined file that no longer
      breaks the rule fails as stale.
- [x] **`spec-0315-gradle`** (surefold/surefold-core#1124) — `dev.surefold.arch` and `surefoldArch { }`,
      docs/modules.md row, a guide section in `docs/app.md` (a build-script fence, which no guide suite compiles).
      Done when: a TestKit build fails on a violation; editing only the baseline reruns `compileKotlin` (not
      `UP-TO-DATE`, not `FROM-CACHE`) and reports a stale entry in a file whose source did not change.

## Acceptance

```bash
./gradlew :surefold-arch-compiler:test :surefold-app-gradle:test --tests '*Arch*'
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Approved by the owner on 2026-11-20, every question taken as recommended.


1. **Does changing a rule or the baseline recheck untouched files under incremental compilation?** Kotlin's IC is
   expected to rebuild the module when plugin options or their input files change; unverified. Recommended: the last
   entry's TestKit test proves it, and if it fails the Gradle plugin marks the compile task non-incremental on a
   changed baseline hash. Taken as recommended (owner, 2026-11-20). Answered while building: editing only the
   baseline reruns `compileKotlin` as a full rebuild, every source recompiled, so nothing more is needed.
2. **Baseline by file + rule, or with the first consumer's counts?** A count stops a baselined file gaining a second
   violation unseen; file + rule is what the owner asked for and is simpler. Recommended: file + rule now; counts if
   review finds baselined files growing. Taken as recommended (owner, 2026-11-20).
3. **Where does the module live?** `core/` beside `surefold-compiler` (generic build tooling), or a new family.
   Recommended: `core/surefold-arch-compiler`; the Gradle plugin in `surefold-app-gradle`, as
   `dev.surefold.compiler`'s is. Taken as recommended (owner, 2026-11-20).
4. **IDE and the baseline.** The editor reads the baseline when the Gradle import builds the session, so a line added
   to it shows only after a re-sync. Recommended: accept it and say so in the guide; the build is the gate. Taken as
   recommended (owner, 2026-11-20).
5. **Patterns.** ArchUnit's `..` package suffix plus exact top-level class names, or globs on file paths too.
   Recommended: packages and classes only: files move, packages are the architecture. Taken as recommended (owner,
   2026-11-20).
