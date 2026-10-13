/*
 * SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
 * Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
 *
 * This file is part of pulse-finance.
 *
 * pulse-finance is dual-licensed:
 *   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
 *   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
 *     Contact operations@inventzia.com.
 */
package com.inventzia.pulse.finance.position;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The kernel against the shared, cross-language accounting fixtures.
 *
 * <p>The fixtures ({@code tests/fixtures/position_accounting.json}, mapped onto the test classpath
 * by {@code <testResources>}) are the specification, and they are derived from first principles
 * rather than captured from the legacy implementation - which has measured precision and accounting
 * defects, so its output cannot be the oracle. The same file drives
 * {@code tests/test_fixtures_consistency.py}, so neither language owns the contract.
 *
 * <p>Comparisons use {@link BigDecimal#compareTo} rather than {@code equals}: {@code 0} and
 * {@code 0.00} are the same number and differ only in scale, and asserting on scale here would be
 * asserting D2's answer before D2 is decided.
 */
class PositionTrackerFixtureTest {

    private static final String RESOURCE = "/fixtures/position_accounting.json";

    private static JsonNode fixtures() throws IOException {
        try (InputStream in = PositionTrackerFixtureTest.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        RESOURCE + " is not on the test classpath. It is mapped from "
                        + "tests/fixtures by <testResources> in core/java/pom.xml - check that "
                        + "mapping rather than copying the file.");
            }
            return new ObjectMapper().readTree(in);
        }
    }

    private static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asText());
    }

    /** One dynamic test per fixture case, so a failure names the case rather than a line number. */
    @TestFactory
    List<DynamicTest> everyFixtureCase() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode kase : fixtures().get("cases")) {
            tests.add(DynamicTest.dynamicTest(kase.get("name").asText(), () -> runCase(kase)));
        }
        assertThat(tests).as("fixtures must not be empty").isNotEmpty();
        return tests;
    }

    private void runCase(JsonNode kase) {
        JsonNode opening = kase.get("opening");
        // The fixtures declare openingEquity = openingCash + openingPos * openingVwap, i.e. the
        // opening mark IS the acquisition VWAP, so opening unrealized is zero by construction.
        // Passing vwap as both arguments is that assumption made explicit rather than implied.
        // Each case declares its own multiplier (default 1), so multiplied-contract-cl exercises
        // CL at 1000 through exactly the same assertions as every other case.
        BigDecimal multiplier = kase.has("multiplier")
                ? dec(kase.get("multiplier")) : BigDecimal.ONE;
        PositionTracker t = PositionTracker.openingPeriod(
                dec(opening.get("cash")),
                dec(opening.get("signedPos")),
                dec(opening.get("vwap")),
                dec(opening.get("vwap")),
                multiplier);

        int step = 0;
        for (JsonNode s : kase.get("steps")) {
            JsonNode event = s.get("event");
            String kind = event.get("kind").asText();
            switch (kind) {
                case "trade" -> t.applyTrade(
                        Side.valueOf(event.get("side").asText()),
                        dec(event.get("qty")),
                        dec(event.get("price")),
                        event.has("fee") ? dec(event.get("fee")) : BigDecimal.ZERO,
                        event.has("executionId") ? event.get("executionId").asText() : null);
                case "fee"      -> t.applyFee(dec(event.get("amount")));
                case "cashflow" -> t.applyExternalCashFlow(dec(event.get("amount")));
                case "mark"     -> { /* a mark changes no state; it only re-prices */ }
                default -> throw new IllegalStateException("unknown fixture event kind: " + kind);
            }

            BigDecimal mark = dec(s.get("mark"));
            JsonNode e = s.get("expect");
            String where = kase.get("name").asText() + " step " + step
                           + " (" + kind + "): " + s.get("derivation").asText();

            assertThat(t.signedPosition()).as(where + " [signedPos]")
                    .usingComparator(BigDecimal::compareTo).isEqualTo(dec(e.get("signedPos")));
            assertThat(t.cash()).as(where + " [cash]")
                    .usingComparator(BigDecimal::compareTo).isEqualTo(dec(e.get("cash")));
            assertThat(t.grossRealizedPnl()).as(where + " [grossRealizedPnl]")
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(dec(e.get("grossRealizedPnl")));
            assertThat(t.cumulativeFees()).as(where + " [cumulativeFees]")
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(dec(e.get("cumulativeFees")));
            assertThat(t.cumulativeExternalCashFlow()).as(where + " [externalCashFlow]")
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(dec(e.get("cumulativeExternalCashFlow")));
            assertThat(t.unrealizedPnlThisPeriod(mark)).as(where + " [unrealizedPnl]")
                    .usingComparator(BigDecimal::compareTo).isEqualTo(dec(e.get("unrealizedPnl")));
            assertThat(t.netPnl(mark)).as(where + " [netPnl]")
                    .usingComparator(BigDecimal::compareTo).isEqualTo(dec(e.get("netPnl")));

            // The notional is the accounting source of truth, so assert it wherever a case
            // states it - that is what pins the "never derive accounting from a rounded vwap"
            // rule rather than merely documenting it.
            if (e.has("signedNotional")) {
                assertThat(t.signedNotional()).as(where + " [signedNotional]")
                        .usingComparator(BigDecimal::compareTo)
                        .isEqualTo(dec(e.get("signedNotional")));
            }

            Optional<BigDecimal> vwap = t.vwap();
            if (e.get("vwap").isNull()) {
                assertThat(vwap).as(where + " [vwap must be empty when flat]").isEmpty();
            } else {
                assertThat(vwap).as(where + " [vwap present]").isPresent();
                assertThat(vwap.orElseThrow()).as(where + " [vwap]")
                        .usingComparator(BigDecimal::compareTo).isEqualTo(dec(e.get("vwap")));
            }

            // The identity must hold at every checkpoint, not only at the end.
            assertThat(t.reconciliationDiscrepancy(mark)).as(where + " [reconciliation]")
                    .usingComparator(BigDecimal::compareTo).isEqualTo(BigDecimal.ZERO);
            step++;
        }
    }

    // ------------------------------------------------------------------
    // Behaviours the fixtures cannot express, because they are about refusal.
    // ------------------------------------------------------------------

    @Test
    void aRecurringVwapIsRoundedForReportingAndNeverEntersAccounting() {
        // 301 / 3 recurs. Under D2 that is a defined value at CALCULATION_SCALE, not an error -
        // an earlier version threw here, which made an ordinary book unable to report its own VWAP.
        PositionTracker t = PositionTracker.startingFlat();
        t.applyTrade(Side.BUY, new BigDecimal("1"), new BigDecimal("100.00"));
        t.applyTrade(Side.BUY, new BigDecimal("2"), new BigDecimal("100.50"));

        assertThat(t.signedPosition()).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(t.signedNotional()).as("the notional stays exact")
                .isEqualByComparingTo(new BigDecimal("301.00"));
        assertThat(t.vwap()).isPresent();
        assertThat(t.vwap().orElseThrow())
                .as("reporting value, rounded at the declared scale")
                .isEqualByComparingTo(new BigDecimal("100.333333333333"));

        // The accounting figures must NOT be derived from that rounded value.
        BigDecimal mark = new BigDecimal("101");
        assertThat(t.unrealizedPnl(mark))
                .as("( 3 * 101 - 301.00 ) * 1 = 2.00, exactly")
                .isEqualByComparingTo(new BigDecimal("2.00"));
        assertThat(t.reconciliationDiscrepancy(mark))
                .as("the identity must be exact despite a recurring VWAP")
                .isEqualByComparingTo(BigDecimal.ZERO);

        // And across a partial reduce, where costRemoved itself rounds, the rounding in realized
        // must cancel against the rounding in the remaining notional.
        t.applyTrade(Side.SELL, new BigDecimal("1"), new BigDecimal("102"));
        assertThat(t.reconciliationDiscrepancy(new BigDecimal("99")))
                .as("still exact after a reduce with an inexact allocated cost")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void aPositionThatWouldOverflowInt64IsRefused() {
        // Validating only the incoming quantity is not enough: Long.MAX_VALUE units plus one more
        // passes every per-trade check and still leaves 9223372036854775808, which
        // Position.signedPosition (int64) cannot carry.
        PositionTracker t = PositionTracker.startingFlat();
        t.applyTrade(Side.BUY, new BigDecimal(Long.MAX_VALUE), BigDecimal.ONE);
        assertThat(t.signedPosition()).isEqualByComparingTo(new BigDecimal(Long.MAX_VALUE));

        assertThatThrownBy(() -> t.applyTrade(Side.BUY, BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not fit int64");

        // A short may reach Long.MIN_VALUE, which IS publishable - the range is asymmetric.
        PositionTracker u = PositionTracker.startingFlat();
        u.applyTrade(Side.SELL, new BigDecimal(Long.MIN_VALUE).negate(), BigDecimal.ONE);
        assertThat(u.signedPosition()).isEqualByComparingTo(new BigDecimal(Long.MIN_VALUE));

        // Opening and restoring are validated too, not just trading.
        assertThatThrownBy(() -> PositionTracker.openingPeriod(BigDecimal.ZERO,
                        new BigDecimal(Long.MAX_VALUE).add(BigDecimal.ONE),
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not fit int64");
    }

    @Test
    void theMultiplierScalesRealizedAndUnrealizedPnlTogether() {
        // CL is 1000 bbl. The multiplier must scale the WHOLE realized expression: an earlier
        // draft multiplied the proceeds but not the removed cost, turning 60,000 into 1,558,500.
        BigDecimal m = new BigDecimal("1000");
        PositionTracker t = PositionTracker.startingFlat(m);
        t.applyTrade(Side.BUY, new BigDecimal("100"), new BigDecimal("50"));

        assertThat(t.cash()).as("100 * 50 * 1000")
                .isEqualByComparingTo(new BigDecimal("-5000000"));
        assertThat(t.signedNotional()).as("the notional is stored UNmultiplied")
                .isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(t.unrealizedPnl(new BigDecimal("52")))
                .as("( 100*52 - 5000 ) * 1000")
                .isEqualByComparingTo(new BigDecimal("200000"));

        t.applyTrade(Side.SELL, new BigDecimal("30"), new BigDecimal("52"));
        assertThat(t.grossRealizedPnl()).as("30 * 2 * 1000, not 1,558,500")
                .isEqualByComparingTo(new BigDecimal("60000"));
        assertThat(t.reconciliationDiscrepancy(new BigDecimal("52")))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void aFlatPositionHasNoVwap() {
        PositionTracker t = PositionTracker.startingFlat();
        assertThat(t.vwap()).isEmpty();
        t.applyTrade(Side.BUY, new BigDecimal("10"), new BigDecimal("5"));
        t.applyTrade(Side.SELL, new BigDecimal("10"), new BigDecimal("6"));
        assertThat(t.vwap()).as("flat again: no average price exists").isEmpty();
        assertThat(t.unrealizedPnl(new BigDecimal("99")))
                .usingComparator(BigDecimal::compareTo).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void aDuplicateExecutionIsReportedAsSuchRatherThanAsAZeroSizedReduce() {
        PositionTracker t = PositionTracker.startingFlat();
        TradeOutcome first = t.applyTrade(Side.BUY, new BigDecimal("10"),
                new BigDecimal("10"), BigDecimal.ZERO, "E-1");
        assertThat(first.kind()).isEqualTo(TradeOutcome.Kind.OPEN);

        TradeOutcome again = t.applyTrade(Side.BUY, new BigDecimal("10"),
                new BigDecimal("10"), BigDecimal.ZERO, "E-1");
        assertThat(again.kind()).isEqualTo(TradeOutcome.Kind.IGNORED_DUPLICATE);
        assertThat(t.hasApplied("E-1")).isTrue();
        assertThat(t.signedPosition()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("10"));
        assertThat(t.cash()).as("a duplicate must not move cash either")
                .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("-100"));
    }

    @Test
    void theReversalCaseIsNamed() {
        // Guards the decomposition itself: if a refactor merged REVERSE into CLOSE+OPEN silently,
        // the arithmetic might still pass while the case analysis became untestable.
        PositionTracker t = PositionTracker.startingFlat();
        t.applyTrade(Side.BUY, new BigDecimal("100"), new BigDecimal("50"));
        TradeOutcome o = t.applyTrade(Side.SELL, new BigDecimal("150"), new BigDecimal("55"));

        assertThat(o.kind()).isEqualTo(TradeOutcome.Kind.REVERSE);
        assertThat(o.isReversal()).isTrue();
        assertThat(o.closedQuantity()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("100"));
        assertThat(o.realizedPnl()).as("on the closed 100 only, not all 150")
                .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("500"));
        assertThat(o.openedQuantity()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("50"));
        assertThat(o.openedAtPrice()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("55"));
    }

    @Test
    void refusesNonPositiveQuantitiesAndNegativeFeesButNotNonPositivePrices() {
        PositionTracker t = PositionTracker.startingFlat();
        assertThatThrownBy(() -> t.applyTrade(Side.BUY, BigDecimal.ZERO, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("quantity");
        assertThatThrownBy(() -> t.applyTrade(Side.BUY, BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("-1"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fee");
        // A price of zero is NOT an error. Guarding price <= 0 is the old implementation's
        // mistake, unsuitable for the very instrument in our sample data.
        t.applyTrade(Side.BUY, BigDecimal.ONE, BigDecimal.ZERO);
        assertThat(t.signedPosition()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(BigDecimal.ONE);
    }

    // ------------------------------------------------------------------
    // Regressions from the review of 2026-10-11. Each reproduces a specific defect.
    // ------------------------------------------------------------------

    @Test
    void aRejectedTradeLeavesEveryFieldUnchanged() {
        // Originally this reproduced a recurring-VWAP rejection; under D2 that no longer throws,
        // so the trigger is now the int64 range check - which, like the division before it, is
        // reached only AFTER cash and fees have been computed. The defect being guarded is the
        // same: an earlier version moved cash, charged the fee and recorded the execution id
        // before the failing step, leaving state that then looked like a duplicate on retry.
        PositionTracker t = PositionTracker.startingFlat();
        t.applyTrade(Side.BUY, new BigDecimal(Long.MAX_VALUE), new BigDecimal("100"));

        BigDecimal cashBefore = t.cash();
        BigDecimal positionBefore = t.signedPosition();
        BigDecimal feesBefore = t.cumulativeFees();
        BigDecimal grossBefore = t.grossRealizedPnl();

        assertThatThrownBy(() -> t.applyTrade(Side.BUY, BigDecimal.ONE,
                        new BigDecimal("102"), BigDecimal.ONE, "E-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not fit int64");

        assertThat(t.cash()).as("cash must not move").isEqualByComparingTo(cashBefore);
        assertThat(t.signedPosition()).isEqualByComparingTo(positionBefore);
        assertThat(t.cumulativeFees()).as("the fee must not be charged")
                .isEqualByComparingTo(feesBefore);
        assertThat(t.grossRealizedPnl()).isEqualByComparingTo(grossBefore);
        assertThat(t.hasApplied("E-1")).as("a rejected trade must not claim its id").isFalse();

        // Retrying must re-throw, not be swallowed as a duplicate...
        assertThatThrownBy(() -> t.applyTrade(Side.BUY, BigDecimal.ONE,
                        new BigDecimal("102"), BigDecimal.ONE, "E-1"))
                .isInstanceOf(IllegalArgumentException.class);

        // ...and once the cause is gone - here by selling first, so the buy fits again - the same
        // id applies normally, exactly once.
        t.applyTrade(Side.SELL, new BigDecimal("10"), new BigDecimal("102"));
        assertThat(t.applyTrade(Side.BUY, BigDecimal.ONE, new BigDecimal("102"),
                        BigDecimal.ONE, "E-1").kind())
                .isEqualTo(TradeOutcome.Kind.ADD);
        assertThat(t.applyTrade(Side.BUY, BigDecimal.ONE, new BigDecimal("102"),
                        BigDecimal.ONE, "E-1").kind())
                .isEqualTo(TradeOutcome.Kind.IGNORED_DUPLICATE);
    }

    @Test
    void aFlatBookValuesAtAnyMarkIncludingZero() {
        // Valuing nothing must not require a usable market price. The fixture
        // external-cash-flow-no-trades carries mark 0.00 for exactly this reason.
        PositionTracker t = PositionTracker.startingFlat();
        t.applyExternalCashFlow(new BigDecimal("10000.00"));

        assertThat(t.unrealizedPnl(BigDecimal.ZERO)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(t.positionValue(BigDecimal.ZERO)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(t.netPnl(BigDecimal.ZERO)).as("a deposit is not profit")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(t.reconciliationDiscrepancy(BigDecimal.ZERO))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void acceptsNegativePrices() {
        // CL settled negative in April 2020. The old price <= 0 guard was named in the
        // transition review as unsuitable for the instrument in our own sample data.
        PositionTracker t = PositionTracker.startingFlat();
        t.applyTrade(Side.BUY, new BigDecimal("10"), new BigDecimal("-37.63"));

        assertThat(t.signedPosition()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(t.cash()).as("a buyer at a negative price is paid to take delivery")
                .isEqualByComparingTo(new BigDecimal("376.30"));

        t.applyTrade(Side.SELL, new BigDecimal("10"), new BigDecimal("10.00"));
        assertThat(t.grossRealizedPnl()).isEqualByComparingTo(new BigDecimal("476.30"));
        assertThat(t.reconciliationDiscrepancy(new BigDecimal("10.00")))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void refusesAFractionalQuantityBecauseTheDatumCannotCarryOne() {
        // The kernel must not be able to reach a state its own wire format cannot express:
        // Position.signedPosition is int64.
        PositionTracker t = PositionTracker.startingFlat();
        assertThatThrownBy(() -> t.applyTrade(Side.BUY, new BigDecimal("1.5"),
                        new BigDecimal("100")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole number")
                .hasMessageContaining("int64");
        assertThat(t.signedPosition()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(t.cash()).isEqualByComparingTo(BigDecimal.ZERO);

        // A trailing zero is not fractional: 10.00 is a whole number of units.
        t.applyTrade(Side.BUY, new BigDecimal("10.00"), new BigDecimal("5"));
        assertThat(t.signedPosition()).isEqualByComparingTo(new BigDecimal("10"));
    }

    @Test
    void openingAPeriodMidLifeDoesNotReportPreExistingGainAsProfit() {
        // One unit acquired at 100, marked at 120 when the period opens. The acquisition gain
        // of 20 belongs to an earlier period and must not appear as this period's PnL - and
        // the reconciliation identity must still hold, which it cannot if the baseline uses
        // the mark while unrealized uses the VWAP.
        PositionTracker t = PositionTracker.openingPeriod(BigDecimal.ZERO, BigDecimal.ONE,
                new BigDecimal("100"), new BigDecimal("120"), BigDecimal.ONE);

        assertThat(t.openingUnrealizedPnl()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(t.netPnl(new BigDecimal("120"))).as("no movement yet, so no PnL")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(t.unrealizedPnlThisPeriod(new BigDecimal("120")))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(t.reconciliationDiscrepancy(new BigDecimal("120")))
                .isEqualByComparingTo(BigDecimal.ZERO);

        // Movement during the period IS reported, in both directions.
        assertThat(t.netPnl(new BigDecimal("130"))).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(t.netPnl(new BigDecimal("90"))).isEqualByComparingTo(new BigDecimal("-30"));
        assertThat(t.reconciliationDiscrepancy(new BigDecimal("90")))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void restoringPreservesTotalsBaselineAndAppliedExecutionIds() {
        // Restoration is not "open a period from a position": a restored tracker that has
        // forgotten its applied ids double-counts the first replayed fill.
        PositionTracker original = PositionTracker.startingFlat();
        original.applyTrade(Side.BUY, new BigDecimal("10"), new BigDecimal("50"),
                new BigDecimal("2.50"), "E-1");
        original.applyTrade(Side.SELL, new BigDecimal("4"), new BigDecimal("55"),
                BigDecimal.ZERO, "E-2");
        original.applyExternalCashFlow(new BigDecimal("1000"));

        PositionTracker restored = PositionTracker.restore(original.snapshot());

        BigDecimal mark = new BigDecimal("53");
        assertThat(restored.signedPosition()).isEqualByComparingTo(original.signedPosition());
        assertThat(restored.cash()).isEqualByComparingTo(original.cash());
        assertThat(restored.grossRealizedPnl()).isEqualByComparingTo(original.grossRealizedPnl());
        assertThat(restored.cumulativeFees()).isEqualByComparingTo(original.cumulativeFees());
        assertThat(restored.cumulativeExternalCashFlow())
                .isEqualByComparingTo(original.cumulativeExternalCashFlow());
        assertThat(restored.openingEquity()).isEqualByComparingTo(original.openingEquity());
        assertThat(restored.netPnl(mark)).isEqualByComparingTo(original.netPnl(mark));
        assertThat(restored.reconciliationDiscrepancy(mark))
                .isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(restored.hasApplied("E-1")).as("a replayed fill must stay a duplicate").isTrue();
        assertThat(restored.hasApplied("E-2")).isTrue();
        assertThat(restored.applyTrade(Side.BUY, new BigDecimal("10"), new BigDecimal("50"),
                        new BigDecimal("2.50"), "E-1").kind())
                .isEqualTo(TradeOutcome.Kind.IGNORED_DUPLICATE);
    }
}
