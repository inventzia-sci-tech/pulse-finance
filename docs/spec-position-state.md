# Spec: `PositionState`

**Status: proposed, awaiting field-by-field confirmation.** No YAML is written. Two of its decisions
depend on D2 and D4 (`spec-accounting-decisions.md`), and one — whether this type is a recovery
record at all — changes its field set substantially.

**Source:** `com.algotrading.cep.finance.data.position.PositionState` (361 lines, `extends
A_MktDatum`). The authoritative field order is its own `csvInternalFieldNames()`.

---

## The original, field by field

Eighteen CSV columns. `date` and `timeUtc` are *derived* from `datumTime` at serialisation, not
stored fields, so fourteen are real, plus the two inherited routing fields.

| # | Original field | Java type | Notes from the source |
|---|---|---|---|
| — | `datumKey` | `String` | inherited; **set to the instrument id** (manager:303) |
| — | `datumTime` | `long` | inherited; epoch millis |
| 1 | `date` | — | derived at CSV time from `datumTime` |
| 2 | `timeUtc` | — | derived at CSV time from `datumTime` |
| 3 | `instrumentId` | `String` | **duplicates `datumKey`** — manager:303–304 sets both to the same `s` |
| 4 | `strategyId` | `String` | |
| 5 | `portfolioId` | `String` | |
| 6 | `currency` | `Currency` | |
| 7 | `moneySignificantDigit` | `int` | per-row decimal scale |
| 8 | `posStatePoint` | `PositionPoint` | why this state exists |
| 9 | `sgndPos` | `long` | **`(S)`** — recoverable state |
| 10 | `posVwap` | `Money` | **misnamed: holds the signed cost NOTIONAL, not a price.** `getUnitVwap()` divides it by `sgndPos` on read (`PosPnLTracker:459-472`), and it accumulates additively (`:331`). Its own comment says "av prc", which is wrong. |
| 11 | `posStateBidPrice` | `Money` | instantaneous — no `(S)` marker |
| 12 | `posStateAskPrice` | `Money` | instantaneous — no `(S)` marker |
| 13 | `posStateMarkPrice` | `Money` | **`(S)`** |
| 14 | `cash` | `Money` | **`(S)`** |
| 15 | `cumulCashPaidRcvd` | `Money` | "cash paid received not frm trades (for example divs paid/received)" |
| 16 | `cumulRealzdPnl` | `Money` | |
| 17 | `cumulPnl` | `Money` | **`(S)`** — `cumulRealzdPnl + _unRealizedPnl` (`PosPnLTracker:211`). **Fees are not subtracted**, so it is realized + unrealized *gross*, not net. |
| 18 | `cumulFee` | `Money` | |

Nine `Money` fields. Per S2 every one becomes an exact decimal; `Money` itself is not ported.

**The `(S)` markers are a real signal.** Only four fields carry them — `sgndPos`,
`posStateMarkPrice`, `cash`, `cumulPnl` — marking the author's own distinction between recoverable
state and derived or instantaneous reporting values. That distinction was identified as a strength of
the original, and it bears directly on fork F1 below.

---

## Fork F1 — is this a published view, or a recovery record?

**This is the decision that most changes the field set, and I think the original conflated two
things.**

The old system used `PositionState` for both: it published them on `POS-STATE-PUB_<strategyId>` *and*
read them back from EOD state files to initialise (`posManagerStateFromFiles`). But measured against
D4's checkpoint requirements, it cannot actually do the second job:

| D4 requires | In `PositionState`? |
|---|---|
| `cash`, `signedPosition` | yes |
| **signed notional** (not a divided VWAP) | **yes, actually** — `posVwap` *is* the signed notional (see field 10). This is the one checkpoint requirement the original already met. |
| `grossRealizedPnl`, `cumulativeFees` | yes (`cumulRealzdPnl`, `cumulFee`) |
| `cumulativeExternalCashFlow` | yes (`cumulCashPaidRcvd`) |
| **`openingEquity`, `openingUnrealizedPnl`** | **no** |
| **`appliedExecutionIds`** | **no** |
| **replay boundary `(eventTime, sequence)`** | **no** |
| `multiplier` | no (the old system had no multiplier at all) |

