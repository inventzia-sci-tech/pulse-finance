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

"""The datum-type provider (generated) for com.inventzia.pulse.finance."""

from inventzia.pulse.data.datum.provider import DatumTypeBinding
from inventzia.pulse.finance.schemas.position import Position


class FinanceDatumTypeProvider:
    """Datum types contributed by com.inventzia.pulse.finance, discovered via the SPI."""

    def provider_id(self) -> str:
        return "com.inventzia.pulse.finance"

    def spi_version(self) -> int:
        return 1

    def package_version(self) -> str:
        return "0.1.0.dev0"

    def bindings(self) -> "list[DatumTypeBinding]":
        return [
            DatumTypeBinding(Position.TYPE_ID, Position.TYPE_VERSION, Position),
        ]

    def manifest(self):
        return "pdm1|com.inventzia.pulse.finance|com.inventzia.pulse.finance.schemas.Position:1:aee6114c21acc79c24c599ed7147e9d976276c6e8200cbea9fbfe3a2e7f639d4"
