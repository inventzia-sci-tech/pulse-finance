# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-finance.
#
# pulse-finance is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
"""Refuse to build a wheel whose bundled jar is missing, stale, or incomplete.

pulse-beacon's equivalent checks only that the jar exists. That is not enough, and the gap is not
theoretical: a jar built from older sources passes an existence check, ships, and then contributes
the wrong types. Three checks instead:

1. **Present.** The jar is staged as package data.
2. **Current.** A digest of the build inputs, recorded beside the jar by ``stage-runtime-jar.sh``,
   matches the inputs as they are now. Timestamps are not used: a checkout, a copy, or WSL and
   Windows disagreeing about mtimes can all make a stale jar look fresh.
3. **Complete.** The jar actually contains the provider class and its ``META-INF/services``
   registration. A jar from a broken Maven profile can be present and current and still contribute
   nothing, which would surface much later as a type-universe mismatch rather than a packaging bug.

Set ``PULSE_FINANCE_ALLOW_INCOMPLETE_JAR=1`` for an intentionally jar-less source build.
"""
from __future__ import annotations

import hashlib
import os
import zipfile
from pathlib import Path

from setuptools import build_meta as _setuptools

_HERE = Path(__file__).resolve().parent
_RUNTIME_DIR = _HERE / "src" / "inventzia" / "pulse" / "finance" / "_runtime"
_JAR = _RUNTIME_DIR / "pulse-finance-core.jar"
_DIGEST = _RUNTIME_DIR / "pulse-finance-core.jar.inputs"

_SERVICES_ENTRY = "META-INF/services/com.inventzia.pulse.data.datum.DatumTypeProvider"
_PROVIDER_CLASS = "com/inventzia/pulse/finance/schemas/FinanceDatumTypeProvider.class"

_ABORT = "pulse-finance wheel build aborted: "
_ESCAPE = ("\nStage it with ./stage-runtime-jar.sh, or set "
           "PULSE_FINANCE_ALLOW_INCOMPLETE_JAR=1 for a deliberately jar-less source build.")


#: Files whose change must invalidate the staged jar: the schemas, the generated bindings, the
#: hand-written Java, the services registration and the POM.
_INPUT_ROOTS = (
    _HERE / "schemas",
    _HERE / "core" / "java" / "src" / "main" / "java",
    _HERE / "core" / "java" / "src" / "main" / "resources",
)
_INPUT_FILES = (_HERE / "core" / "java" / "pom.xml",)


def inputs_digest() -> str:
    """SHA-256 over the build inputs, order-independent and content-based.

    Public because ``stage-runtime-jar.sh`` calls it: one implementation, so the recorded digest
    and the verified digest cannot drift. An earlier version had the shell own the logic and the
    backend ``source`` the script to reuse it, which silently **rebuilt and re-staged the jar** as
    a side effect of verifying it, and so could mask the staleness it was meant to detect. A
    verification step must not build anything.

    Content only; never modification times. A checkout, a copy, or WSL and Windows disagreeing
    about mtimes can all make a stale jar look current.
    """
    paths: list[Path] = [f for f in _INPUT_FILES if f.is_file()]
    for root in _INPUT_ROOTS:
        if root.is_dir():
            paths.extend(f for f in root.rglob("*") if f.is_file())

    outer = hashlib.sha256()
    for path in sorted(paths, key=lambda q: q.relative_to(_HERE).as_posix()):
        outer.update(path.relative_to(_HERE).as_posix().encode("utf-8"))
        outer.update(b"\0")
        outer.update(hashlib.sha256(path.read_bytes()).hexdigest().encode("ascii"))
        outer.update(b"\n")
    return outer.hexdigest()


def _require_complete_jar() -> None:
    if os.environ.get("PULSE_FINANCE_ALLOW_INCOMPLETE_JAR"):
        return

    if not _JAR.is_file():
        raise SystemExit(
            f"{_ABORT}the bundled jar is missing at\n    {_JAR}\n"
            "A wheel without it installs a package whose Python types are registered while its "
            "Java types cannot be loaded." + _ESCAPE)

    # --- current -----------------------------------------------------------
    if not _DIGEST.is_file():
        raise SystemExit(
            f"{_ABORT}the jar is staged but its input digest is missing at\n    {_DIGEST}\n"
            "Without it the jar cannot be shown to match the sources in this tree." + _ESCAPE)
    recorded = _DIGEST.read_text(encoding="utf-8").strip()
    actual = inputs_digest()
    if actual != recorded:
        raise SystemExit(
            f"{_ABORT}the bundled jar is STALE.\n"
            f"    recorded inputs: {recorded}\n"
            f"    current inputs : {actual}\n"
            "The sources have changed since the jar was staged, so the wheel would ship Java "
            "types that do not match its Python ones." + _ESCAPE)

    # --- complete ----------------------------------------------------------
    try:
        with zipfile.ZipFile(_JAR) as z:
            names = set(z.namelist())
    except zipfile.BadZipFile as exc:
        raise SystemExit(f"{_ABORT}the bundled jar is not a readable archive: {exc}" + _ESCAPE)

    for required in (_PROVIDER_CLASS, _SERVICES_ENTRY):
        if required not in names:
            raise SystemExit(
                f"{_ABORT}the bundled jar is incomplete: it does not contain\n    {required}\n"
                "A jar can be present and current and still contribute no types, which surfaces "
                "later as a cross-language type-universe mismatch rather than a packaging error."
                + _ESCAPE)


def build_wheel(wheel_directory, config_settings=None, metadata_directory=None):
    _require_complete_jar()
    return _setuptools.build_wheel(wheel_directory, config_settings, metadata_directory)


def build_sdist(sdist_directory, config_settings=None):
    return _setuptools.build_sdist(sdist_directory, config_settings)


def build_editable(wheel_directory, config_settings=None, metadata_directory=None):
    # An editable install is a development mode; the contributor falls back to Maven output there.
    return _setuptools.build_editable(wheel_directory, config_settings, metadata_directory)


# Re-export the hooks setuptools provides unchanged.
get_requires_for_build_wheel = _setuptools.get_requires_for_build_wheel
get_requires_for_build_sdist = _setuptools.get_requires_for_build_sdist
get_requires_for_build_editable = _setuptools.get_requires_for_build_editable
prepare_metadata_for_build_wheel = _setuptools.prepare_metadata_for_build_wheel
prepare_metadata_for_build_editable = _setuptools.prepare_metadata_for_build_editable
