# Spec: accounting decisions D1–D5

**Status: proposed, awaiting confirmation.** Each decision below has a recommended answer, chosen to
be the narrowest defensible one for a first release. Nothing here is implemented beyond what the
kernel already does; where the kernel's current behaviour is a *placeholder* for a decision, that is
said explicitly.

These five were extracted in `../initialtransitioning.md` because each reaches into both the
generated schemas and the arithmetic kernel, and retrofitting any of them means a wire version bump.
A universal accounting model is not the goal — a *written* one is.

---

## D1 — Instrument and quantity model

**Recommendation: an `InstrumentSpec` looked up beside the events, never carried on them.**

```java
record InstrumentSpec(String instrumentId,
                      BigDecimal multiplier,     // contract size; 1 for cash equities
                      String currency,           // ISO 4217 code
                      int quantityScale) {}      // 0 for the first release: whole units only
```

Notional is always `quantity × price × multiplier`, with price quoted **per unit** as the market
quotes it.

**Why not on the event.** A multiplier is a property of the instrument, not of a fill. Putting it on
every event lets two events disagree about the same contract, and there is no principled way to
resolve that disagreement at read time. It also bloats every row for a value that changes roughly
never.

**Why not folded into the price**, which is what the old system appears to have done (nothing in the
reviewed classes applies a multiplier at all): it destroys the ability to display a real price,
makes a position's mark incomparable with the market data it came from, and silently changes meaning
the first time someone marks from a quote feed instead of a fill.

**Quantities are whole numbers for the first release.** `quantityScale = 0`. The kernel already
enforces this and says why: `Position.signedPosition` is `int64`, so a fractional position cannot be
published faithfully, and the kernel must not be able to reach a state its own wire format cannot
express. Relaxing it means relaxing both together, as a version bump.

**Consequences accepted.** No crypto, no fractional FX, no fractional-share brokers in v1. A reverse
split producing an odd-lot remainder has no representation yet — the old code silently truncated it
with a `(long)` cast under its own `// TODO`; refusing is better than truncating, and the corporate
action spec will have to decide between cash-in-lieu and a fractional carve-out.

**What pins it.** A fixture set for a multiplied contract (CL at 1,000 bbl) parallel to the existing
`multiplier = 1` set, asserting that notionals, realized PnL and unrealized PnL all scale by exactly
the multiplier and that nothing else changes.

---

## D2 — Numerical policy

This is the decision the kernel is currently *refusing* rather than answering, and that refusal is a
placeholder.

**Recommendation, in four parts.**

| | Policy |
|---|---|
| **Additive arithmetic** | Exact `BigDecimal`, no rounding, ever. Cash, notionals, fees, cash flows and realized PnL accumulate exactly. |
| **Division** | Scale **12**, `HALF_EVEN`. Exactly two things divide: the *reported* VWAP, and `costRemoved` on a **partial** reduce. Neither feeds an accounting total except through the cancelling pair above. |
| **Persisted scale** | **12**, matching `StorageMapping`'s `DECIMAL(38,12)` default, so a round trip neither widens nor rejects. |
| **Presentation / settlement** | Per-currency minor units (2 for USD), applied **only** at reporting or settlement, never fed back into state. |

**Why 12.** It is already the persisted scale, so choosing anything else guarantees a mismatch
between what is calculated and what survives a round trip. Twelve digits is far finer than any
instrument's tick and coarse enough that `DECIMAL(38,12)` leaves 26 integer digits — more than any
realistic notional.

**The important structural point: a rounded VWAP must not contaminate PnL.** Realized PnL is
currently computed as `closedQty × (price − vwapBefore) × multiplier`, which reads a *divided* value
back into an accumulating total. With an inexact VWAP that rounding enters PnL permanently and
compounds across reduces. The fix is to keep the signed notional as the single source of truth —
which the kernel already does — and compute the cost removed on a reduce from the notional directly:

