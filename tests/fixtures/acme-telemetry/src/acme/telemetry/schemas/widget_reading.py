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
# Source: schemas/widget_reading.yaml
# Regenerate: python schemas/schemas-generators/generate_python.py

from __future__ import annotations
from decimal import Decimal
from pydantic import BaseModel, ConfigDict, Field
from typing import ClassVar, Optional


class WidgetReading(BaseModel):
    """
    A minimal third-party datum: one instrument-like key, one event time, one exact decimal. Small on purpose; it exists to be discovered alongside the finance types, not to model anything.
    """

    model_config = ConfigDict(extra="ignore", frozen=True)

    TYPE_ID:      ClassVar[str] = "com.acme.telemetry.schemas.WidgetReading"
    TYPE_VERSION: ClassVar[int] = 1
    DATUM_KEY_FIELD:  ClassVar[str] = "widget_id"
    DATUM_TIME_FIELD: ClassVar[str] = "reading_time"

    widget_id: str = Field(alias="widgetId")
    """Identifier of the widget reporting"""
    reading_time: int = Field(alias="readingTime")
    """Time of the reading, epoch milliseconds"""
    value: Decimal
    """The reading, as an exact decimal"""

    # -- Datum protocol ---------------------------------------------------

    @property
    def datum_key(self) -> str:
        return self.widget_id

    @property
    def datum_time(self) -> int:
        return self.reading_time
