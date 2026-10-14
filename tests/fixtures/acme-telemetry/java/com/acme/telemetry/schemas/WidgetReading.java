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
 * Source: schemas/widget_reading.yaml
 * Regenerate: python schemas/schemas-generators/generate_java.py
 */

package com.acme.telemetry.schemas;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.inventzia.pulse.data.datum.Datum;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * A minimal third-party datum: one instrument-like key, one event time, one exact decimal. Small on purpose; it exists to be discovered alongside the finance types, not to model anything.
 *
 * <p>Type ID: {@value #TYPE_ID}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WidgetReading(
    @JsonProperty(value = "widgetId", required = true) String widgetId,
    @JsonProperty(value = "readingTime", required = true) long readingTime,
    @JsonProperty(value = "value", required = true) BigDecimal value
) implements Datum {

    public WidgetReading {
        widgetId = Objects.requireNonNull(widgetId, "widgetId");
        value = Objects.requireNonNull(value, "value");
    }

    public static final String TYPE_ID      = "com.acme.telemetry.schemas.WidgetReading";
    public static final int    TYPE_VERSION = 1;

    /** Record component carrying the routing key ({@code x-datum-key}). */
    public static final String DATUM_KEY_FIELD  = "widgetId";
    /** Record component carrying the routing time ({@code x-datum-time}). */
    public static final String DATUM_TIME_FIELD = "readingTime";

    @Override public String getDatumKey()  { return widgetId; }
    @Override public long   getDatumTime() { return readingTime; }
}
