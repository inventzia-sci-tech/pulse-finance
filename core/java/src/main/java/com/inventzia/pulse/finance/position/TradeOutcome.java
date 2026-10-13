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
import java.util.Objects;

/**
 * What one trade did to a position: the case decomposition, made explicit.
 *
 * <p>The old implementation ({@code PosPnLTracker.processTrade}) got this analysis right -
 * including the case most implementations get wrong - but buried it in branches whose outcome was
 * never named. Naming it is what lets a test assert <em>which</em> case fired rather than only
 * checking the arithmetic afterwards, and what makes {@link Kind#REVERSE} impossible to lose in a
 * later refactor.
 */
public record TradeOutcome(Kind kind,
                           BigDecimal closedQuantity,
                           BigDecimal realizedPnl,
                           BigDecimal openedQuantity,
                           BigDecimal openedAtPrice) {

    public enum Kind {
        /** Flat before; a position now exists. */
        OPEN,
        /** Same direction: the position grew and its VWAP moved. No PnL realized. */
        ADD,
        /** Opposite direction, smaller than the position: PnL realized, VWAP unchanged. */
        REDUCE,
        /** Opposite direction, exactly the position: PnL realized, flat afterwards. */
        CLOSE,
        /**
         * Opposite direction, <em>larger</em> than the position: the old position closes and a new
         * one opens in the other direction.
         *
         * <p>This is the case that matters. Realizing PnL on the whole traded quantity rather than
         * on the closed portion is the classic error - a 150 sell against a 100 long at +5.00
         * realizes 500.00, not 750.00 - and the remainder must open at the trade price rather than
         * inherit a VWAP blended with the position just closed.
         */
        REVERSE,
        /**
         * Nothing happened: the execution id had already been applied (D4).
         *
         * <p>A distinct kind rather than a zero-valued {@link #REDUCE}, because "a redelivered fill
         * was correctly ignored" and "a reduce of size zero" are different events and a caller
         * logging or counting them must be able to tell them apart.
         */
        IGNORED_DUPLICATE
    }

    public TradeOutcome {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(closedQuantity, "closedQuantity");
        Objects.requireNonNull(realizedPnl, "realizedPnl");
        Objects.requireNonNull(openedQuantity, "openedQuantity");
    }

    /** Whether this trade crossed through zero. */
    public boolean isReversal() { return kind == Kind.REVERSE; }
}