```
exposure        = |positionBefore|
costRemoved     = notionalBefore × closedQty / exposure      (one division, at scale 12)
signedClosedQty = closedQty × sign(closing trade)            (−1 for a sell, +1 for a buy)

realized        = ( −signedClosedQty × price − costRemoved ) × multiplier
```

**The multiplier scales the whole bracket.** The signed notional is stored *unmultiplied*, so
multiplying only the proceeds adds two different units together. With CL's multiplier of 1000 that
error turns a realized **60,000 into 1,558,500** — it is not a rounding difference, it is nonsense.
Two earlier drafts of this spec were wrong here: the first had the sign inverted (−120 instead of
+120 on a short close), the second multiplied only the proceeds. The form above is verified against
the VWAP form on long reduce, long close, short reduce, short close, reversal and a negative price,
at multiplier 1 and 1000.

The double negation is not decoration: `notionalBefore` and `costRemoved` are *signed* (negative for
a short), and the closing trade's cash flow has the opposite sign to the position it closes.

For a close or a reversal `closedQty == exposure`, so `costRemoved == notionalBefore` **exactly** and
no division happens at all — which is most of the cases that matter. Only a partial reduce divides.

**The remaining notional is updated by subtracting the allocated cost**, not by re-deriving it from
a VWAP:

```
notionalAfter = notionalBefore − costRemoved
```

This is what makes the identity exact even when `costRemoved` rounds: the same rounded quantity
appears in `realized` (negated) and in `notionalAfter`, and the two cancel in the identity. Deriving
`notionalAfter = positionAfter × vwapBefore` would reintroduce the divided VWAP and break that
cancellation. Verified on `recurring-vwap-partial-reduce`, where
`1.666666666667 + (−2.666666666667) = −1.000000000000` exactly.

**Implemented.** The kernel previously divided exactly and threw on a non-terminating quotient,
which made the missing policy visible but was not shippable: an ordinary book (1 @ 100, 2 @ 100.50)
could not report its own VWAP. It now exposes `CALCULATION_SCALE = 12` /
`CALCULATION_ROUNDING = HALF_EVEN`, divides the reported VWAP at that scale, publishes
`signedNotional()` as the accounting source of truth, and computes both realized and unrealized PnL
from the notional. The two tests that asserted the old exact-or-throw behaviour were retargeted
rather than deleted.

**What pins it — now in place.** `recurring-vwap-partial-reduce` and `multiplied-contract-cl` are
fixtures, consumed by both test suites: 13 cases / 25 checkpoints, with `signedNotional` asserted
wherever a case exercises rounding, and the reconciliation residual asserted exactly zero at every
checkpoint. Unit tests cover the reported VWAP's rounded value, the multiplier scaling realized and
unrealized together, and the int64 range.

**Still owed**: a persistence round trip asserting values are unaltered at scale 12 (needs the SQL
path), and a long reduce sequence asserting the residual does not drift over many roundings.

---

## D3 — Reconciliation equations

**Recommendation: the four-term identity, already implemented and already enforced.** This decision
is effectively settled by the fixtures; it is recorded here so it is not re-litigated.

```
equity(t)        = cash(t) + signedPosition(t) × mark(t) × multiplier
netPnl(t)        = equity(t) − openingEquity − cumulativeExternalCashFlow(t)

netPnl(t)        ==  grossRealizedPnl(t)
                   + ( unrealizedPnl(t) − openingUnrealizedPnl )
                   − cumulativeFees(t)

where unrealizedPnl(t) = ( signedPosition(t) × mark(t) − signedNotional(t) ) × multiplier
```

**Unrealized PnL is computed from the signed notional, never from the VWAP.** The two forms are
algebraically identical only when the VWAP divides exactly — precisely the case that cannot be
assumed. An earlier draft of this spec wrote the VWAP form, which reads a rounded value back into an
accounting figure; on `recurring-vwap-partial-reduce` that is not exact and the identity drifts.

