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
 * Source: all schemas under schemas_yaml/
 * Regenerate: python schemas/schemas-generators/generate_java.py
 */

package com.acme.telemetry.schemas;

import com.inventzia.pulse.data.datum.DatumTypeBinding;
import com.inventzia.pulse.data.datum.DatumTypeProvider;
import com.acme.telemetry.schemas.WidgetReading;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Datum types contributed by com.acme.telemetry (generated), discovered via the SPI.
 */
public final class AcmeDatumTypeProvider implements DatumTypeProvider {

    @Override public String providerId()     { return "com.acme.telemetry"; }
    @Override public int    spiVersion()      { return 1; }
    @Override public String packageVersion()  { return "1.0.0"; }

    @Override
    public Collection<DatumTypeBinding> bindings() {
        return List.of(
                new DatumTypeBinding(WidgetReading.TYPE_ID, WidgetReading.TYPE_VERSION, WidgetReading.class)
        );
    }

    @Override
    public Optional<String> manifest() {
        return Optional.of("pdm1|com.acme.telemetry|com.acme.telemetry.schemas.WidgetReading:1:caa8c93429bef74a1122db524670b99edc2c25043e1f4aa582d800445a7e0fcb");
    }
}
