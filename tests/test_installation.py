# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
"""The installation acceptance criterion for the datum-ownership decision.

pulse-finance owns its finance schemas and contributes them through pulse-data's
DatumTypeProvider SPI. The criterion that makes that choice defensible:

    An APPLICATION DEVELOPER must be able to use these types through ordinary installation
    and startup, with no manual classpath assembly and no registry manipulation.

Note the boundary. Finance declares its own JAR and pulse-beacon discovers it; what must
not happen is that work landing on the application developer. pulse-beacon now provides the
mechanism: a package names its JAR under the `inventzia.pulse.jvm_classpath` entry-point
group, and `start_jvm()` collects contributions itself. `pulse-ext-example` demonstrates it
end to end, so an application passes no paths at all.

What that example does NOT establish, and what these tests are for, is the *installed*
experience. It is installed editable from a source checkout and its contribution locates a
Maven-built JAR in `java/target/`; its packaging does not bundle one. So it is evidence of
discovery and composition, not of a distributable artifact. These tests must therefore run
against **installed wheels** in a clean environment outside the checkout, with no editable
install and no Maven-output fallback, and must assert provider identities and exact decimal
values rather than merely that startup succeeded.

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
# The criterion proper. Each JVM scenario runs in its own process, because a JVM cannot be
# started twice in one interpreter and sharing one would let the scenarios interfere.
# ---------------------------------------------------------------------------

from conftest import run_in_clean_env            # noqa: E402  (fixture helper)


def test_the_installed_environment_is_built_from_wheels_only(clean_install):
    """Nothing editable, nothing resolved from the source tree.

    Checked first: every assertion below is worthless if the environment is secretly importing
    the checkout.
    """
    out = run_in_clean_env(clean_install, r"""
import importlib.metadata as md
for name in ("pulse-data", "pulse-beacon", "pulse-finance", "acme-telemetry"):
    d = md.distribution(name)
    direct = d.read_text("direct_url.json") or ""
    assert "editable" not in direct, f"{name} is installed editable"
    print(f"{name} {d.version}")
""")
    assert out.returncode == 0, out.stderr
    assert "pulse-finance" in out.stdout and "acme-telemetry" in out.stdout


def test_the_bridge_arrives_with_the_execution_extra(clean_install):
    """JPype must come from ``pulse-finance[execution]``, not from the test asking for it.

    The fixture deliberately does not install ``jpype1``. If the extra depended on bare
    pulse-beacon, there would be no bridge here and nothing could start.
    """
    out = run_in_clean_env(clean_install, r"""
import importlib.metadata as md
import jpype                                      # must be present via the extra alone
print("JPYPE", md.version("JPype1"), "BEACON", md.version("pulse-beacon"))
""")
    assert out.returncode == 0, out.stderr
    assert "JPYPE" in out.stdout


def test_a_finance_event_round_trips_through_the_engine_wire_format(finance_only_install):
    """A real event round trip, finance **alone**, over the self-describing wire format.

    Constructing a Java record and reading its fields back proves almost nothing: it never builds
    the Python model, never serialises, and never decodes a returned datum, so the codec and the
    registry lookup could both be broken while it stayed green. An earlier version of this test
    did exactly that. This one goes Python model -> tagged JSON -> Java ``fromTaggedJson`` ->
    a derived Java datum -> tagged JSON -> Python ``from_tagged_json``.

    It also runs in the finance-only environment, because the composition fixture always installs
    the second extension and so never tested finance by itself.
    """
    out = run_in_clean_env(finance_only_install, r"""
from decimal import Decimal
from inventzia.pulse.beacon.core.crosslanguage import jpype_host as jh
jh.start_jvm()
import jpype
from inventzia.pulse.data.datum.codec import to_tagged_json, from_tagged_json
from inventzia.pulse.finance.schemas.position import Position

