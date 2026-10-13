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

/** Which way a trade goes. */
public enum Side {
    BUY(1),
    SELL(-1);

    private final int sign;

    Side(int sign) { this.sign = sign; }

    /** {@code +1} for a buy, {@code -1} for a sell: the signed direction of the quantity. */
    public int sign() { return sign; }
}