So restoring from a `PositionState` silently resets the reporting baseline to zero and re-admits every
already-applied fill — exactly the recovery defects D4 exists to prevent. The old system got away
with it because its baseline was implicitly "start of day" and its fills arrived once.

**Recommendation: `PositionState` is a published view. Recovery gets its own type.**

- `PositionState` — what the manager publishes for consumers: strategies, dashboards, the PnL
  reporting layer. Rich, human-meaningful, lossy-by-design (a divided VWAP is fine here).
- `PositionCheckpoint` — a separate schema mirroring `PositionSnapshot`, carrying signed notional,
  the baseline, the applied-id set and the replay boundary. Written by the recovery path, read by
  nothing else.

Splitting them means neither compromises for the other: the view can carry bid/ask and a rounded
VWAP without those entering recovery, and the checkpoint can carry an id set without appearing on a
dashboard. If instead you want one type, it must be the checkpoint's field set, and the view becomes
a projection of it.

---

## Fork F2 — the identity: one key or three fields?

D4 names the accounting identity as the triple `(strategyId, portfolioId, instrumentId)`. The datum
has one routing key.

| Option | Shape | Trade-off |
|---|---|---|
| **A — instrument key, triple in payload** (the original) | `x-datum-key: instrumentId`; `strategyId`/`portfolioId` as fields | Faithful. Works only if the topic distinguishes strategies, as the old `POS-STATE-PUB_<strategyId>` naming did. Two strategies on one topic collide on the key. |
| **B — composite key** | `x-datum-key: positionKey` = `"<strategyId>/<portfolioId>/<instrumentId>"`, triple also as fields | One topic serves many strategies; the key is self-describing and unique. Costs a parsed/constructed string and makes key-prefix queries the way to filter by strategy. |
| **C — composite key, no redundant fields** | key only | Leanest, but every consumer must parse the key to get a strategy id, and SQL filtering on strategy becomes a string operation. |

**Recommendation: B.** It keeps the original's fields (so nothing is lost and the triple is directly
queryable in SQL) while making the routing key genuinely unique, which A is not in general — A is
safe only under a convention the schema cannot enforce. C saves three columns at the cost of making
every consumer and every query do string surgery.

Note that under B `instrumentId` is no longer redundant with the key, which resolves the original's
`setDatumKey(s)` / `setInstrumentId(s)` duplication rather than carrying it forward.

---

## Fork F3 — does `moneySignificantDigit` survive?

**Recommendation: drop it.**

It is a per-row decimal scale. D2 makes scale a *policy* — exact addition, division at 12, persisted
at 12, presentation per-currency — held centrally. A per-row scale under a central policy is either
redundant (it agrees) or a contradiction (it does not), and there is no principled way to resolve the
contradiction at read time. Worse, two rows for the same position could disagree.

The legitimate need behind it — "how many digits should this be shown to?" — is a property of the
currency, and is better derived from the currency code than restated on every row.

**If you want it kept**, the honest framing is a presentation hint named as such
(`displayScale`), explicitly not participating in arithmetic, and validated as consistent across rows
for one currency. I would still rather not: a field that must agree everywhere is better computed than
stored.

---

## Fork F4 — bid and ask

The original carries both, and they are the two `Money` fields *without* an `(S)` marker — the
author's own note that they are instantaneous market observations, not recoverable state.

**Recommendation: keep them, as optional (nullable) fields.** Absence is meaningful — a position can
be marked with no live quote — and the original used `NaN` as the absent sentinel, with
commented-out NaN debugging left in the source as evidence of past bugs. A nullable decimal says
"unknown" without a magic value and without a floating-point type.

They belong on the view, not the checkpoint.

---

## Proposed schema — `PositionState` (the view)

Under F1-view, F2-B, F3-drop, F4-nullable. **Not yet written.**

