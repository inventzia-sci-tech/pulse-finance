/*
 * SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
 * Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
 *
 * This file is part of pulse-data.
 *
 * pulse-data is dual-licensed:
 *   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
 *   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
 *     Contact operations@inventzia.com.
 *
 * THIS FILE IS GENERATED. DO NOT EDIT MANUALLY.
 * Source: schemas/position.yaml
 * Regenerate: python schemas/schemas-generators/generate_java.py
 */

package com.inventzia.pulse.finance.schemas;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.inventzia.pulse.data.datum.Datum;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * A position in one instrument at a point in time: signed size and the VWAP at which it was acquired.
 * Ported from com.algotrading.cep.finance.data.position.Position (an A_MktDatum), published by the position manager whenever the position changes. The field set is the original's, with two deliberate departures recorded on the fields themselves: positionVwap becomes an exact decimal, and positionPoint becomes a string code.
 * The original inherited its routing key and time from A_Datum as datumKey/datumTime, and also carried publishedTime and receivedTime. Those two are dropped here, matching the CdfBar precedent in pulse-data: the engine's run recording is where publish and receive provenance belongs, not every domain datum.
 * CONVERSION CONTRACT with PositionTracker, the accounting kernel. The two representations differ deliberately and the mapping is total in both directions:
 * 
 *   signedPosition  <-  tracker.signedPosition(), which is a BigDecimal but is
 *                       constrained by the kernel to whole numbers precisely so that it
 *                       cannot reach a state this int64 field cannot carry. A fractional
 *                       position is rejected at the point of the trade, not discovered
 *                       here at publication time. Relaxing either side requires relaxing
 *                       both, as a wire version bump (D1).
 * 
 *   positionVwap    <-  tracker.vwap().orElse(ZERO). The kernel reports an absent VWAP for
 *                       a flat position because no average price exists; this datum
 *                       publishes 0, as the original did. The information is not lost:
 *                       signedPosition is carried alongside, so a consumer distinguishes
 *                       "flat" from "acquired at zero" by reading it. A reader that cares
 *                       must check signedPosition first.
 * 
 *                       The reverse direction is therefore conditional: a datum with
 *                       signedPosition 0 restores an absent VWAP, whatever positionVwap
 *                       holds. Any other value of positionVwap on a flat Position is
 *                       meaningless rather than wrong, and must be ignored.
 * 
 * Note that this datum is a published view, not a recovery record. Restoring a tracker needs its cumulative totals, reporting baseline and applied execution ids, none of which are here - see PositionSnapshot and PositionTracker.restore. Treating a Position as a checkpoint would silently reset PnL and re-admit already-applied fills.
 *
 * <p>Type ID: {@value #TYPE_ID}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Position(
    @JsonProperty(value = "instrumentId", required = true) String instrumentId,
    @JsonProperty(value = "positionTime", required = true) long positionTime,
    @JsonProperty(value = "signedPosition", required = true) long signedPosition,
    @JsonProperty(value = "positionVwap", required = true) BigDecimal positionVwap,
    @JsonProperty(value = "positionPoint", required = true) String positionPoint
) implements Datum {

    public Position {
        instrumentId = Objects.requireNonNull(instrumentId, "instrumentId");
        positionVwap = Objects.requireNonNull(positionVwap, "positionVwap");
        positionPoint = Objects.requireNonNull(positionPoint, "positionPoint");
    }

    public static final String TYPE_ID      = "com.inventzia.pulse.finance.schemas.Position";
    public static final int    TYPE_VERSION = 1;

    /** Record component carrying the routing key ({@code x-datum-key}). */
    public static final String DATUM_KEY_FIELD  = "instrumentId";
    /** Record component carrying the routing time ({@code x-datum-time}). */
    public static final String DATUM_TIME_FIELD = "positionTime";

    @Override public String getDatumKey()  { return instrumentId; }
    @Override public long   getDatumTime() { return positionTime; }
}
