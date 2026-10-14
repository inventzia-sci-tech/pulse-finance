# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
"""Holder for the bundled finance jar, staged by ``stage-runtime-jar.sh``.

The jar is package data, not source. It is deliberately **thin**: finance classes and resources
only, with no shaded dependencies, because pulse-beacon's runtime jar already supplies pulse-data
and Jackson and a second copy would invite version shadowing.
"""
