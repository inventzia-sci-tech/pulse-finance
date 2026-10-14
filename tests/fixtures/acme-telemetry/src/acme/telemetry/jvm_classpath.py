"""This extension's JVM classpath contribution.

A zero-argument callable returning jar paths, located inside the installed wheel via
importlib.resources. Must not start a JVM, import JPype, or touch the datum registry.
"""
from __future__ import annotations

from pathlib import Path

_JAR_NAME = "acme-telemetry.jar"
_RUNTIME_PACKAGE = "acme.telemetry._runtime"


def jars() -> list[str]:
    from importlib.resources import files
    jar = Path(str(files(_RUNTIME_PACKAGE).joinpath(_JAR_NAME)))
    if not jar.is_file():
        raise FileNotFoundError(
            f"{_JAR_NAME} is not in the installed wheel at {jar}. The wheel was built without "
            f"its jar, so Java cannot load com.acme.telemetry types while Python has them.")
    return [str(jar)]
