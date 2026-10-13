# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
"""The installation acceptance criterion for the datum-ownership decision.

pulse-finance owns its finance schemas and contributes them through pulse-data's
DatumTypeProvider SPI. The criterion that makes that choice defensible:

    An APPLICATION DEVELOPER must be able to use these types through ordinary installation
    and startup, with no manual classpath assembly and no registry manipulation.

Note the boundary. Finance's own bootstrap may legitimately locate and contribute its
bundled JAR - that is the work the package exists to do. What must not happen is that work
landing on the application developer. `pulse-ext-example` is the counter-example: it makes
the *application* do it, by hardcoded path into its own Maven output -

    _EXT_JAR = Path(__file__).resolve().parents[1] / "java" / "target" / "...jar"
    jpype_host.start_jvm(extra_classpath=[_EXT_JAR])

- which proves discovery works given a hand-assembled classpath, and nothing about
installation.

**Release gate.** The real tests below are placeholders until provider packaging lands.
They SKIP during development, but under `PULSE_FINANCE_RELEASE=1` they FAIL instead, so a
release cannot be cut while the criterion is unproven. Skipping is a development
convenience, never a release outcome. See initialtransitioning.md, "Acceptance test for
this decision".
"""
from __future__ import annotations

import os
from pathlib import Path

import pytest

_REPO = Path(__file__).resolve().parents[1]

#: Set in CI's release job. Turns every unimplemented placeholder into a hard failure.
_RELEASE_MODE = os.environ.get("PULSE_FINANCE_RELEASE") == "1"


def _pending(what: str):
    """Skip during development; fail a release.

    This is the mechanism that keeps the criterion honest. A plain `pytest.skip` would let
    the release pass with the criterion unproven, which is exactly how an acceptance test
    becomes decoration.
    """
    message = (f"installation acceptance criterion not yet met: {what}. "
               f"See schemas/README.md and initialtransitioning.md.")
    if _RELEASE_MODE:
        pytest.fail(message)
    pytest.skip(message + "  (set PULSE_FINANCE_RELEASE=1 to make this a failure)")


def _schema_committed() -> bool:
    return any((_REPO / "schemas").glob("*.yaml"))


# ---------------------------------------------------------------------------
# Runs today, unconditionally. No module-level skip: this guard's whole value is that it
# works BEFORE packaging exists, catching the regression where someone "fixes" the
# installation story by pushing classpath assembly back onto applications.
# ---------------------------------------------------------------------------

#: Application-facing trees only. Finance's own library bootstrap under src/ is permitted
#: to assemble the classpath for its bundled JAR - that is the point of the package. The
#: requirement is that examples, which stand in for application code, never have to.
_APPLICATION_TREES = ("examples", "docs")

_FORBIDDEN_IN_APPLICATION_CODE = (
    ("extra_classpath", "an application must not assemble the JVM classpath"),
    ("DatumTypeRegistry.of", "an application must not build its own type registry"),
    ("register_provider", "provider registration is automatic via packaging metadata"),
)


def test_application_facing_code_never_assembles_a_classpath_or_touches_the_registry():
    offenders = []
    for tree in _APPLICATION_TREES:
        for path in (_REPO / tree).rglob("*.py") if (_REPO / tree).exists() else []:
            if "__pycache__" in path.parts or ".venv" in path.parts:
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            for needle, why in _FORBIDDEN_IN_APPLICATION_CODE:
                if needle in text:
                    offenders.append(f"{path.relative_to(_REPO)}: {needle} - {why}")
    assert not offenders, (
        "application-facing code must get the finance types from the installed package:\n  "
        + "\n  ".join(offenders))


def test_the_release_gate_itself_is_wired():
    """Guards the gate, not the feature.

    If `_pending` were ever softened back to an unconditional skip, the release job would go
    green with the criterion unproven and nobody would notice. This asserts the mechanism is
    still load-bearing.
    """
    assert _RELEASE_MODE is (os.environ.get("PULSE_FINANCE_RELEASE") == "1")
    import inspect
    body = inspect.getsource(_pending)
    assert "pytest.fail" in body, "_pending must fail (not skip) under PULSE_FINANCE_RELEASE=1"


# ---------------------------------------------------------------------------
# The criterion proper. Placeholders, release-gated.
# ---------------------------------------------------------------------------

@pytest.fixture(scope="module")
def clean_install(tmp_path_factory):
    """Build the distributions and install them into a venv OUTSIDE the checkout.

    Outside the checkout matters: a venv created inside the source tree can import the
    package from the working directory via a stray path entry, which would make this test
    pass while the wheel is in fact broken.
    """
    if not _schema_committed():
        _pending("no finance schema committed yet (D1-D5 open)")
    _pending("clean-environment install not implemented: build wheel+jar, create a venv "
             "outside the repo, pip install both")


def test_python_side_discovers_the_provider_without_manual_registration(clean_install):
    """pip install, and the registry already knows the finance types."""
    _pending("python discovery round trip")


def test_java_side_discovers_the_provider_from_the_installed_jar(clean_install):
    """The JAR reaches the classpath without the application naming a path.

    This is precisely what the ext example does not demonstrate. Whichever mechanism is
    chosen - JAR shipped as wheel package data and contributed by finance's own bootstrap,
    or a classpath entry-point group that jpype_host consults - the assertion is the same:
    the application passes no `extra_classpath`.
    """
    _pending("java discovery from an installed jar")


def test_round_trip_python_to_java_to_python_preserves_values(clean_install):
    """Publish a finance datum from Python, have a Java actor observe it, return a derived
    finance datum, and assert exact equality across both crossings.

    Exact, not approximate: a float round trip would satisfy a tolerance check and still be
    the defect this entire port exists to eliminate.
    """
    _pending("python -> java -> python finance-event round trip")


def test_no_maven_and_no_hand_located_jars_are_required(clean_install):
    """Fails if the happy path needs mvn, or any path containing `target/`."""
    _pending("no-maven startup")
