# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
"""Build the distributions and install them into a clean environment outside the checkout.

Expensive and session-scoped on purpose: the acceptance criterion is about *installed artifacts*,
so the fixture builds real wheels and installs them offline rather than importing from the source
tree. Editable installs and the Maven-output fallback are exactly what it must not use, because
either would let a packaging failure pass.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

_REPO = Path(__file__).resolve().parents[1]
_SIBLINGS = _REPO.parent
_ACME = _REPO / "tests" / "fixtures" / "acme-telemetry"

#: Set in CI's release job: a missing prerequisite becomes a failure instead of a skip.
_RELEASE_MODE = os.environ.get("PULSE_FINANCE_RELEASE") == "1"


def _unavailable(reason: str):
    if _RELEASE_MODE:
        pytest.fail(f"installation acceptance prerequisite missing: {reason}", pytrace=False)
    pytest.skip(f"{reason} (set PULSE_FINANCE_RELEASE=1 to make this a failure)")


def _run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=900, **kw)


@pytest.fixture(scope="session")
def clean_install(tmp_path_factory) -> dict:
    """Wheels built, then installed into a venv **outside** the checkout.

    Outside matters: a venv inside the source tree can import the package from the working
    directory through a stray path entry, which would make these tests pass while the wheel is
    broken.

    :returns: ``{"python": <venv python>, "wheels": <dir>, "env": <venv root>}``
    """
    for tool in ("mvn", "java"):
        if shutil.which(tool) is None:
            _unavailable(f"{tool} is required to build the jars (JAVA_HOME from the `pulse` env)")
    for sibling in ("pulse-data", "pulse-beacon"):
        if not (_SIBLINGS / sibling).is_dir():
            _unavailable(f"sibling checkout {sibling} not found beside pulse-finance")

    base = tmp_path_factory.mktemp("acceptance")
    assert _REPO not in base.parents and base != _REPO, "the venv must live outside the checkout"
    wheels = base / "wheels"; wheels.mkdir()

    # 1. stage finance's jar, then build every wheel from source.
    staged = _run(["bash", str(_REPO / "stage-runtime-jar.sh")], cwd=str(_REPO))
    if staged.returncode != 0:
        _unavailable(f"could not stage the finance jar: {staged.stderr[-400:]}")
    built = _run(["bash", str(_ACME / "build.sh")], cwd=str(_ACME))
    if built.returncode != 0:
        _unavailable(f"could not build the acme-telemetry fixture: {built.stderr[-400:]}")

    for project in (_SIBLINGS / "pulse-data", _SIBLINGS / "pulse-beacon", _REPO):
        out = _run([sys.executable, "-m", "build", "--wheel", "--no-isolation",
                    "--outdir", str(wheels), str(project)])
        if out.returncode != 0:
            _unavailable(f"wheel build failed for {project.name}: {out.stdout[-500:]}")
    for whl in (_ACME / "dist").glob("*.whl"):
        shutil.copy(whl, wheels)

    # 2. a venv with nothing in it, outside the checkout.
    env_dir = base / "env"
    out = _run([sys.executable, "-m", "venv", str(env_dir)])
    if out.returncode != 0:
        _unavailable(f"could not create a venv: {out.stderr[-300:]}")
    py = env_dir / ("Scripts" if os.name == "nt" else "bin") / "python"

    # 3. resolve the dependency closure once, then install strictly offline so nothing can
    #    silently come from the index or the source tree.
    out = _run([str(py), "-m", "pip", "download", "--dest", str(wheels),
                "--find-links", str(wheels), "pulse-finance[execution]", "acme-telemetry", "jpype1"])
    if out.returncode != 0:
        _unavailable(f"could not resolve the dependency closure: {out.stdout[-500:]}")
    out = _run([str(py), "-m", "pip", "install", "--no-index", "--find-links", str(wheels),
                "pulse-finance[execution]", "acme-telemetry", "jpype1"])
    if out.returncode != 0:
        _unavailable(f"offline install failed: {out.stdout[-600:]}")

    return {"python": str(py), "wheels": str(wheels), "env": str(env_dir)}


def run_in_clean_env(clean_install: dict, script: str, env_extra: dict | None = None):
    """Run *script* in its own process in the clean environment, from outside the checkout.

    Its own process because a JVM cannot be started twice in one interpreter, so sharing one
    would let the scenarios interfere. Outside the checkout so no relative path can reach the
    source tree.
    """
    env = {k: v for k, v in os.environ.items() if k not in ("PYTHONPATH",)}
    env.update(env_extra or {})
    return subprocess.run([clean_install["python"], "-c", script],
                          capture_output=True, text=True, timeout=600,
                          cwd=clean_install["env"], env=env)