| Field | Type | Req | From |
|---|---|---|---|
| `positionKey` | `string`, `x-datum-key` | ✔ | new: `"<strategyId>/<portfolioId>/<instrumentId>"` |
| `stateTime` | `int64`, `x-datum-time` | ✔ | `datumTime` |
| `strategyId` | `string` | ✔ | unchanged |
| `portfolioId` | `string` | ✔ | unchanged |
| `instrumentId` | `string` | ✔ | unchanged (no longer duplicating the key) |
| `currency` | `string` | ✔ | `Currency` → ISO 4217 code (`Currency` is not `StorageMapping`-representable) |
| `positionPoint` | `string` | ✔ | `posStatePoint`; string code pending enum support, as in `Position` |
| `signedPosition` | `int64` | ✔ | `sgndPos` |
| `positionVwap` | `decimal` | ✔ | **converted, not renamed**: `posVwap / sgndPos` at scale 12, or `0` when flat. The legacy field is a notional; this one is a per-unit price. |
| `signedNotional` | `decimal` | ✔ | `posVwap` **unchanged** — carried as well as the divided VWAP, so a consumer that needs exact accounting has it without re-multiplying a rounded price |
| `markPrice` | `decimal` | ✔ | `posStateMarkPrice` |
| `bidPrice` | `decimal` | ✖ | `posStateBidPrice`; null = no quote |
| `askPrice` | `decimal` | ✖ | `posStateAskPrice`; null = no quote |
| `cash` | `decimal` | ✔ | unchanged |
| `cumulativeExternalCashFlow` | `decimal` | ✔ | `cumulCashPaidRcvd`, renamed to match D3's vocabulary |
| `cumulativeRealizedPnl` | `decimal` | ✔ | `cumulRealzdPnl` |
| `cumulativeFees` | `decimal` | ✔ | `cumulFee` |
| `cumulativeGrossPnl` | `decimal` | ✔ | `cumulPnl` **unchanged in value**: realized + unrealized, fees *not* deducted. Named `Gross` because that is what the legacy arithmetic computes. |
| `cumulativeNetPnl` | `decimal` | ✔ | **new, derived**: `cumulativeGrossPnl − cumulativeFees`. The figure D3's identity actually governs, which the original never computed. |

Dropped: `moneySignificantDigit` (F3), `date`/`timeUtc` (derived from `stateTime`, as in the original),
`publishedTime`/`receivedTime` (per the `Position` decision — run recording owns provenance).

**Every field is exactly `StorageMapping`-representable** — strings, int64 and decimals only, no
arrays — so the type is SQL-persistable. To be re-verified mechanically before the YAML is committed,
not assumed.

**Two of these are conversions, not renames — a correction to an earlier draft of this spec.**

| Legacy | New | |
|---|---|---|
| `posVwap` | `signedNotional` | **same value**, honest name |
| `posVwap` | `positionVwap` | **divided** by `sgndPos` at scale 12 — a real conversion |
| `cumulPnl` | `cumulativeGrossPnl` | **same value**; an earlier draft called it `cumulativeNetPnl`, which was wrong: the legacy calculation never subtracts fees |
| — | `cumulativeNetPnl` | **new**, `gross − fees`; the quantity D3 governs |
| `cumulCashPaidRcvd` | `cumulativeExternalCashFlow` | rename only |
| `cumulRealzdPnl` | `cumulativeRealizedPnl` | rename only |

Say if you would rather keep the original spellings for the pure renames; the two conversions have to
happen regardless, because the legacy names misdescribe the legacy values.

---

## What blocks writing it

| Blocker | Why |
|---|---|
| **F1–F4 confirmed** | each changes the field set |
| **D2** | fixes the decimal scales and whether `positionVwap` can be published at all without a rounding policy |
| **D4** | decides whether `PositionCheckpoint` is a separate schema, and what it carries |
| enum support in pulse-data | not blocking — `positionPoint` ships as a string code, as `Position` already does |

Once F1–F4 and D2/D4 are settled, the YAML, the generated bindings, the conversion contract on the
schema, and a `PositionState` fixture set are a short piece of work — the kernel already computes
every value this type reports.
