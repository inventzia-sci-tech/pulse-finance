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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything needed to restore a {@link PositionTracker} to an exact earlier state.
 *
 * <p>Distinct from "start a new reporting period from a known position", which needs far less and
 * means something different. Restoration must reproduce the tracker <em>including</em> its
 * cumulative totals, its reporting baseline and the execution ids it has already applied - a
 * restored tracker that has forgotten its applied ids will double-count the first replayed fill,
 * which is the failure D4 exists to prevent.
 *
 * @param cash                       cash balance
 * @param signedPosition             signed position, positive long / negative short
 * @param signedNotional             signed cost notional of the open position
 *                                   ({@code signedPosition * vwap}); carried rather than the
 *                                   per-unit VWAP so restoration cannot lose precision to a
 *                                   division that does not terminate
 * @param grossRealizedPnl           cumulative gross realized trading PnL
 * @param cumulativeFees             cumulative fees
 * @param cumulativeExternalCashFlow cumulative deposits less withdrawals
 * @param openingEquity              the reporting baseline this tracker measures PnL against
 * @param openingUnrealizedPnl       unrealized PnL already present at that baseline, netted out of
 *                                   every PnL figure so a pre-existing mark-to-market gain is not
 *                                   reported as this period's profit
 * @param multiplier                 contract multiplier in force
 * @param appliedExecutionIds        execution ids already applied. Held as an unordered
 *                                   {@link Set}; use {@link #canonicalExecutionIds()} when
 *                                   serialising, because a checkpoint must be byte-stable and
 *                                   set iteration order is not.
 */
public record PositionSnapshot(BigDecimal cash,
                               BigDecimal signedPosition,
                               BigDecimal signedNotional,
                               BigDecimal grossRealizedPnl,
                               BigDecimal cumulativeFees,
                               BigDecimal cumulativeExternalCashFlow,
                               BigDecimal openingEquity,
                               BigDecimal openingUnrealizedPnl,
                               BigDecimal multiplier,
                               Set<String> appliedExecutionIds) {

    public PositionSnapshot {
        Objects.requireNonNull(cash, "cash");
        Objects.requireNonNull(signedPosition, "signedPosition");
        Objects.requireNonNull(signedNotional, "signedNotional");
        Objects.requireNonNull(grossRealizedPnl, "grossRealizedPnl");
        Objects.requireNonNull(cumulativeFees, "cumulativeFees");
        Objects.requireNonNull(cumulativeExternalCashFlow, "cumulativeExternalCashFlow");
        Objects.requireNonNull(openingEquity, "openingEquity");
        Objects.requireNonNull(openingUnrealizedPnl, "openingUnrealizedPnl");
        Objects.requireNonNull(multiplier, "multiplier");
        if (multiplier.signum() <= 0) {
            throw new IllegalArgumentException("multiplier must be strictly positive: " + multiplier);
        }
        if (signedPosition.signum() == 0 && signedNotional.signum() != 0) {
            throw new IllegalArgumentException(
                    "a flat position cannot carry a cost notional: " + signedNotional);
        }
        // Defensive copy. Note that Set.copyOf gives NO ordering guarantee - an earlier version
        // wrapped a LinkedHashSet and claimed insertion order was preserved, which was simply
        // false: Set.copyOf discards it. Byte-stability for serialisation comes from
        // canonicalExecutionIds() instead, which imposes an order rather than hoping for one.
        appliedExecutionIds = Set.copyOf(
                Objects.requireNonNull(appliedExecutionIds, "appliedExecutionIds"));
    }

    /**
     * The applied execution ids in a deterministic order, for serialising a checkpoint.
     *
     * <p>Sorted rather than insertion-ordered on purpose: two trackers that applied the same ids in
     * a different order are the same accounting state, and their checkpoints should be identical
     * bytes. Insertion order would make them differ and defeat any checkpoint comparison.
     */
    public List<String> canonicalExecutionIds() {
        return List.copyOf(new TreeSet<>(appliedExecutionIds));
    }
}