Worth noting that **the original did this correctly** and the earlier draft was a regression from
it: `PosPnLTracker:207-208` computes `_unRealizedPnl = mark × sgndPos` then `.minus(posVwap)`, and
its `posVwap` field holds the signed cost notional (`getUnitVwap()` divides it by the position on
read). The old field name is misleading; the arithmetic behind it was right.

**The terms are separate on purpose.**

- `grossRealizedPnl` is trading PnL **before** fees.
- `cumulativeFees` reduces cash and nets off separately.
- `cumulativeExternalCashFlow` (deposits, withdrawals, dividends received) changes equity without
  producing trading PnL, and must be subtracted or a 10,000 deposit reports as 10,000 of profit.
- `openingUnrealizedPnl` is netted out so a period beginning mid-life does not report a pre-existing
  mark-to-market gain as this period's profit.

**`pnl1 == pnl2` is not a valid invariant** and the old code was right not to assert it, though wrong
not to define the alternative. The counter-example is in the fixtures as
`fees-only-no-price-move`: a round trip at an unchanged price with a fee of 7.50 each way leaves
gross trading PnL at exactly **0** while equity falls by **15**. Only the four-term form holds.

**Residual tolerance: exactly zero** at calculation scale. A non-zero residual is a bug in the
kernel, not a data problem, and callers should treat it as fatal.

**What pins it.** `reconciliationDiscrepancy()` is asserted zero at **every** checkpoint of every
fixture — 20 of them — not only at the end. Plus the Python identity check, which validates the
fixtures themselves independently of any implementation.

---

## D4 — Checkpoint contents, replay boundary and duplicate handling

**Recommendation.**

**Accounting identity: the triple `(strategyId, portfolioId, instrumentId)`.** One tracker per
triple. The old system keyed its datums by instrument alone and carried strategy and portfolio as
payload — defensible only because a `MonoStrategyPositionManager` is one strategy by construction and
the topic name encoded it (`POS-STATE-PUB_<strategyId>`). Making the triple explicit removes that
implicit dependence on topic naming.

**Execution identity: the venue's execution id**, required for deduplication. A fill without one
cannot be deduplicated and must be rejected rather than accepted hopefully. **Fee identity** is
separate: a commission billed apart from its fill needs its own id, or a replayed fee double-charges
while the trade correctly deduplicates.

**Replay boundary: `(eventTime, sequence)`, never a timestamp alone.** Several fills can share a
millisecond, so a timestamp cannot say which of them was included. The checkpoint records the last
applied `(eventTime, sequence)` per subscribed topic; replay resumes strictly after it.

**Checkpoint contents** — `PositionSnapshot` already carries the accounting half:

| Carried | Why |
|---|---|
| `cash`, `signedPosition`, `signedNotional` | position state; notional rather than VWAP so restoration cannot lose a division |
| `grossRealizedPnl`, `cumulativeFees`, `cumulativeExternalCashFlow` | cumulative totals |
| `openingEquity`, `openingUnrealizedPnl` | the reporting baseline |
| `multiplier` | D1 |
| `appliedExecutionIds` | without these, the first replayed fill after recovery is counted twice |

**Still to add**: the per-topic `(eventTime, sequence)` boundary, and the triple identity.

### D4 is not yet a complete protocol — six things still unspecified

The above fixes the identity and the snapshot's accounting half. These remain open, and each can
silently break recovery:

**1. Stable sequence identity and ordering across topics.** `(eventTime, sequence)` presumes a
`sequence` that is stable across a restart and comparable *between* subscribed topics. Neither is
established. A per-topic counter restarts at zero; a per-engine counter is not reproducible across a
replay unless it is recorded. The boundary must be defined as a per-topic `(eventTime, sequence)`
**vector**, with a stated rule for the order in which same-timestamp events from *different* topics
are applied — otherwise two replays of the same input can produce different states.

**2. Atomic capture of accounting state and boundary together.** The snapshot and the boundary are
read at different instants unless something makes them one write. A snapshot taken after event *n*
but recording boundary *n−1* replays *n* twice; the reverse loses it. Capture must be a single
transition in the dispatch loop, and the checkpoint write must be atomic with respect to it.