VWAP = Decimal("68.47")
sent = Position(instrumentId="CL", positionTime=1283630000000,
                signedPosition=100, positionVwap=VWAP, positionPoint="ID_TRD")

# 1. Python -> wire
envelope = to_tagged_json(sent)
assert '"com.inventzia.pulse.finance.schemas.Position"' in envelope, envelope

# 2. wire -> Java, resolved through the registry from the typeId alone
# DatumCodec.instance(), the same accessor beacon's own CrossLanguageGateway uses.
Codec = jpype.JClass("com.inventzia.pulse.data.datum.DatumCodec")
codec = Codec.instance()
received = codec.fromTaggedJson(envelope)
assert str(received.getDatumKey()) == "CL", received.getDatumKey()
assert Decimal(str(received.positionVwap())) == VWAP, received.positionVwap()

# 3. Java derives a new datum and sends it back
PositionJ = jpype.JClass("com.inventzia.pulse.finance.schemas.Position")
BigDecimal = jpype.JClass("java.math.BigDecimal")
derived = PositionJ(received.instrumentId(), received.positionTime(),
                    jpype.JLong(-50), received.positionVwap().add(BigDecimal("0.01")), "EOD")
back = str(codec.toTaggedJson(derived))

# 4. wire -> Python
decoded = from_tagged_json(back)
assert type(decoded) is Position, type(decoded)
assert decoded.instrument_id == "CL"
assert decoded.signed_position == -50
assert decoded.position_vwap == Decimal("68.48"), decoded.position_vwap
assert decoded.position_point == "EOD"
print("EVENT ROUND TRIP EXACT", decoded.position_vwap)
""")
    assert out.returncode == 0, out.stderr
    assert "EVENT ROUND TRIP EXACT 68.48" in out.stdout


def test_two_extensions_compose_with_no_arguments(clean_install):
    """The justification for the whole design, so it is a test and not a remark.

    A second extension under a third-party namespace (``com.acme.telemetry``) is installed
    alongside finance. Plain ``start_jvm()`` must load both, which the previous per-application
    ``extra_classpath`` approach could only do if the caller knew about both packages.
    """
    out = run_in_clean_env(clean_install, r"""
from decimal import Decimal
from inventzia.pulse.beacon.core.crosslanguage import jpype_host as jh
jh.start_jvm()
import jpype
from inventzia.pulse.data.datum.registry import default_registry

Registry = jpype.JClass("com.inventzia.pulse.data.datum.DatumTypeRegistry")
java = sorted(str(p.providerId()) for p in Registry.defaultRegistry().providers())
python = sorted(p.provider_id for p in default_registry().providers())
assert java == python, (java, python)
for required in ("com.inventzia.pulse.finance", "com.acme.telemetry"):
    assert required in java, (required, java)

# Both type sets usable, both exact.
BigDecimal = jpype.JClass("java.math.BigDecimal")
Position = jpype.JClass("com.inventzia.pulse.finance.schemas.Position")
Widget = jpype.JClass("com.acme.telemetry.schemas.WidgetReading")
pos = Position("CL", jpype.JLong(1), jpype.JLong(-5), BigDecimal("68.47"), "EOD")
wid = Widget("W-1", jpype.JLong(2), BigDecimal("0.000000000001"))
assert Decimal(str(pos.positionVwap())) == Decimal("68.47")
assert Decimal(str(wid.value())) == Decimal("0.000000000001")
print("COMPOSED", java)
""")
    assert out.returncode == 0, out.stderr
    assert "COMPOSED" in out.stdout
    assert "com.acme.telemetry" in out.stdout


def test_repeated_startup_in_one_process_is_idempotent(clean_install):
    """Two calls in the same process, isolated from the other scenarios."""
    out = run_in_clean_env(clean_install, r"""
