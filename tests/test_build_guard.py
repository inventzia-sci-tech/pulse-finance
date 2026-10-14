# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
"""The wheel build must refuse a jar that is missing, stale or incomplete.

Referenced by ``test_installation.py`` as the first half of the missing-jar case: that suite
covers an installed artifact that is damaged, this one covers rejection during construction.

Fast and JVM-free: it drives the build backend directly against a copy of the staged jar, so each
failure mode is exercised rather than argued about. These run from the source tree on purpose,
since what is under test is the *build*, not the installed result.
"""
from __future__ import annotations

import importlib
import shutil
import subprocess
import zipfile
from pathlib import Path

import pytest

_REPO = Path(__file__).resolve().parents[1]
_RUNTIME = _REPO / "src" / "inventzia" / "pulse" / "finance" / "_runtime"
_JAR = _RUNTIME / "pulse-finance-core.jar"
_DIGEST = _RUNTIME / "pulse-finance-core.jar.inputs"


@pytest.fixture
def backend():
    """The build backend, reloaded so module-level paths are current."""
    import sys
    if str(_REPO) not in sys.path:
        sys.path.insert(0, str(_REPO))
    import _build_backend
    return importlib.reload(_build_backend)


@pytest.fixture(scope="session")
def staged_artifacts():
    """Make sure something is staged, staging it if needed.

    Self-sufficient on purpose. Skipping when nothing is staged left these tests reporting nine
    skips and exit code zero in the release job, which proves nothing; and leaving the staging to
    the job meant the guards and the thing they guard could drift apart.
    """
    from conftest import unavailable

    if _JAR.is_file() and _DIGEST.is_file():
        return
    if shutil.which("mvn") is None or shutil.which("java") is None:
        unavailable("mvn and a JDK are required to stage the jar these guards check")
    out = subprocess.run(["bash", str(_REPO / "stage-runtime-jar.sh")],
                         capture_output=True, text=True, timeout=900, cwd=str(_REPO))
    if out.returncode != 0 or not _JAR.is_file():
        unavailable(f"could not stage the jar: {out.stderr[-300:]}")


@pytest.fixture
def staged(tmp_path, staged_artifacts):
    """Preserve and restore the staged artifacts around each test."""
    jar_backup = tmp_path / "jar"; shutil.copy(_JAR, jar_backup)
    digest_backup = tmp_path / "digest"; shutil.copy(_DIGEST, digest_backup)
    yield
    shutil.copy(jar_backup, _JAR)
    shutil.copy(digest_backup, _DIGEST)


def _rewrite_jar(transform) -> None:
    """Rebuild the staged jar, passing every entry through *transform*."""
    with zipfile.ZipFile(_JAR) as z:
        entries = [(n, z.read(n)) for n in z.namelist()]
    with zipfile.ZipFile(_JAR, "w") as z:
        for name, data in entries:
            kept = transform(name, data)
            if kept is not None:
                z.writestr(name, kept)


def test_a_healthy_staged_jar_is_accepted(backend, staged):
    backend._require_complete_jar()


def test_a_missing_jar_is_refused(backend, staged):
    _JAR.unlink()
    with pytest.raises(SystemExit) as e:
        backend._require_complete_jar()
    assert "missing" in str(e.value)


def test_a_missing_input_digest_is_refused(backend, staged):
    # Without it the jar cannot be shown to match the sources, so it must not be trusted.
    _DIGEST.unlink()
    with pytest.raises(SystemExit) as e:
        backend._require_complete_jar()
    assert "input digest is missing" in str(e.value)


def test_a_stale_jar_is_refused(backend, staged):
    """The case pulse-beacon's existence-only check misses."""
    _DIGEST.write_text("0" * 64, encoding="utf-8")
    with pytest.raises(SystemExit) as e:
        backend._require_complete_jar()
    message = str(e.value)
    assert "STALE" in message
    assert "recorded inputs" in message and "current inputs" in message


def test_a_jar_without_its_provider_class_is_refused(backend, staged):
    _rewrite_jar(lambda n, d: None if n.endswith("FinanceDatumTypeProvider.class") else d)
    with pytest.raises(SystemExit) as e:
        backend._require_complete_jar()
    assert "incomplete" in str(e.value)


def test_a_jar_without_its_service_registration_is_refused(backend, staged):
    _rewrite_jar(lambda n, d: None if ("services" in n and "DatumTypeProvider" in n) else d)
    with pytest.raises(SystemExit) as e:
        backend._require_complete_jar()
    assert "incomplete" in str(e.value)


def test_an_empty_service_registration_is_refused(backend, staged):
    """Presence is not enough, and this one actually got through.

    An empty services file satisfies a name check and registers nothing: ServiceLoader then finds
    no provider, Java comes up without the finance types, and the failure surfaces as a
    type-universe mismatch with no mention of packaging. A controlled probe confirmed the earlier
    guard accepted exactly this.
    """
    _rewrite_jar(lambda n, d: b"" if ("services" in n and "DatumTypeProvider" in n) else d)
    with pytest.raises(SystemExit) as e:
        backend._require_complete_jar()
    message = str(e.value)
    assert "does not name the provider" in message
    assert "(nothing)" in message


def test_a_service_registration_naming_the_wrong_class_is_refused(backend, staged):
    _rewrite_jar(lambda n, d: b"com.example.Wrong\n"
                 if ("services" in n and "DatumTypeProvider" in n) else d)
    with pytest.raises(SystemExit) as e:
        backend._require_complete_jar()
    assert "does not name the provider" in str(e.value)
    assert "com.example.Wrong" in str(e.value)


def test_comments_and_blank_lines_in_the_registration_are_tolerated(backend, staged):
    """The services format allows them, so the guard must not reject a valid file."""
    valid = (b"# the finance provider\n"
             b"\n"
             b"com.inventzia.pulse.finance.schemas.FinanceDatumTypeProvider  # trailing\n")
    _rewrite_jar(lambda n, d: valid if ("services" in n and "DatumTypeProvider" in n) else d)
    backend._require_complete_jar()
