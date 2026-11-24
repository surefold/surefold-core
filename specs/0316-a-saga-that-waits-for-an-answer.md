# 0316 — A saga that waits for an answer

## Problem

A durable saga (0299) can only call things that return. A process that has to wait days for a person, such as a
payout held until someone approves it, is today written as two sagas and a job (0216): the first holds the money and
ends "awaiting approval", the approval handler starts the second, and a job fires the timeout. Undo does not cross the
split, so releasing the hold after a rejection or a timeout is written by hand, and so is deciding between an approval
and a timeout that arrive together. A service on core alone has no jobs at all, and can only block the body's thread
for the whole wait, keeping its entity in memory until the answer comes.

## Not doing

- **`sleep`.** The only timer is `receive`'s deadline. A plain sleep is a later spec, once someone needs one.
- **Signals before the saga starts.** A signal for an id with nothing journalled is refused as `Unknown`, never kept.
- **Cancel, queries, inspect or an admin page.** Cancel is a later spec on this inbox.
- **Versioning.** A body changed under a waiting saga still stops as `Diverged` (0299 question 2).
- **A second journal backend.** The inbox is one more persistence id on the cluster's journal.

## Shape

In `surefold-cluster-saga`:

```kotlin
import dev.surefold.cluster.saga.Delivered
import dev.surefold.cluster.saga.SagaSignal
import dev.surefold.cluster.saga.durableSaga
import dev.surefold.cluster.saga.entityStep
import kotlin.time.Duration.Companion.days

data class Approval(val by: String, val approved: Boolean)

object Approved : SagaSignal<Approval>("approval", ApprovalCodec)

fun payouts(accounts: Sharded<Keyed<Long, Overdrawn>>, approvers: Approvers) =
    durableSaga("payout", ClaimCodec, ReasonCodec, ReceiptCodec) { claim ->
        entityStep("hold", accounts, claim.account, refused = { Reason.Overdrawn }, undo = { claim.pence }) {
            -claim.pence
        }
        var answer = receive(Approved, within = 3.days)
        if (answer == null) {
            step("remind", Done, { key -> approvers.remind(claim, key) })
            answer = receive(Approved, within = 4.days) ?: fail(Reason.NotApproved) // undoes the hold
        }
        if (!answer.approved) fail(Reason.Rejected(answer.by))
        Receipt(claim, answer.by)
    }

val running = payouts(accounts, approvers).on(cluster)
running.signal("claim-81", Approved, Approval("ops", true), dedupe = "ticket-812") // Either<AskFailure, Delivered>
```

- **`receive(signal, within): T?`** in `DurableScope` takes the next signal of that name the body has not yet taken,
  or answers `null` once `within` has passed. Its deadline is journalled the first time the body reaches it, and
  what it took (a signal, or the timeout) is journalled as an `Ended("receive:approval#n", …)`, so a replay takes the
  same answer whatever has arrived since. The body decides what a timeout means.
- **A body with nothing to take parks.** The run stops without blocking a thread, the entity may passivate, and the
  saga wakes on the next signal for it or at the deadline, whichever comes first, and replays to where it stopped.
- **Signals go to an inbox of their own**, the persistence id `"$kind-signals"/id`, written only by the saga's entity,
  so a signal never makes the runner `Overtaken`. A second signal with the same `dedupe` is answered as the first.
- **`signal(id, signal, value, dedupe)`** answers `Delivered`: `Accepted` (in the inbox of a saga that has not ended),
  `Ended(outcome)` (too late: how it ended, `Done` or `Refused`) or `Unknown` (nothing journalled for the id).
  On one node, `Durable.signal(journal, id, …)` does the same, and `run`/`resume` throw `Waiting(saga, until)` when
  the body parks.
- **Deadlines live in the `surefold-sagas` waker** beside its list of unfinished sagas: a parked saga is listed with
  its deadline, and the waker tells it `Wake` when it falls due, from whichever node holds the singleton.

## Why this shape

A wait inside one body keeps one undo chain across it, which is the thing the two-saga split loses. **Alternative:**
signals appended to the saga's own journal. One journal is simpler to read, but its sequence belongs to the runner,
so every signal during a run would overtake it. Recommended: a separate inbox, with what the body took copied into the
saga's journal. **Alternative home for deadlines:** a timer on each saga's entity, lost when it passivates or its
node dies, or the jobs tables, which core cannot depend on. Recommended: the waker, which already outlives any node
and already lists every unfinished saga.

## Stack

- [x] **`spec-0316-inbox`** ([#1134](https://github.com/surefold/surefold-core/pull/1134)) — `SagaSignal`, the inbox, `Delivered`, and `Durable.signal` on one node.
      Done when: a signal to a running saga is `Accepted` once and a repeat with its `dedupe` is answered the same
      with one inbox record, one to an ended saga answers `Ended` with its outcome, and one to a fresh id `Unknown`.
- [x] **`spec-0316-receive`** ([#1135](https://github.com/surefold/surefold-core/pull/1135)) — `receive` in the body, its deadline and choice journalled, `Waiting` on one node.
      Done when: on a test clock, a saga resumed after a signal takes it, one resumed past the deadline gets `null`,
      a signal and an expired deadline both present are decided by the journal and replay the same after a restart,
      and a 0128 unkind run crashing at every boundary ends every saga as it would have without a crash.
- [x] **`spec-0316-park`** ([#1136](https://github.com/surefold/surefold-core/pull/1136)) — a parked saga's entity stops its `Busy` word and may passivate; the waker keeps
      deadlines and wakes sagas at them.
      Done when: a three-node test kills the node of a parked saga and the deadline still wakes it elsewhere, and a
      passivated parked saga holds no runner.
- [x] **`spec-0316-cluster`** ([#1137](https://github.com/surefold/surefold-core/pull/1137)) — `Sagas.signal` from any node, waking a parked saga.
      Done when: a signal sent from another node resumes a parked saga, and one sent after it ended answers `Ended`.
- [x] **`spec-0316-docs`** ([#1138](https://github.com/surefold/surefold-core/pull/1138)) — the approval above as a `docs/cluster.md` section with a compiled fence.
      Done when: `GuideExampleTest` compiles it.

## Acceptance

```bash
./gradlew :surefold-cluster-saga:test
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **A signal that arrives before the body reaches `receive`?** Answered (maintainer, 2026-11-24): kept in the inbox,
   and `receive` takes it at once.
2. **Several signals of one name, such as two approvers?** Answered (maintainer, 2026-11-24): each `receive` takes the
   next in the order the inbox holds them; a repeat `dedupe` is never a second signal.
3. **Signals the body never takes?** Answered (maintainer, 2026-11-24): ignored once the saga ends, and pruned with its journal (0076).
4. **`Accepted` for a saga that then ends without taking the signal**, such as an approval landing while the timeout's
   undo runs? Answered (maintainer, 2026-11-24): `Accepted` means "in the inbox of a running saga", no more; a caller that needs the result
   follows with `await`.
5. **A signal to a `Stuck` saga?** Answered (maintainer, 2026-11-24): `Accepted`, since `resume` may still take it.
6. **The name.** `Signal` is already `dev.surefold.actor.Signal`. Answered (maintainer, 2026-11-24): `SagaSignal`.
7. **Every deadline in one singleton's state.** Answered (maintainer, 2026-11-24): accept it, since the waker already lists every
   unfinished saga; give its journal a snapshot (0074) if recovering it gets slow.
