#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# Build the finance jar and stage it as wheel package data, together with a digest of the
# inputs it was built from.
#
# The digest is the point. pulse-beacon's equivalent guard only checks that a jar exists, which
# a jar built from older sources passes. File timestamps cannot substitute: a checkout, a copy,
# or WSL and Windows disagreeing about mtimes all make a stale jar look current.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNTIME_DIR="$HERE/src/inventzia/pulse/finance/_runtime"
JAR_NAME="pulse-finance-core.jar"
DIGEST_NAME="$JAR_NAME.inputs"

# The digest has ONE implementation, in _build_backend.py, which the wheel build also uses.
# Duplicating it here would let the recorded and the verified digest drift.
inputs_digest() {
    python -c "import sys; sys.path.insert(0, '$HERE'); import _build_backend; print(_build_backend.inputs_digest())"
}

# The exact artifact for the version in the POM, from a CLEAN build.
#
# Both halves matter. Without `clean`, a version change leaves the previous version's jar in
# target/ alongside the new one, and picking "the first match" could stage the old artifact and
# then record the CURRENT input digest against it, certifying a stale jar as fresh. Globbing for
# a version is likewise wrong: ask the POM which version this is and demand that exact file.
version="$(mvn -B -ntp -q -f "$HERE/core/java/pom.xml" \
             help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -1 | tr -d '[:space:]')"
test -n "$version" || { echo "could not read project.version from the POM" >&2; exit 1; }
echo "building the finance jar (version $version, clean)"
mvn -B -ntp -f "$HERE/core/java/pom.xml" clean package -DskipTests >/dev/null

built="$HERE/core/java/target/pulse-finance-core-$version.jar"
if [[ ! -f "$built" ]]; then
    echo "Maven did not produce the expected artifact:" >&2
    echo "    $built" >&2
    echo "  target/ contains: $(ls "$HERE/core/java/target"/*.jar 2>/dev/null || echo none)" >&2
    exit 1
fi

mkdir -p "$RUNTIME_DIR"
cp "$built" "$RUNTIME_DIR/$JAR_NAME"
inputs_digest > "$RUNTIME_DIR/$DIGEST_NAME"

echo "staged $(basename "$built") -> $RUNTIME_DIR/$JAR_NAME"
echo "inputs digest: $(cat "$RUNTIME_DIR/$DIGEST_NAME")"
