# 0322 — Which entities run here

## Problem

Spec 0081 made counts visible: how many entities of a kind run on this node,
how many shards it hosts. An operator or admin page still cannot answer
"which ids?" or "which member owns `customer-42`?". surefold-bank's admin page
and similar runbooks invent their own registries beside the managers, or scrape
logs. The gauges stay; they do not name anyone.

## Not doing

- **No change to the 0081 metrics.** Gauges stay on the hot path; this is a
  snapshot API read off the manager's turn, not a new gauge per id.
- **No cluster-wide directory service.** Each node answers for what it runs;
  ownership of an idle id is computed from the view, not looked up remotely.
- **No listing of every id that has ever existed.** Only currently running
  entities, plus pure shard/owner queries.

## Shape

```kotlin
class Sharded<M : Any> {
    /** Ids of this kind whose entity is started on this node right now. */
    fun running(): Set<String>

    /** The shard [id] hashes to, with this kind's shard count. */
    fun shardOf(id: String): Int

    /**
     * The Up member that owns [id] under the current view and any load move,
     * or null while no member holds that shard (joining, empty role, …).
     */
    fun ownerOf(id: String): Node?
}

// example: admin page
val where = baskets.ownerOf("customer-42")
val here = baskets.running()
```

- `running()` is consistent with the `surefold.sharding.entities` gauge on this
  node: the same starts and ends update both.
- `shardOf` / `ownerOf` are pure against the kind's placement and `cluster.view`;
  they do not start the entity.
- Local `entities` (no cluster) exposes `running()` the same way on a handle
  returned beside the behaviour, or the manager ref gains a query message —
  see open questions.

## Why this shape

Methods on `Sharded` are where services already hold a kind. The alternative
is only metrics with exemplar ids, which backends rarely support uniformly, or
forcing every behaviour to register itself in a side table. Recommended:
`Sharded` queries for the cluster case; keep local entities minimal if the
handle is awkward (open question).

## Stack

- [ ] **`cursor/sharded-running-ids-2d0b`** — `running`, `shardOf`, `ownerOf` on
      `Sharded`; wire from each shard manager's book; tests with two nodes and
      a moved shard.
      Done when: after telling ten entities on a two-node cluster, the union of
      `running()` equals those ten, each `ownerOf` names the member that hosts
      it, and `shardOf` matches placement. Gauge count equals `running().size`
      on each node.

## Acceptance

```bash
./gradlew :surefold-cluster:check
```

## Open questions

1. **Is `running()` a snapshot copy or a live view?** Recommended: an immutable
   snapshot taken on the managers (merged on the caller), so the admin page
   cannot mutate plumbing.
2. **Should local `entities` expose the same?** Recommended: yes, via a small
   `EntitiesRef.running()` if we can do it without a second spawn API; else
   defer local to a follow-up and ship `Sharded` first.
3. **Expose assignments as `Map<Int, Node>` for every shard?** Recommended:
   defer. `ownerOf` covers the runbook question; a full map is easy later and
   larger to test with rebalance.
