#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
"""One version, two packaging systems. Keep them from drifting.

Maven and PEP 440 spell "pre-release" differently, so the two files cannot simply hold the
same string. The mapping is fixed here and checked in CI:

    development   Maven 0.1.0-SNAPSHOT   <->   Python 0.1.0.devN
    release       Maven 0.1.0            <->   Python 0.1.0

The base version (0.1.0) must always agree. What drifts in practice is one side being
bumped for a release and the other forgotten, which ships a wheel and a JAR claiming
different versions of the same contract - including in the provider's packageVersion().

Exit 0 if consistent, 1 otherwise.
"""
from __future__ import annotations

import re
import sys
import tomllib
import xml.etree.ElementTree as ET
from pathlib import Path

_REPO = Path(__file__).resolve().parents[1]
_POM = _REPO / "core/java/pom.xml"
_PYPROJECT = _REPO / "pyproject.toml"
_MAVEN_NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def maven_version() -> str:
    root = ET.parse(_POM).getroot()
    # Direct child only: a <version> inside <dependencies> is a different artifact's.
    for child in root:
        if child.tag == f"{{{_MAVEN_NS['m']}}}version":
            return (child.text or "").strip()
    raise SystemExit(f"no project <version> in {_POM}")


def python_version() -> str:
    return tomllib.loads(_PYPROJECT.read_text(encoding="utf-8"))["project"]["version"]


def main() -> int:
    mv, pv = maven_version(), python_version()
    problems = []

    maven_snapshot = mv.endswith("-SNAPSHOT")
    maven_base = mv[: -len("-SNAPSHOT")] if maven_snapshot else mv

    m = re.fullmatch(r"(\d+\.\d+\.\d+)(?:\.dev(\d+))?", pv)
    if not m:
        problems.append(f"python version {pv!r} is not X.Y.Z or X.Y.Z.devN")
        python_base, python_dev = pv, None
    else:
        python_base, python_dev = m.group(1), m.group(2)

    if maven_base != python_base:
        problems.append(f"base version mismatch: maven {maven_base!r} vs python {python_base!r}")

    if maven_snapshot and python_dev is None:
        problems.append(f"maven is a SNAPSHOT ({mv}) but python {pv!r} is a final release; "
                        f"use {python_base}.dev0")
    if not maven_snapshot and python_dev is not None:
        problems.append(f"maven is a release ({mv}) but python {pv!r} is a pre-release; "
                        f"drop the .dev suffix")

    print(f"maven  core/java/pom.xml : {mv}")
    print(f"python pyproject.toml    : {pv}")
    if problems:
        print("\n".join("❌  " + p for p in problems), file=sys.stderr)
        return 1
    state = "development (pre-release)" if maven_snapshot else "release"
    print(f"✅  consistent — {state}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
