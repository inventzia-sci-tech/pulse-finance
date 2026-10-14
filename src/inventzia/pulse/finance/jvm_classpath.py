# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-finance.
#
# pulse-finance is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
"""This package's contribution to the JVM classpath.

Declared through pulse-beacon's ``inventzia.pulse.jvm_classpath`` entry-point group, so Beacon
finds this jar itself and an application calls ``start_jvm()`` with no arguments and no paths.

The entry point references :func:`jars`, a zero-argument callable, rather than a filename, because
locating a jar inside an installed wheel requires ``importlib.resources`` to execute. Beacon calls
it *before* the JVM exists, and again when checking a JVM that is already running, so it must be
safe to call repeatedly, must not import JPype or touch the datum registry, and must stay cheap.
"""
from __future__ import annotations

import os
from pathlib import Path

_JAR_NAME = "pulse-finance-core.jar"
_RUNTIME_PACKAGE = "inventzia.pulse.finance._runtime"


def jars() -> list[str]:
    """The jars this package contributes.

    :raises FileNotFoundError: if none can be found. Beacon re-raises it as a
        ``ClasspathContributionError`` naming this distribution.

    **Why this raises rather than returning an empty list.** The finance datum types stay
    registered on the Python side either way, so an empty result would bring Java up without them
    and defer the failure to a type-universe mismatch that says nothing about a missing jar.
    Raising does not affect Python-only use: this is called when Beacon starts Java, not at import.
    """
    bundled, built = _bundled(), _built_in_checkout()
    for candidate in (bundled, built):
        if candidate is not None and candidate.is_file():
            return [str(candidate)]

    raise FileNotFoundError(
        f"{_JAR_NAME} was not found, so this package's Java types cannot be loaded while its "
        f"Python types are registered.\n"
        f"  packaged (installed wheel): "
        f"{bundled if bundled else _RUNTIME_PACKAGE + ' not present'}\n"
        f"  development build        : {built}\n"
        f"Stage it with: ./stage-runtime-jar.sh\n"
        f"Or set PULSE_FINANCE_JAR to an existing jar."
    )


def _bundled() -> Path | None:
    """The jar shipped as package data in an installed wheel."""
    try:
        from importlib.resources import files
        return Path(str(files(_RUNTIME_PACKAGE).joinpath(_JAR_NAME)))
    except (ModuleNotFoundError, FileNotFoundError, TypeError):
        return None


def _built_in_checkout() -> Path | None:
    """Maven's output in a source checkout: the editable-install and dev case.

    Never reached by an installed wheel, which finds the bundled copy first. The acceptance tests
    deliberately run outside any checkout so this path cannot mask a packaging failure.
    """
    override = os.environ.get("PULSE_FINANCE_JAR")
    if override:
        return Path(override)
    # .../pulse-finance/src/inventzia/pulse/finance/jvm_classpath.py
    # parents[4] is the repository root.
    root = Path(__file__).resolve().parents[4]
    target = root / "core" / "java" / "target"
    if not target.is_dir():
        return None
    found = sorted(target.glob("pulse-finance-core-*.jar"))
    return found[0] if found else None
