# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-data.
#
# pulse-data is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
#
# THIS FILE IS GENERATED. DO NOT EDIT MANUALLY.
# Source: all schemas under schemas_yaml/
# Regenerate: python schemas/schemas-generators/generate_python.py

"""The datum-type provider (generated) for com.acme.telemetry."""

from inventzia.pulse.data.datum.provider import DatumTypeBinding
from acme.telemetry.schemas.widget_reading import WidgetReading


class AcmeDatumTypeProvider:
    """Datum types contributed by com.acme.telemetry, discovered via the SPI."""

    def provider_id(self) -> str:
        return "com.acme.telemetry"

    def spi_version(self) -> int:
        return 1

    def package_version(self) -> str:
        return "1.0.0"

    def bindings(self) -> "list[DatumTypeBinding]":
        return [
            DatumTypeBinding(WidgetReading.TYPE_ID, WidgetReading.TYPE_VERSION, WidgetReading),
        ]

    def manifest(self):
        return "pdm1|com.acme.telemetry|com.acme.telemetry.schemas.WidgetReading:1:caa8c93429bef74a1122db524670b99edc2c25043e1f4aa582d800445a7e0fcb"
