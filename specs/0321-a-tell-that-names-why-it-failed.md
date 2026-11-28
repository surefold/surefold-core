# 0321 — A tell that names why it failed

## Problem

When a message does not land — mailbox full, entity discarded by `startIf`
(0318), entity interrupted mid-ask by stop/passivate — the sender often hears
nothing useful. `tell` returns `Unit` and the miss is a dead letter on the
receiving side. `ask` can already answer `TimedOut`, `Stopped`, or
`Unreachable`, but not "never accepted" or "mailbox full". Callers and tests
poll dead-letter counters or wait out timeouts to learn what a typed failure
could have said at once.

## Not doing

- **No change to `tell`'s return type.** Fire-and-forget stays fire-and-forget;
  a new method sits beside it.
- **No softening of reliable delivery.** Producers still confirm; this is for
  plain tell/ask.
- **No unbounded mailboxes.** Full still means full.
- **No new remote frame types** beyond what ask already needs to end early.

## Shape

1. Widen `AskFailure` so an ask that never entered a turn can end for a named
   reason, without waiting for `within`:

```kotlin
sealed interface AskFailure {
    data object TimedOut : AskFailure
    data object Stopped : AskFailure
    data object Unreachable : AskFailure
    data object MailboxFull : AskFailure
    data object Discarded : AskFailure   // with 0318; else deferred until then
}
```

2. Add an attempt that returns whether the message was accepted into a mailbox
   (local or the first hop a sharded region takes):

```kotlin
/** true if the mailbox took it; false if full (and a Full dead letter was recorded). */
fun <M : Any> ActorRef<M>.tryTell(message: M): Boolean
```

`tryTell` is the public name for what `@PlumbingSeam tellIfRoom` already does,
without the seam. Sharded refs use it through the region the same way `tell`
does: false means this node's region (or the kept buffer) could not take it,
not that the remote owner refused.

3. An ask whose `tell`/`tryTell` into the target would be full ends with
   `AskFailure.MailboxFull` at once. An ask to an entity that stop/passivate
   cuts off before the reply ends with `Stopped` as today; if the turn was
   cut mid-message after partial work, still `Stopped` (no "maybe applied"
   case beyond what reliable delivery already covers).

## Why this shape

Keeping `tell` as `Unit` avoids a breaking change across the ecosystem.
`tryTell` matches the existing plumbing seam and gives callers a boolean they
already invent with `tellIfRoom`. Widening `AskFailure` is the place asks
already branch. The alternative is making `tell` return `Either`; recommended
against — every call site pays for a case most ignore.

## Stack

- [ ] **`cursor/try-tell-2d0b`** — public `tryTell`, documented as the non-seam
      form of `tellIfRoom`; existing seam callers may stay.
      Done when: `tryTell` on a full mailbox returns false and records a Full
      dead letter, and `tell` still throws/fails as today.
- [ ] **`cursor/ask-mailbox-full-2d0b`** — `AskFailure.MailboxFull` (and
      `Discarded` if 0318 has landed), ask ends at once; guide row.
      Done when: an ask into a capacity-0 (or full) mailbox returns
      `MailboxFull` before `within`. Without the change it times out.

## Acceptance

```bash
./gradlew :surefold-actor:check :surefold-cluster:check
```

## Open questions

1. **Does `tryTell` on a `ShardedRef` mean "region accepted" or "owner accepted"?**
   Recommended: region accepted (including kept-for-handoff). Owner refusal is
   a hop/retry today; surfacing it needs a wire change — defer.
2. **Should `tellIfRoom` be deprecated in favour of `tryTell`?** Recommended:
   keep the seam as a typealias or trampoline for one release; no rush.
3. **Partial processing then stop: a distinct `Interrupted` failure?**
   Recommended: no in this spec. `Stopped` already covers it; a distinct case
   invites callers to retry unsafely. Reliable delivery remains the path for
   "must apply once".
