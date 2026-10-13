# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
"""The accounting fixtures must be arithmetically sound before anything is implemented.

This checks the fixtures against themselves, in exact decimal arithmetic, with no
reference to pulse-finance code: every checkpoint's stated primitives (cash, signedPos,
vwap, mark) must reproduce its stated derived values (unrealizedPnl, netPnl) through the
reconciliation identity in `fixtures/README.md`.

What this buys: a fixture with a typo'd expectation fails here, now, rather than being
discovered later as a "bug" in a correct implementation. These fixtures are the spec for
D1-D5, so an unsound one is worse than none.
"""
from __future__ import annotations

import json
from decimal import Decimal
from pathlib import Path

try:
    import pytest
except ModuleNotFoundError:                      # standalone mode: see __main__ below
    pytest = None

# Repository-level, consumed by both test suites: the fixtures specify the finance contract
# across languages, so neither language's tree owns them. The Java suite maps this same
# directory onto its test classpath via <testResources> in core/java/pom.xml.
_FIXTURES = Path(__file__).resolve().parent / "fixtures" / "position_accounting.json"


def _d(v) -> Decimal:
    return Decimal(str(v))


def _load() -> dict:
    return json.loads(_FIXTURES.read_text(encoding="utf-8"))


def _cases():
    return [(c["name"], c) for c in _load()["cases"]]


def test_fixture_file_is_present_and_declares_its_assumptions():
    doc = _load()
    assert doc["fixtureVersion"] == 1
    # The assumptions are load-bearing: a fixture set that does not state its multiplier
    # and rounding policy cannot constrain D1/D2.
    for key in ("multiplier", "arithmetic", "openingBaseline", "feePolicy", "shortVwap",
                "signedNotional", "division"):
        assert doc["assumptions"][key].strip(), f"assumption '{key}' must be stated"
    assert "NOT captured from the legacy" in doc["source"]


_parametrize = (pytest.mark.parametrize("name,case", _cases(),
                                        ids=[n for n, _ in _cases()])
                if pytest else (lambda f: f))


@_parametrize
def test_reconciliation_identity_holds_at_every_checkpoint(name, case):
    opening = case["opening"]
    mult = _d(case.get("multiplier", "1"))
    opening_equity = (_d(opening["cash"])
                      + _d(opening["signedPos"]) * _d(opening["vwap"]) * mult)

    for i, step in enumerate(case["steps"]):
        e = step["expect"]
        pos = _d(e["signedPos"])
        cash = _d(e["cash"])
        mark = _d(step["mark"])
        gross = _d(e["grossRealizedPnl"])
        fees = _d(e["cumulativeFees"])
        ecf = _d(e["cumulativeExternalCashFlow"])
        stated_unreal = _d(e["unrealizedPnl"])
        stated_net = _d(e["netPnl"])
        where = f"{name} step {i} ({step['event']['kind']})"

        # 1. Unrealized must follow from the NOTIONAL, not from the (possibly rounded) vwap.
        #    Deriving it from vwap is the defect this guards: on recurring-vwap-partial-reduce the
        #    vwap-derived answer is not exact, and reading it back into an accounting total makes
        #    the identity drift.
        if pos == 0:
            assert e["vwap"] is None, f"{where}: a flat position's vwap must be null, not 0"
            expected_unreal = Decimal(0)
        else:
            assert e["vwap"] is not None, f"{where}: an open position needs a vwap"
            # Where a case omits signedNotional it equals signedPos * vwap exactly.
            notional = (_d(e["signedNotional"]) if "signedNotional" in e
                        else pos * _d(e["vwap"]))
            expected_unreal = (pos * mark - notional) * mult
        assert expected_unreal == stated_unreal, (
            f"{where}: unrealized {stated_unreal} != (signedPos*mark - signedNotional)*multiplier"
            f" = {expected_unreal}")

        # 2. netPnl must follow from the cash/position primitives.
        equity = cash + pos * mark * mult
        expected_net = equity - opening_equity - ecf
        assert expected_net == stated_net, (
            f"{where}: netPnl {stated_net} != equity - openingEquity - externalCashFlow"
            f" = {equity} - {opening_equity} - {ecf} = {expected_net}")

        # 3. The four-term identity: the whole point of D3.
        assert stated_net == gross + stated_unreal - fees, (
            f"{where}: {stated_net} != gross {gross} + unrealized {stated_unreal}"
            f" - fees {fees}")


def test_fee_case_proves_direct_pnl_equality_is_invalid():
    """Guards the reasoning, not just the numbers.

    If someone later 'simplifies' this fixture into a price-moving case, the counter-example
    to `pnl1 == pnl2` disappears and D3's justification quietly evaporates.
    """
    case = next(c for _, c in _cases() if c["name"] == "fees-only-no-price-move")
    last = case["steps"][-1]["expect"]
    assert _d(last["grossRealizedPnl"]) == 0, "the point is a ZERO gross trading PnL"
    assert _d(last["cumulativeFees"]) > 0
    assert _d(last["netPnl"]) != _d(last["grossRealizedPnl"]), (
        "gross and net must differ here, or this case no longer refutes a direct equality")


def test_overshoot_does_not_realize_on_the_whole_quantity():
    """The single most valuable behaviour in the old tracker; assert it cannot be eroded."""
    case = next(c for _, c in _cases() if c["name"] == "overshoot-long-to-short")
    reversal = case["steps"][1]
    assert _d(reversal["expect"]["grossRealizedPnl"]) == _d("500.00"), (
        "closing 100 of a 150 sell at +5.00 realizes 500.00; realizing on all 150 gives 750.00")
    assert _d(reversal["expect"]["signedPos"]) == _d("-50")
    assert _d(reversal["expect"]["vwap"]) == _d("55.00"), (
        "the new short's vwap is the reversal trade price, not a blend with the closed long")


def test_every_case_carries_a_derivation():
    for name, case in _cases():
        for i, step in enumerate(case["steps"]):
            assert step.get("derivation", "").strip(), (
                f"{name} step {i} has no derivation; independence must be auditable")


# ---------------------------------------------------------------------------
# Standalone runner: `python tests/test_fixtures_consistency.py`.
# The fixtures are the spec for D1-D5, so checking them must never be blocked on
# having a test runner installed.
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    import sys
    import traceback

    failures = 0
    checks = 0
    test_fixture_file_is_present_and_declares_its_assumptions()
    print("assumptions declared: ok")
    for _name, _case in _cases():
        checks += 1
        try:
            test_reconciliation_identity_holds_at_every_checkpoint.__wrapped__(_name, _case) \
                if hasattr(test_reconciliation_identity_holds_at_every_checkpoint, "__wrapped__") \
                else test_reconciliation_identity_holds_at_every_checkpoint(_name, _case)
            print(f"  identity ok: {_name} ({len(_case['steps'])} checkpoints)")
        except AssertionError:
            failures += 1
            print(f"  IDENTITY FAILED: {_name}")
            traceback.print_exc()
    for fn in (test_fee_case_proves_direct_pnl_equality_is_invalid,
               test_overshoot_does_not_realize_on_the_whole_quantity,
               test_every_case_carries_a_derivation):
        checks += 1
        try:
            fn()
            print(f"  ok: {fn.__name__}")
        except AssertionError:
            failures += 1
            print(f"  FAILED: {fn.__name__}")
            traceback.print_exc()
    total_steps = sum(len(c["steps"]) for _, c in _cases())
    print(f"\n{checks} checks over {len(_cases())} cases / {total_steps} checkpoints; "
          f"{failures} failure(s)")
    sys.exit(1 if failures else 0)
