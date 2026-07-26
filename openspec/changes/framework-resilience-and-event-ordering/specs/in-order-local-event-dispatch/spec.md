# DEFERRED — not part of this change

**Status: deferred. No requirements. Nothing here is to be implemented or archived.**

This capability was proposed as an opt-in `sequentialPerEntity` mode on `EventHandlingJob`, so that
notifications sharing a `model_id` would be handled sequentially while different entities continued
in parallel. It was reviewed against the implementation and parked. The requirement/scenario
structure has been removed deliberately so this file cannot be mistaken for deliverable scope.

## The problem is real

`EventHandlingJob.drainBatch` gives every due notification its own virtual thread:

```java
toInvoke.stream().map(n -> executor.submit(() -> classify(n)))
```

So two events for the same entity genuinely can be handled out of order today. Two things would
also have made a fix feasible: dispatch is a `DistributedJob`, so exactly one instance runs
cluster-wide, and `model_id` is always populated because fan-out skips sentinel rows.

## Why it was parked anyway

**The framework already answers this twice.** `docs/events/local-event-handler.md` states the
contract — "What you must not rely on: ... Ordering between events" — and prescribes the pattern for
order-sensitive work: `UPDATE ... WHERE state = '<expected source state>'`. That is ordering by
guard: an out-of-order event fails its guard, no-ops, and retries until its predecessor lands.
Separately, `docs/events/event-streaming.md` already promises real ordering on the CDC path via
`model_id` as the Kafka partition key. A third, weaker in-house mechanism would mean two different
ordering guarantees with different strengths.

**Ordering, retries, and bounded latency are a trilemma.** Pick any two. Whatever gets built is a
compromise that would ship under a name (`sequentialPerEntity`) users will read as "strict".

**No concrete driver.** `proposal.md` says applications "sometimes require" strict in-order
delivery. Groups 1 and 2 came from identifiable defects; this came from a hypothetical.

## Unresolved design questions, if it is ever revived

These must be answered *before* implementation — they determine the whole design.

1. **Head-of-line blocking: yes or no?** Event 1 for entity X fails and backs off; event 2 is due
   now. Either hold event 2 until event 1 resolves — true ordering, but a poison event stalls that
   entity for up to the 7-day `retentionWindow` before it reaches `EXPIRED` — or dispatch event 2
   and lose the guarantee. The original spec promised "strictly in order" and chose neither.

2. **Which ordering key?** The original spec said "`event_date` / `next_retry_at`" as if
   interchangeable. They agree only at fan-out (`EventFanoutJob` sets `nextRetryAt(event.eventDate)`)
   and diverge on the first failure (`next_retry_at = postInvokeNow + backoff`). `findDue` orders by
   `next_retry_at` alone, so after one retry an older event can sort *after* a newer one for the
   same entity.

3. **What is the grouping key?** Notifications are one row per `(event × handler)`. Grouping by
   `model_id` alone would serialize *independent handlers* for the same entity against each other —
   an email handler blocking an indexer — which is stronger than the requirement needs and costs
   throughput for no ordering benefit. `(model_id, handler_name)` is almost certainly correct.

4. **Which API shape?** The original spec offered `sequentialPerEntity = true` *or*
   `DispatchMode.SEQUENTIAL_PER_ENTITY` in the same sentence. If revived, pick one. Note that
   `LocalEventHandlerConfig` wraps every tuning knob in `Optional<T>` but uses a plain `boolean` for
   `HandlingConfig.enabled`; a toggle should follow the latter.

## What would justify reviving it

A concrete case where the guard pattern does not work — handlers with external side effects that
cannot be made order-insensitive (sending "order shipped" before "order confirmed"), in a
deployment where the Kafka path is too heavy. That use case should drive the design, including the
answer to question 1 above.
