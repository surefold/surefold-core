# 0319 — Reads that share an entity

## Problem

An entity handles one message at a time. That is right for writes: two
withdrawals must not both see the same balance. It is wrong for a hot entity
whose traffic is mostly reads: a `Balance` ask waits behind every other ask,
even though it only looks at state. Services today split read models out to a
projection, or accept the latency, because the entity mailbox has no other
lane.

## Not doing

- **No change to ordinary actors.** Only `entities` / sharded entities gain a
  concurrent lane.
- **No shared-memory free-for-all.** A message that may call `become` stays
  exclusive.
- **No stream or duplex replies.** Those are a later spec if needed.
- **No raising the default mailbox size.**

## Shape

A marker on messages that only read. Concurrent messages for one id may run
together on virtual threads; a message that is not concurrent waits until they
finish, then runs alone.

```kotlin
/** A message that must not call become, and may run beside other Concurrent ones. */
interface Concurrent

data class Balance(val reply: Reply<Long>) : Account, Concurrent

data class Withdraw(val pence: Long, val reply: Reply<Boolean>) : Account

fun account(id: String) = behaviour<Account, Long>(0L) { _, balance, message ->
    when (message) {
        is Balance -> stay().also { message.reply(balance) }
        is Withdraw ->
            if (balance >= message.pence) become(balance - message.pence).also { message.reply(true) }
            else stay().also { message.reply(false) }
    }
}
```

- The entity manager (or the entity runner) schedules `Concurrent` turns in
  parallel, each seeing the state as of the last exclusive `become`.
- A non-`Concurrent` message is not started until every in-flight Concurrent
  turn for that id has finished; Concurrent messages wait behind an exclusive
  turn the same way.
- A Concurrent turn that returns `become(…)` fails the entity: the marker is a
  claim, not a hint.
- Default with no Concurrent messages is exactly today's one-at-a-time behaviour.

## Why this shape

A marker keeps the protocol self-describing and needs no new parameter on
`entities`. The alternative is `concurrency: Int` on the manager and letting
any messages overlap; that silently races writes. Recommended: the marker, and
a hard fail on `become` from a Concurrent turn so the claim is checked.

## Stack

- [ ] **`cursor/concurrent-entity-reads-2d0b`** — `Concurrent`, scheduling rules,
      fail-on-become, and tests that two Balances overlap while a Withdraw
      still excludes them.
      Done when: two Concurrent asks on one busy entity both complete while a
      third exclusive message is held, and a Concurrent turn that becomes fails
      the entity. Without the change the two asks are strictly serial.

## Acceptance

```bash
./gradlew :surefold-actor:check :surefold-cluster:check
```

## Open questions

1. **May a Concurrent turn stash, spawn, or watch?** Recommended: yes for
   watch/spawn (read-path side effects), no for stash (stash is exclusive
   mailbox control). Spell it in the implementation's tests.
2. **Does passivation wait for Concurrent turns?** Recommended: yes. An entity
   with Concurrent work in flight is not idle, same as today's kept messages.
3. **Should persistent entities allow Concurrent reads during replay?**
   Recommended: no Concurrent turns until replay has finished; first exclusive
   or Concurrent message after recovery starts the lanes.
