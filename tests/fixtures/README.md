# Independent accounting fixtures

**These are not captured from the old system.** Every expected value here is derived from
first principles and carries its arithmetic in a `derivation` field, because the legacy
implementation has measured precision and accounting defects (see
`../../initialtransitioning.md`): treating its output as authoritative would
port the defects along with the behaviour.

They are **policy-explicit**, not policy-free. Each file states the assumptions it makes
under `assumptions`. Where a decision D1–D5 is still open, the fixture fixes the narrowest
defensible choice and says so — so the fixtures *constrain* those decisions rather than
presuming them settled. When a decision lands differently, the affected fixture changes
deliberately, in one place, with its derivation.

## The reconciliation identity

Every checkpoint in every case satisfies:

```
equity(t)   = cash(t) + signedPos(t) * mark(t)
netPnl(t)   = equity(t) - openingEquity - cumulativeExternalCashFlow(t)

netPnl(t)  ==  grossRealizedPnl(t) + unrealizedPnl(t) - cumulativeFees(t)
    where   unrealizedPnl(t) = signedPos(t) * (mark(t) - vwap(t))
```

This is the equation D3 asks for, stated once. Note what it is *not*: a claim that
`pnl1 == pnl2`. Gross trading PnL, fees and external cash flows are separate terms over a
shared opening baseline; case `fees-only-no-price-move` is the counter-example that makes a
direct equality false.

`../test_fixtures_consistency.py` checks the identity against every case in exact
decimal arithmetic, so the fixtures are validated **before** any implementation exists and
independently of one.
