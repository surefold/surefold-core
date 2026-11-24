# 0317 — A saga called off while it runs

## Problem

A durable saga (0299, 0316) can only end by its own body: done, refused, or stuck. Nobody outside can stop one that
should no longer go ahead, such as a payout whose customer withdrew the claim while it waits days for an approval.
Today the only way is for the body to poll a flag of its own between steps, which every saga writes again and which a
saga parked in `receive` never reaches until its deadline.

## Not doing

- **Interrupting a step in flight.** A cancel takes effect at the body's next boundary; a step already running
  finishes and is undone with the rest.
- **Cancelling a saga that has not started.** It answers `Unknown`, as a signal does (0316).
- **Taking a cancel back.** Once seen by the body it is journalled, and the saga ends `Cancelled`.
- **A typed reason, bulk cancel, or an admin page.** The reason is a `String`; an admin page is a later spec.

## Shape

In `surefold-cluster-saga`:

```kotlin
import dev.surefold.cluster.saga.Delivered
import dev.surefold.cluster.saga.Durable

running.cancel("claim-81", reason = "customer withdrew", dedupe = "ops-1234") // Either<AskFailure, Delivered>

when (val outcome = running.await("claim-81", within = 10.seconds)) {
    is Durable.Outcome.Cancelled -> log("called off: ${outcome.reason}")
    else -> Unit
}
```

- **`cancel(id, reason, dedupe)`** answers as `signal` does: `Accepted` (kept for a saga that has not ended),
  `Ended(outcome)`, or `Unknown`. A repeat with the same `dedupe` is answered `Accepted` and kept once. On one node,
  `Durable.cancel(journal, id, reason, dedupe)` does the same.
- **A cancel is kept in the saga's inbox** (0316) under a reserved name, so it wakes a parked saga exactly as a signal
  does, and it is never taken by `receive`.
- **The body sees it at its next boundary**: a `step`, `receive`, `now()` or `uuid()` at a position the journal does not
  yet hold reads the inbox first. Finding a cancel, it journals `Cancelling(reason)` there and calls nothing more.
  Replay meets that record at the same position, so a resumed saga goes straight to undoing.
- **Every step that ended is undone**, newest first, journalled as a refusal's undos are, and told why through
  `Undo.Cancelled`. The saga ends `Durable.Outcome.Cancelled(reason)`, which `await` answers from any node and a later
  `signal` or `cancel` answers as `Ended`.
- **A saga already undoing** after a refusal ignores a cancel and ends `Refused`. An undo that keeps failing still
  leaves it `Stuck`, released by `resume` as before.

## Why this shape

A cancel through the inbox reuses everything 0316 built: one journal per saga that the entity alone writes, the wake
of a parked saga, the dedupe and the `Delivered` answers. **Alternative:** a command the entity applies by
interrupting its runner. It lands sooner, but leaves the interrupted step unknown, needing its `check` and leaving a
step without one `Unsettled`. Recommended: the inbox, read at each fresh boundary, so a cancel costs one inbox read
per step and nothing is ever half done.

## Stack

- [x] **`spec-0317-one-node`** ([#1141](https://github.com/surefold/surefold-core/pull/1141)) — `Durable.cancel`, the boundary check, `Cancelling` and `Cancelled` journalled,
      `Outcome.Cancelled`, undos told `Undo.Cancelled`.
      Done when: a saga cancelled before its second step undoes the first and ends `Cancelled`; one cancelled while
      parked in `receive` does the same when resumed; one already undoing a refusal ends `Refused`; and a node dying
      at every append after the cancel ends the saga `Cancelled` with each undo run once.
- [x] **`spec-0317-cluster`** ([#1142](https://github.com/surefold/surefold-core/pull/1142)) — `Sagas.cancel` from any node, and `Cancelled` across the wire to `await`.
      Done when: a three-node test cancels a saga parked in `receive` from another node, and every node awaits it as
      `Cancelled` well before its deadline, with its hold undone.
- [ ] **`spec-0317-docs`** — a withdrawn claim added to the `docs/cluster.md` approval section, with a compiled fence.
      Done when: `GuideExampleTest` compiles it.

## Acceptance

```bash
./gradlew :surefold-cluster-saga:test
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **What does an undo see as its `why`?** Answered (maintainer, 2026-11-24): the core's existing `Undo.Cancelled`, holding an
   `InterruptedException` whose message is the reason, so `surefold` itself does not change. The alternative is a new
   `Undo` variant carrying the reason, which breaks every exhaustive `when` over `Undo`.
2. **The inbox read at every fresh boundary.** Answered (maintainer, 2026-11-24): accept it; replayed positions read nothing, and a saga
   takes few steps.
3. **A cancel that arrives after the body's last boundary**, while it returns its answer? Answered (maintainer, 2026-11-24): `Accepted`, and
   the saga ends `Done`, as 0316 question 4 settled for signals; a caller that needs the result follows with `await`.
4. **A cancel to a `Stuck` saga?** Answered (maintainer, 2026-11-24): `Accepted`. A saga stuck before a step sees it on `resume` at its next
   boundary; one stuck undoing keeps undoing and ends as that undo would have.
5. **The reserved name.** Answered (maintainer, 2026-11-24): the cancel is kept as `surefold:cancel`, and `SagaSignal` refuses a name that
   starts with `surefold:`.
6. **A metric.** Answered (maintainer, 2026-11-24): count `surefold.saga.cancelled` by kind, beside `surefold.saga.stuck`.