**3. Applied fee ids, not only execution ids.** The snapshot carries `appliedExecutionIds` only. A
commission billed separately from its fill (`applyFee`) has no id and no dedup, so a replayed fee
double-charges while the trade beside it correctly deduplicates. `applyFee` needs a fee id and the
snapshot a second set.

**4. Collections cannot be persisted by the scalar SQL mapper.** `StorageMapping` refuses arrays
outright, so neither id set can be a column. Options: a child table keyed by the accounting triple;
a single canonical delimited string (bounded, and a length to declare); or a separate blob store.
This must be decided before a `PositionCheckpoint` schema can be written at all — it is not a detail.

**5. Instrument metadata and its version.** Reproducing a historical interpretation needs the
`InstrumentSpec` that was in force, not today's. A multiplier corrected after the fact silently
re-prices history. The checkpoint must carry the instrument spec version it was computed under.

**6. Pruning needs a stronger guarantee than the boundary gives.** A boundary stops *replay* from
re-delivering an old execution; it does nothing about a **live adapter** re-delivering one — a broker
resending a fill from last week, after the id was pruned. So retention cannot be derived from the
replay horizon alone. It needs either a broker-side guarantee of maximum redelivery age, or
durable dedup outside the pruning window (an execution-id ledger with its own retention), or an
explicit accepted risk. Naming a horizon without one of those is a guess dressed as a policy.

**The unbounded-set problem remains**, and these interact: `appliedExecutionIds` grows forever, and
the obvious fix (prune by horizon) is exactly what item 6 says is unsafe on its own.

**What pins it.** A recovery-equivalence test: process a sequence, checkpoint mid-way, restore, replay
the remainder, and assert the final state is **identical** to uninterrupted processing — including
cumulative totals and the reconciliation residual. Plus a redelivery test across the restore
boundary, and a same-millisecond multi-fill test.

---

## D5 — Strategy contract

**Recommendation: strategies react to published position-update events. Synchronous reads are a
same-process convenience that carries no ordering guarantee.**

**Why.** Actor priority (`MultiClientEngine.registerActor(…, highPriority)`) orders *invocation* — it
guarantees the position manager is updated before another fill subscriber runs. It does **not**
guarantee that a *derived* position event has been delivered to that subscriber before it runs. Those
are different properties, and a strategy written against the first while believing the second is
reading state that happens to be fresh rather than state it was told about.

An event-driven contract also survives the two things a synchronous read cannot: a cross-language
actor (the Python side holds no reference to the Java manager) and any future out-of-process split.

**Cost accepted.** A strategy reacting to a published update sees the position one dispatch later
than a synchronous reader would. For a strategy that must act within the same event as its own fill,
that is a real difference and the synchronous accessor is the escape hatch — documented as
participating in no ordering guarantee, and never the mechanism a correctness argument rests on.

**What pins it.** A test with two actors subscribed to the same fill topic, the manager registered
high-priority, asserting (a) the manager's state is updated before the second actor runs, and (b) the
derived position event reaches it only on a later dispatch — so the distinction is demonstrated
rather than assumed. Plus the same test with several events at one timestamp.

---

## Summary

| # | Recommendation | Kernel change needed |
|---|---|---|
| D1 | `InstrumentSpec` beside the events; whole-unit quantities in v1 | none (multiplier is already a constructor argument) |
| D2 | exact addition; division at scale 12 `HALF_EVEN`; persisted 12; presentation per-currency | **yes** — replace exact-or-throw, move realized PnL onto the notional |
| D3 | the four-term identity | none (implemented and enforced) |
| D4 | triple identity; execution + fee ids; `(eventTime, sequence)` boundary | **yes** — and six items remain unspecified; not yet a protocol |
| D5 | event-driven; synchronous read is an unguaranteed convenience | none yet (actor wrapper does not exist) |

D2 and D4 are the two that block `PositionState`, because they decide its scales and whether it is a
recovery record at all. See `spec-position-state.md`.
