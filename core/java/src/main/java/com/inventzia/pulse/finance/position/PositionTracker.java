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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Position and PnL accounting for one instrument. Pure: no engine, no persistence, no clock.
 *
 * <p>Deliberately independent of pulse-beacon and of any datum type. The accounting is the part
 * that must be provably right, and it is far easier to prove against fixtures than inside a running
 * engine. See {@code initialtransitioning.md}.
 *
 * <h2>Atomicity</h2>
 * <p>Every input either applies completely or changes nothing. {@link #applyTrade} computes the
 * whole next state into locals before assigning any field, and records the execution id only once
 * the computation has succeeded. This matters because {@link #vwap() VWAP division can throw}: an
 * earlier version mutated cash, fees and the applied-id set first, so a rejected sell left the
 * position unchanged but the cash moved, and retrying it returned
 * {@link TradeOutcome.Kind#IGNORED_DUPLICATE} - corrupt state that then looked like a duplicate.
 *
 * <h2>Quantities are integral</h2>
 * <p>{@link BigDecimal} carries them so that settling D1 does not change the type, but a
 * non-integral quantity is <em>rejected</em>. The reason is a contract, not a preference: the
 * generated {@code Position} datum declares {@code signedPosition} as {@code int64}, so a
 * fractional position could not be published faithfully. Refusing it here means the kernel cannot
 * reach a state its own wire format cannot express. Relaxing one requires relaxing both, together,
 * as a wire version bump.
 *
 * <h2>Prices may be zero or negative</h2>
 * <p>Only quantities are required positive. A price is any finite decimal, because rejecting
 * {@code price <= 0} is wrong for real instruments - CL settled negative in April 2020, and the old
 * implementation's {@code price <= 0} guard was identified in the transition review as unsuitable
 * for exactly the instrument in our own sample data. Reinstating it here would have been the same
 * mistake. A mark is likewise unconstrained, and a flat position reports zero unrealized PnL
 * without consulting the mark at all - no market price should be needed to value nothing.
 *
 * <h2>Reporting baseline</h2>
 * <p>Every PnL figure is measured against an explicit baseline: {@code openingEquity} plus the
 * {@code openingUnrealizedPnl} already present at it. Netting off that opening unrealized is what
 * keeps the reconciliation identity true when a tracker starts mid-life - without it, restoring one
 * unit acquired at 100 and currently marked at 120 reports 20 of profit before any market movement,
 * and the identity's two sides disagree by exactly that 20.
 *
 * <p>Not thread-safe; one instance per instrument, mutated by one caller.
 */
public final class PositionTracker {

    /**
     * Scale and rounding for every division (D2).
     *
     * <p>12 digits because that is {@code StorageMapping}'s {@code DECIMAL(38,12)} default: choosing
     * any other calculation scale guarantees a mismatch between what is computed and what survives a
     * persistence round trip. {@code HALF_EVEN} because it does not bias a long run of roundings.
     *
     * <p>An earlier version divided exactly and threw on a non-terminating quotient. That made the
     * missing policy visible, which was the point, but it is not shippable: an ordinary book
     * (1 @ 100, then 2 @ 101) could not report its own VWAP.
     */
    public static final int CALCULATION_SCALE = 12;
    public static final RoundingMode CALCULATION_ROUNDING = RoundingMode.HALF_EVEN;

    private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);
    private static final BigDecimal LONG_MIN = BigDecimal.valueOf(Long.MIN_VALUE);

    private final BigDecimal multiplier;

    /** The reporting baseline: equity at the start of the period this tracker measures. */
    private final BigDecimal openingEquity;

    /** Unrealized PnL already present at the baseline, excluded from every figure reported. */
    private final BigDecimal openingUnrealizedPnl;

    private BigDecimal signedPosition;

    /**
     * Signed cost notional of the open position, {@code signedPosition * vwap}.
     *
     * <p>Carried as a notional rather than a unit price so accumulation is additive and the division
     * happens once, on read. The old implementation had this right.
     */
    private BigDecimal signedNotional;

    private BigDecimal cash;
    private BigDecimal grossRealizedPnl;
    private BigDecimal cumulativeFees;
    private BigDecimal cumulativeExternalCashFlow;

    /** Execution ids already applied, for D4 duplicate rejection. */
    private final Set<String> appliedExecutionIds;

    private PositionTracker(BigDecimal multiplier, BigDecimal openingEquity,
                            BigDecimal openingUnrealizedPnl, BigDecimal signedPosition,
                            BigDecimal signedNotional, BigDecimal cash,
                            BigDecimal grossRealizedPnl, BigDecimal cumulativeFees,
                            BigDecimal cumulativeExternalCashFlow,
                            Set<String> appliedExecutionIds) {
        this.multiplier = multiplier;
        this.openingEquity = openingEquity;
        this.openingUnrealizedPnl = openingUnrealizedPnl;
        this.signedPosition = signedPosition;
        this.signedNotional = signedNotional;
        this.cash = cash;
        this.grossRealizedPnl = grossRealizedPnl;
        this.cumulativeFees = cumulativeFees;
        this.cumulativeExternalCashFlow = cumulativeExternalCashFlow;
        this.appliedExecutionIds = new LinkedHashSet<>(appliedExecutionIds);
    }

    // ------------------------------------------------------------------
    // Construction. Three named entry points, because "start flat", "start a reporting period
    // from a position I already hold" and "restore a tracker exactly" need different inputs and
    // mean different things. A single constructor taking (cash, position, vwap) silently conflated
    // the second and third, and got the second's baseline wrong.
    // ------------------------------------------------------------------

    /** A tracker starting flat, with no cash and a unit multiplier. */
    public static PositionTracker startingFlat() {
        return startingFlat(BigDecimal.ONE);
    }

    /** A tracker starting flat with the given contract multiplier (D1). */
    public static PositionTracker startingFlat(BigDecimal multiplier) {
        return new PositionTracker(requirePositive(multiplier, "multiplier"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Set.of());
    }

    /**
     * Begin a new reporting period holding an existing position: acquisition-based accounting whose
     * baseline is the opening <em>market</em> valuation.
     *
     * <p>Use this to start a day, a run or a mandate from a position carried in. Cumulative totals
     * start at zero by definition - this is a new period, not a continuation - so it is <b>not</b>
     * restoration; for that use {@link #restore(PositionSnapshot)}, which also preserves the
     * applied execution ids this method necessarily starts empty.
     *
     * <p>{@code openingVwap} is what the position cost; {@code openingMark} is what it was worth
     * when the period began. They are different inputs on purpose. The baseline is the mark, and
     * the unrealized PnL already sitting between the two is recorded and netted out, so the period
     * reports only what happens during it.
     *
     * @param openingVwap per-unit acquisition VWAP; ignored when the position is zero
     * @param openingMark per-unit market price at the period's start; ignored when flat
     */
    public static PositionTracker openingPeriod(BigDecimal openingCash, BigDecimal openingPosition,
                                                BigDecimal openingVwap, BigDecimal openingMark,
                                                BigDecimal multiplier) {
        requirePositive(multiplier, "multiplier");
        Objects.requireNonNull(openingCash, "openingCash");
        requireIntegral(Objects.requireNonNull(openingPosition, "openingPosition"),
                "openingPosition");
        requirePublishable(openingPosition, "openingPosition");

        if (openingPosition.signum() == 0) {
            return new PositionTracker(multiplier, openingCash, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, openingCash,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Set.of());
        }
        Objects.requireNonNull(openingVwap, "openingVwap");
        Objects.requireNonNull(openingMark, "openingMark");
        BigDecimal notional = openingPosition.multiply(openingVwap);
        BigDecimal baseline = openingCash.add(openingPosition.multiply(openingMark)
                .multiply(multiplier));
        BigDecimal openingUnrealized = openingPosition.multiply(openingMark.subtract(openingVwap))
                .multiply(multiplier);
        return new PositionTracker(multiplier, baseline, openingUnrealized, openingPosition,
                notional, openingCash, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Set.of());
    }

    /**
     * Restore a tracker to an exact earlier state, cumulative totals, reporting baseline and
     * applied execution ids included.
     *
     * <p>This is the recovery path. Everything a continuing tracker must not forget travels in the
     * snapshot - in particular the applied execution ids, without which the first replayed fill
     * after recovery is counted twice.
     */
    public static PositionTracker restore(PositionSnapshot s) {
        Objects.requireNonNull(s, "snapshot");
        requireIntegral(s.signedPosition(), "snapshot.signedPosition");
        requirePublishable(s.signedPosition(), "snapshot.signedPosition");
        return new PositionTracker(s.multiplier(), s.openingEquity(), s.openingUnrealizedPnl(),
                s.signedPosition(), s.signedNotional(), s.cash(), s.grossRealizedPnl(),
                s.cumulativeFees(), s.cumulativeExternalCashFlow(), s.appliedExecutionIds());
    }

    /** This tracker's complete state, for persistence or a later {@link #restore}. */
    public PositionSnapshot snapshot() {
        return new PositionSnapshot(cash, signedPosition, signedNotional, grossRealizedPnl,
                cumulativeFees, cumulativeExternalCashFlow, openingEquity, openingUnrealizedPnl,
                multiplier, appliedExecutionIds);
    }

    // ------------------------------------------------------------------
    // Inputs
    // ------------------------------------------------------------------

    /** Apply a trade, with no fee and no execution id. */
    public TradeOutcome applyTrade(Side side, BigDecimal quantity, BigDecimal price) {
        return applyTrade(side, quantity, price, BigDecimal.ZERO, null);
    }

    /**
     * Apply a trade, atomically.
     *
     * <p>Either every field moves or none does, and the execution id is recorded last. A rejected
     * trade - a non-integral quantity, a negative fee, or a VWAP whose division does not terminate -
     * leaves the tracker exactly as it was and may be retried once the cause is fixed.
     *
     * <p>The fee reduces cash and accumulates in {@link #cumulativeFees()}. It does <b>not</b> enter
     * the VWAP and does <b>not</b> reduce {@link #grossRealizedPnl()}: those are separate terms by
     * design (D3), and conflating them is what made the old pair of PnL figures impossible to
     * reconcile.
     *
     * @param executionId the venue's execution identifier, or {@code null} if none. When non-null
     *                    and already seen the trade is ignored and
     *                    {@link TradeOutcome.Kind#IGNORED_DUPLICATE} returned (D4).
     */
    public TradeOutcome applyTrade(Side side, BigDecimal quantity, BigDecimal price,
                                   BigDecimal fee, String executionId) {
        Objects.requireNonNull(side, "side");
        requirePositive(quantity, "quantity");
        requireIntegral(quantity, "quantity");
        requireFinitePrice(price, "price");
        Objects.requireNonNull(fee, "fee");
        if (fee.signum() < 0) {
            throw new IllegalArgumentException("fee must not be negative: " + fee);
        }

        if (executionId != null && appliedExecutionIds.contains(executionId)) {
            return ignoredDuplicate();
        }

        // ---- compute the whole next state into locals; assign nothing yet ----
        BigDecimal signedQuantity = quantity.multiply(BigDecimal.valueOf(side.sign()));
        BigDecimal positionBefore = signedPosition;
        BigDecimal consideration = quantity.multiply(price).multiply(multiplier);

        BigDecimal nextCash = cash.subtract(consideration.multiply(BigDecimal.valueOf(side.sign())));
        BigDecimal nextFees = cumulativeFees;
        if (fee.signum() > 0) {
            nextCash = nextCash.subtract(fee);
            nextFees = nextFees.add(fee);
        }

        BigDecimal nextPosition;
        BigDecimal nextNotional;
        BigDecimal nextGross = grossRealizedPnl;
        TradeOutcome outcome;

        if (positionBefore.signum() == 0) {
            nextPosition = signedQuantity;
            nextNotional = signedQuantity.multiply(price);
            outcome = new TradeOutcome(TradeOutcome.Kind.OPEN, BigDecimal.ZERO, BigDecimal.ZERO,
                    quantity, price);
        } else if (positionBefore.signum() == side.sign()) {
            // Same direction: the position grows and its VWAP moves. No PnL realized.
            nextPosition = positionBefore.add(signedQuantity);
            nextNotional = signedNotional.add(signedQuantity.multiply(price));
            outcome = new TradeOutcome(TradeOutcome.Kind.ADD, BigDecimal.ZERO, BigDecimal.ZERO,
                    quantity, price);
        } else {
            BigDecimal exposure = positionBefore.abs();
            BigDecimal closedQuantity = quantity.min(exposure);

            // Realized PnL is computed from the signed NOTIONAL, never from a divided VWAP.
            // Reading a rounded VWAP back into an accumulating total would let that rounding
            // enter PnL permanently and compound across successive reduces.
            //
            //   costRemoved = notionalBefore * closedQty / exposure      (the only division)
            //   realized    = ( -signedClosedQty * price - costRemoved ) * multiplier
            //
            // The multiplier scales the WHOLE bracket: the notional is stored unmultiplied, so
            // multiplying only the proceeds mixes two different units - with CL's multiplier of
            // 1000 that error turned a realized 60,000 into 1,558,500.
            //
            // When closedQuantity == exposure (a close or a reversal) the division is exact and
            // costRemoved == notionalBefore, so only a partial reduce rounds at all.
            BigDecimal costRemoved = closedQuantity.compareTo(exposure) == 0
                    ? signedNotional
                    : signedNotional.multiply(closedQuantity)
                            .divide(exposure, CALCULATION_SCALE, CALCULATION_ROUNDING);
            BigDecimal signedClosedQuantity = closedQuantity.multiply(
                    BigDecimal.valueOf(side.sign()));
            BigDecimal realized = signedClosedQuantity.multiply(price).negate()
                    .subtract(costRemoved)
                    .multiply(multiplier);
            nextGross = grossRealizedPnl.add(realized);

            BigDecimal remainder = quantity.subtract(closedQuantity);
            if (remainder.signum() > 0) {
                // Reversal: flat through zero, then the remainder opens at the trade price.
                nextPosition = remainder.multiply(BigDecimal.valueOf(side.sign()));
                nextNotional = nextPosition.multiply(price);
                outcome = new TradeOutcome(TradeOutcome.Kind.REVERSE, closedQuantity, realized,
                        remainder, price);
            } else {
                nextPosition = positionBefore.add(signedQuantity);
                if (nextPosition.signum() == 0) {
                    nextNotional = BigDecimal.ZERO;
                    outcome = new TradeOutcome(TradeOutcome.Kind.CLOSE, closedQuantity, realized,
                            BigDecimal.ZERO, null);
                } else {
                    // The remaining notional is what was NOT allocated to the closed portion.
                    // Deriving it as nextPosition * vwapBefore instead would reintroduce the
                    // divided VWAP, and any rounding in it would then no longer cancel against
                    // the rounding in realized - which is what keeps the identity exact.
                    nextNotional = signedNotional.subtract(costRemoved);
                    outcome = new TradeOutcome(TradeOutcome.Kind.REDUCE, closedQuantity, realized,
                            BigDecimal.ZERO, null);
                }
            }
        }

        // The position must be publishable before anything is committed: Position.signedPosition
        // is int64, and a tracker that overflows it has reached a state its own wire format cannot
        // express.
        requirePublishable(nextPosition, "resulting position");

        // ---- commit ----
        cash = nextCash;
        cumulativeFees = nextFees;
        signedPosition = nextPosition;
        signedNotional = nextNotional;
        grossRealizedPnl = nextGross;
        if (executionId != null) {
            appliedExecutionIds.add(executionId);      // last: only a applied trade is recorded
        }
        return outcome;
    }

    /** A standalone fee (a commission billed separately from its fill). */
    public void applyFee(BigDecimal fee) {
        requirePositive(fee, "fee");
        cash = cash.subtract(fee);
        cumulativeFees = cumulativeFees.add(fee);
    }

    /**
     * A deposit (positive) or withdrawal (negative) that is not a trade.
     *
     * <p>Tracked separately because it changes equity without producing trading PnL. Omitting the
     * term would report a 10,000 deposit as 10,000 of profit.
     */
    public void applyExternalCashFlow(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        cash = cash.add(amount);
        cumulativeExternalCashFlow = cumulativeExternalCashFlow.add(amount);
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    public BigDecimal signedPosition() { return signedPosition; }

    public BigDecimal cash() { return cash; }

    /** Realized trading PnL, <em>gross</em>: before fees, excluding external cash flows. */
    public BigDecimal grossRealizedPnl() { return grossRealizedPnl; }

    public BigDecimal cumulativeFees() { return cumulativeFees; }

    public BigDecimal cumulativeExternalCashFlow() { return cumulativeExternalCashFlow; }

    public BigDecimal multiplier() { return multiplier; }

    /** The reporting baseline, excluded from every PnL figure reported here. */
    public BigDecimal openingEquity() { return openingEquity; }

    /** Unrealized PnL already present at the baseline, likewise excluded. */
    public BigDecimal openingUnrealizedPnl() { return openingUnrealizedPnl; }

    /**
     * Per-unit VWAP of the open position, or empty when flat.
     *
     * <p>Empty rather than zero: a flat position has no average price, and returning zero invites a
     * caller to report "VWAP 0.00" in a statement. Note that the generated {@code Position} datum
     * publishes {@code 0} for a flat position, as the original did - see
     * {@code schemas/position.yaml} for that conversion and why it is safe (the datum also carries
     * {@code signedPosition}, so "flat" remains distinguishable from "acquired at zero").
     *
     * <p><b>Reporting only.</b> This is a divided value at {@link #CALCULATION_SCALE}, and no
     * accounting figure is derived from it - realized PnL comes from the signed notional and
     * unrealized PnL from {@link #signedNotional}. That separation is what keeps the reconciliation
     * identity exact: a rounded VWAP read back into a total would compound across reduces.
     */
    public Optional<BigDecimal> vwap() {
        if (signedPosition.signum() == 0) {
            return Optional.empty();
        }
        return Optional.of(signedNotional.divide(signedPosition,
                CALCULATION_SCALE, CALCULATION_ROUNDING));
    }

    /**
     * The signed cost notional of the open position: {@code signedPosition * vwap}, undivided.
     *
     * <p>The accounting source of truth, and what a checkpoint must carry instead of a VWAP. The
     * old {@code PosPnLTracker} stored exactly this in its {@code posVwap} field and divided only in
     * {@code getUnitVwap()} - a detail worth preserving rather than rediscovering.
     */
    public BigDecimal signedNotional() { return signedNotional; }

    /**
     * Unrealized PnL at a mark price: {@code signedPosition * (mark - vwap) * multiplier}.
     *
     * <p>Zero when flat, <em>without examining the mark</em>: valuing nothing needs no market price,
     * and requiring one would reject a legitimate flat-book valuation.
     */
    public BigDecimal unrealizedPnl(BigDecimal mark) {
        if (signedPosition.signum() == 0) {
            return BigDecimal.ZERO;
        }
        requireFinitePrice(mark, "mark");
        // ( signedPosition * mark - signedNotional ) * multiplier.
        //
        // Exact: no division, so no rounding enters an accounting figure. Algebraically identical
        // to signedPosition * (mark - vwap) * multiplier only when the VWAP divides exactly - which
        // is precisely the case that cannot be assumed. The old PosPnLTracker computed it this way
        // too (`_unRealizedPnl = mark * sgndPos` then `.minus(posVwap)`), and an earlier draft of
        // this class regressed from it.
        return signedPosition.multiply(mark).subtract(signedNotional).multiply(multiplier);
    }

    /**
     * Unrealized PnL accrued <em>during this reporting period</em>: {@link #unrealizedPnl(BigDecimal)}
     * less the {@link #openingUnrealizedPnl()} already present at the baseline.
     *
     * <p>This, not the raw figure, is the term in the reconciliation identity. For a tracker that
     * began flat or at its acquisition price the two are identical.
     */
    public BigDecimal unrealizedPnlThisPeriod(BigDecimal mark) {
        return unrealizedPnl(mark).subtract(openingUnrealizedPnl);
    }

    /** Mark-to-market value of the open position; zero when flat, whatever the mark. */
    public BigDecimal positionValue(BigDecimal mark) {
        if (signedPosition.signum() == 0) {
            return BigDecimal.ZERO;
        }
        requireFinitePrice(mark, "mark");
        return signedPosition.multiply(mark).multiply(multiplier);
    }

    /** {@code cash + positionValue(mark)}. */
    public BigDecimal equity(BigDecimal mark) {
        return cash.add(positionValue(mark));
    }

    /**
     * Net PnL since the reporting baseline:
     * {@code equity(mark) - openingEquity - cumulativeExternalCashFlow}.
     *
     * <p>One side of the reconciliation identity; the other is
     * {@code grossRealizedPnl + unrealizedPnlThisPeriod(mark) - cumulativeFees}.
     */
    public BigDecimal netPnl(BigDecimal mark) {
        return equity(mark).subtract(openingEquity).subtract(cumulativeExternalCashFlow);
    }

    /**
     * The reconciliation residual: zero whenever the accounting is self-consistent.
     *
     * <p>What the old implementation was most of the way towards and never finished. Note what it is
     * <em>not</em>: a comparison of two PnL figures for equality. Gross trading PnL, fees and
     * external cash flows are separate terms, and a fee on an unchanged-price round trip leaves
     * gross at zero while equity falls - so a direct equality would be false on correct books.
     *
     * <p>A non-zero result is a bug in this class, not a data problem. Callers should treat it as
     * fatal.
     */
    public BigDecimal reconciliationDiscrepancy(BigDecimal mark) {
        BigDecimal fromComponents = grossRealizedPnl
                .add(unrealizedPnlThisPeriod(mark))
                .subtract(cumulativeFees);
        return netPnl(mark).subtract(fromComponents);
    }

    /** Whether this execution id has already been applied (D4). */
    public boolean hasApplied(String executionId) {
        return appliedExecutionIds.contains(executionId);
    }

    // ------------------------------------------------------------------

    private TradeOutcome ignoredDuplicate() {
        return new TradeOutcome(TradeOutcome.Kind.IGNORED_DUPLICATE, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, null);
    }

    private static BigDecimal requirePositive(BigDecimal v, String what) {
        Objects.requireNonNull(v, what);
        if (v.signum() <= 0) {
            throw new IllegalArgumentException(what + " must be strictly positive: " + v);
        }
        return v;
    }

    /**
     * A price or mark may be zero or negative; it may not be absent.
     *
     * <p>Named for what it checks so the absence of a positivity constraint is visible as a
     * decision rather than looking like an omission.
     */
    private static BigDecimal requireFinitePrice(BigDecimal v, String what) {
        return Objects.requireNonNull(v, what);
    }

    /**
     * A position must fit {@code int64}, because that is what {@code Position.signedPosition}
     * declares.
     *
     * <p>Checked on the <em>resulting</em> position before any commit, and on opening and restored
     * positions. Validating only the incoming quantity is not enough: buying {@code Long.MAX_VALUE}
     * units and then one more passes every per-trade check and still leaves a position of
     * 9223372036854775808, which cannot be published.
     */
    private static BigDecimal requirePublishable(BigDecimal position, String what) {
        if (position.compareTo(LONG_MAX) > 0 || position.compareTo(LONG_MIN) < 0) {
            throw new IllegalArgumentException(
                    what + " does not fit int64: " + position.toPlainString()
                    + ". Position.signedPosition is int64, so this position could not be "
                    + "published (D1).");
        }
        return position;
    }

    /** Quantities must be whole, because the wire format declares them {@code int64}. */
    private static BigDecimal requireIntegral(BigDecimal v, String what) {
        if (v.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(
                    what + " must be a whole number: " + v + ". The generated Position datum "
                    + "declares signedPosition as int64, so a fractional position cannot be "
                    + "published; relaxing this requires relaxing the schema too (D1).");
        }
        return v;
    }
}
