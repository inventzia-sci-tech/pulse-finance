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

echo "building the finance jar"
mvn -B -ntp -f "$HERE/core/java/pom.xml" package -DskipTests >/dev/null

built="$(find "$HERE/core/java/target" -maxdepth 1 -name 'pulse-finance-core-*.jar' \
         -not -name '*-sources.jar' -not -name '*-javadoc.jar' | head -1)"
test -n "$built" || { echo "no jar produced by Maven" >&2; exit 1; }

mkdir -p "$RUNTIME_DIR"
cp "$built" "$RUNTIME_DIR/$JAR_NAME"
inputs_digest > "$RUNTIME_DIR/$DIGEST_NAME"

echo "staged $(basename "$built") -> $RUNTIME_DIR/$JAR_NAME"
echo "inputs digest: $(cat "$RUNTIME_DIR/$DIGEST_NAME")"