from inventzia.pulse.beacon.core.crosslanguage import jpype_host as jh
jh.start_jvm()
jh.start_jvm()                                   # must reuse, and must still verify
import jpype
assert jpype.isJVMStarted()
print("IDEMPOTENT")
""")
    assert out.returncode == 0, out.stderr
    assert "IDEMPOTENT" in out.stdout


def test_a_running_jvm_missing_a_contribution_is_refused_with_attribution(clean_install):
    """A foreign JVM started without the contributed jars must fail, naming the package.

    The alternative is a ``NoClassDefFoundError`` much later with nothing to attribute it to.
    """
    out = run_in_clean_env(clean_install, r"""
import jpype
from inventzia.pulse.beacon.core.crosslanguage import jpype_host as jh
# A host application boots the JVM itself, with only Beacon's own jar.
beacon_only = jh.beacon_jars()[0]
jpype.startJVM(classpath=beacon_only, convertStrings=True)
try:
    jh.start_jvm()
    raise SystemExit("start_jvm() accepted a JVM missing the contributed jars")
except RuntimeError as exc:
    message = str(exc)
    assert "pulse-finance" in message, message
    assert "Restart" in message, message
    print("ATTRIBUTED AND ACTIONABLE")
""")
    assert out.returncode == 0, out.stderr
    assert "ATTRIBUTED AND ACTIONABLE" in out.stdout


def test_a_damaged_installed_artifact_fails_at_startup_with_attribution(clean_install):
    """The second half of the missing-jar case: the wheel built, but the artifact is damaged.

    The first half, rejection during wheel construction, is covered by
    ``test_build_guard.py`` against the build backend directly.
    """
    out = run_in_clean_env(clean_install, r"""
import pathlib, importlib.resources as res
jar = pathlib.Path(str(res.files("inventzia.pulse.finance._runtime")
                       .joinpath("pulse-finance-core.jar")))
moved = jar.with_suffix(".jar.moved")
jar.rename(moved)
try:
    from inventzia.pulse.beacon.core.crosslanguage import jpype_host as jh
    try:
        jh.resolve_classpath()
        raise SystemExit("a missing installed jar was accepted")
    except jh.ClasspathContributionError as exc:
        message = str(exc)
        assert "pulse-finance" in message, message
        print("DAMAGED ARTIFACT REFUSED")
finally:
    moved.rename(jar)
""")
    assert out.returncode == 0, out.stderr
    assert "DAMAGED ARTIFACT REFUSED" in out.stdout


def test_the_happy_path_needs_no_maven_and_no_checkout_path(clean_install):
    """Maven removed from PATH, JDK kept: ``start_jvm()`` must still succeed.

    An earlier version only resolved paths and accepted any stderr mentioning ``JAVA_HOME``,
    which meant a total failure to start counted as a pass.
    """
    out = run_in_clean_env(
        clean_install,
        r"""
from inventzia.pulse.beacon.core.crosslanguage import jpype_host as jh
import shutil, sys
assert shutil.which("mvn") is None, "Maven is still on PATH; the test proves nothing"

jars, source = jh.resolve_classpath()
assert jars, "no classpath resolved"
for jar in jars:
    normalised = jar.replace("\\", "/")
    assert "/target/" not in normalised, f"resolved a Maven output path: {jar}"
    assert sys.prefix.replace("\\", "/") in normalised, (
        f"jar resolved from outside the installed environment: {jar}")

jh.start_jvm()                                    # must actually start, not merely resolve
import jpype
assert jpype.isJVMStarted()
print("STARTED WITHOUT MAVEN", len(jars))
""",
        # PATH stripped to the system directories, so Maven is gone. The JDK does not need to
        # be on PATH: JPype locates the JVM through JAVA_HOME, which is inherited. Adding the
        # JDK's bin back would reinstate Maven, since the `pulse` env ships both in one directory.
        env_extra={"PATH": "/usr/bin:/bin"},
    )
    assert out.returncode == 0, (out.stdout, out.stderr)
    assert "STARTED WITHOUT MAVEN" in out.stdout
