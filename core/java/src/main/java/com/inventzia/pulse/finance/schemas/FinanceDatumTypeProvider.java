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

package com.inventzia.pulse.finance.schemas;

import com.inventzia.pulse.data.datum.DatumTypeBinding;
import com.inventzia.pulse.data.datum.DatumTypeProvider;
import com.inventzia.pulse.finance.schemas.Position;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Datum types contributed by com.inventzia.pulse.finance (generated), discovered via the SPI.
 */
public final class FinanceDatumTypeProvider implements DatumTypeProvider {

    @Override public String providerId()     { return "com.inventzia.pulse.finance"; }
    @Override public int    spiVersion()      { return 1; }
    @Override public String packageVersion()  { return "0.1.0.dev0"; }

    @Override
    public Collection<DatumTypeBinding> bindings() {
        return List.of(
                new DatumTypeBinding(Position.TYPE_ID, Position.TYPE_VERSION, Position.class)
        );
    }

    @Override
    public Optional<String> manifest() {
        return Optional.of("pdm1|com.inventzia.pulse.finance|com.inventzia.pulse.finance.schemas.Position:1:aee6114c21acc79c24c599ed7147e9d976276c6e8200cbea9fbfe3a2e7f639d4");
    }
}
