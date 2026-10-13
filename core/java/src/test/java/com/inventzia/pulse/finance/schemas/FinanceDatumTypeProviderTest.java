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
package com.inventzia.pulse.finance.schemas;

import com.inventzia.pulse.data.datum.DatumTypeRegistry;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The finance provider must be found by the registry without anyone registering it.
 *
 * <p>This is the Java half of the datum-ownership decision made good: pulse-finance owns its
 * schemas, and a consuming application gets them from the classpath alone. Nothing here calls
 * {@code DatumTypeRegistry.of(...)} - discovery goes through {@code ServiceLoader} reading
 * {@code META-INF/services}, exactly as it will from the published JAR.
 *
 * <p>Previously untested, which left the central claim of that decision unverified: the schema
 * generated and the provider compiled, but nothing proved the registry would accept them. The
 * namespace rule is the part most likely to break silently - a {@code TYPE_ID} outside the
 * provider's own namespace is rejected at startup, so getting it wrong fails every consumer.
 */
class FinanceDatumTypeProviderTest {

    @Test
    void theRegistryDiscoversFinanceTypesFromTheClasspathAlone() {
        DatumTypeRegistry registry = DatumTypeRegistry.defaultRegistry();

        assertThat(registry.classFor(Position.TYPE_ID))
                .as("Position must be resolvable by its TYPE_ID with no manual registration")
                .isEqualTo(Position.class);

        assertThat(registry.providers())
                .extracting(DatumTypeRegistry.ProviderInfo::providerId)
                .as("the finance provider must appear beside core's")
                .contains("com.inventzia.pulse.finance", "com.inventzia.pulse.data");
    }

    @Test
    void aFinanceDatumReportsItsOwnTypeIdThroughTheRegistry() {
        // The reverse lookup: this is what stops a producer emitting an envelope whose typeId
        // no runtime can decode.
        Position p = new Position("CL", 1_283_630_000_000L, 100L,
                new BigDecimal("68.47"), "ID_TRD");

        assertThat(DatumTypeRegistry.defaultRegistry().typeIdOf(p))
                .isEqualTo("com.inventzia.pulse.finance.schemas.Position");
    }

    @Test
    void everyFinanceTypeIdSitsUnderTheProvidersNamespace() {
        // DatumTypeRegistry enforces this and fails the whole registry if violated, so asserting
        // it here turns a startup failure for every consumer into one failing test here.
        FinanceDatumTypeProvider provider = new FinanceDatumTypeProvider();
        String prefix = provider.providerId() + ".";

        assertThat(provider.bindings()).isNotEmpty();
        assertThat(provider.bindings())
                .allSatisfy(b -> assertThat(b.typeId()).startsWith(prefix));
        assertThat(provider.spiVersion()).isEqualTo(DatumTypeRegistry.SPI_VERSION);
    }

    @Test
    void theProviderReportsFinancesOwnVersionNotPulseDatas() {
        // The generator falls back to reading pulse-data's pyproject.toml when
        // --package-version is omitted, which would make finance silently claim pulse-data's
        // version. regen.sh passes it explicitly; this is what proves it took effect.
        assertThat(new FinanceDatumTypeProvider().packageVersion())
                .as("must match pulse-finance's own pyproject.toml version")
                .isEqualTo("0.1.0.dev0");
    }

    @Test
    void theRoutingFieldsAreTheOnesTheSchemaDeclared() {
        assertThat(Position.DATUM_KEY_FIELD).isEqualTo("instrumentId");
        assertThat(Position.DATUM_TIME_FIELD).isEqualTo("positionTime");

        Position p = new Position("CL", 42L, -5L, new BigDecimal("68.47"), "EOD");
        assertThat(p.getDatumKey()).isEqualTo("CL");
        assertThat(p.getDatumTime()).isEqualTo(42L);
        assertThat(p.positionVwap())
                .as("decimal, not double: the whole point of the port")
                .isEqualByComparingTo(new BigDecimal("68.47"));
    }
}
