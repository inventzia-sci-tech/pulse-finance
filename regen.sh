#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# Regenerate pulse-finance's Python + Java datum bindings and its DatumTypeProvider,
# using pulse-data's generators with THIS package's namespace and provider id.
#
# pulse-finance owns its finance schemas; pulse-data owns the machinery that generates
# them. See initialtransitioning.md, "Decision: datum ownership".
#
#   ./regen.sh            regenerate in place
#   ./regen.sh --check    regenerate into a temp tree and fail on any difference (CI)
#
# Two guarantees are pinned here, and they are NOT the same thing:
#   * SPI compatibility   — the runtime contract with pulse-data's registry (SPI_VERSION).
#   * Generator reproducibility — that this revision of the generators produces the
#     committed bindings. A sibling checkout plus a matching SPI_VERSION does not give
#     you this: two generator revisions can both be SPI-1 compatible and still emit
#     different code. Hence the revision and digest pins below.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# pulse-data's generators. Defaults to a sibling checkout; override with PULSE_DATA_GENERATORS.
GEN="${PULSE_DATA_GENERATORS:-$HERE/../pulse-data/schemas/schemas-generators}"

PROVIDER_ID="com.inventzia.pulse.finance"
PROVIDER_CLASS="FinanceDatumTypeProvider"
JAVA_PKG="com.inventzia.pulse.finance.schemas"
PY_PKG="inventzia.pulse.finance.schemas"

# --- the generator pin -------------------------------------------------------
# Single authoritative source: generator.pin at the repository root. Never inline these
# values here - two copies is how a pin goes stale and starts lying.
PIN_FILE="$HERE/generator.pin"

pin_value() {   # pin_value <key>
    sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*\\([^[:space:]]*\\).*/\\1/p" "$PIN_FILE"
}

verify_generator_pin() {
    local actual want pinned_rev rev_note="" fail=0
    if [[ ! -f "$PIN_FILE" ]]; then
        echo "❌  generator pin not found: $PIN_FILE" >&2
        exit 1
    fi
    pinned_rev="$(pin_value pulse_data_revision)"
    if [[ ! -d "$GEN" ]]; then
        echo "❌  pulse-data generators not found at: $GEN" >&2
        echo "    set PULSE_DATA_GENERATORS to .../pulse-data/schemas/schemas-generators" >&2
        exit 1
    fi
    if rev="$(git -C "$GEN" rev-parse HEAD 2>/dev/null)"; then
        if [[ "$rev" != "$pinned_rev" ]]; then
            rev_note="    note: pulse-data HEAD is ${rev:0:7}, pin is ${pinned_rev:0:7}"
        fi
        if [[ -n "$(git -C "$GEN" status --short -- . 2>/dev/null)" ]]; then
            echo "⚠   pulse-data generator tree has uncommitted changes — output is not reproducible" >&2
        fi
    fi
    for file in generate_java.py generate_python.py manifest.py; do
        want="$(pin_value "sha256_16.$file")"
        if [[ -z "$want" ]]; then
            echo "❌  no digest pinned for $file in $PIN_FILE" >&2
            fail=1
            continue
        fi
        actual="$(sha256sum "$GEN/$file" | cut -c1-16)"
        if [[ "$actual" != "$want" ]]; then
            echo "❌  generator digest mismatch: $file (have $actual, pinned $want)" >&2
            fail=1
        fi
    done
    if (( fail )); then
        [[ -n "$rev_note" ]] && echo "$rev_note" >&2
        echo "    The generators changed. Review the diff, regenerate deliberately, then update" >&2
        echo "    generator.pin (revision and digests) in the same commit." >&2
        exit 1
    fi
}

# --package-version is passed EXPLICITLY on purpose. Omitted, generate_python.py falls back
# to reading pulse-data's own pyproject.toml, and the finance provider would then report
# pulse-data's version as its packageVersion().
project_version() {
    python3 -c "import tomllib,pathlib;print(tomllib.loads(pathlib.Path('$HERE/pyproject.toml').read_text())['project']['version'])"
}

generate_into() {
    local py_out="$1" java_out="$2" version="$3"
    python3 "$GEN/generate_python.py" \
        --schemas-dir "$HERE/schemas" --output-dir "$py_out" \
        --base-package "$PY_PKG" \
        --provider-id "$PROVIDER_ID" --provider-class "$PROVIDER_CLASS" \
        --package-version "$version"
    python3 "$GEN/generate_java.py" \
        --schemas-dir "$HERE/schemas" --output-dir "$java_out" \
        --base-package "$JAVA_PKG" \
        --provider-id "$PROVIDER_ID" --provider-class "$PROVIDER_CLASS" \
        --package-version "$version"
}

verify_generator_pin
VERSION="$(project_version)"

PY_OUT="$HERE/src/inventzia/pulse/finance/schemas"
JAVA_OUT="$HERE/core/java/src/main/java/com/inventzia/pulse/finance/schemas"

have_schemas() { compgen -G "$HERE/schemas/*.yaml" >/dev/null; }

if [[ "${1:-}" == "--check" ]]; then
    # An empty schemas/ is a legitimate state, not a failure: the pure accounting kernel is
    # built before the schemas on purpose. What must never pass unnoticed is a mismatch in
    # EITHER direction - schemas without bindings, or bindings that have outlived their schema.
    if ! have_schemas; then
        if [[ -d "$PY_OUT" || -d "$JAVA_OUT" ]]; then
            echo "❌  generated bindings exist but schemas/ has no YAML — stale output:" >&2
            [[ -d "$PY_OUT"   ]] && echo "      $PY_OUT" >&2
            [[ -d "$JAVA_OUT" ]] && echo "      $JAVA_OUT" >&2
            echo "    Remove them, or restore the schema they came from." >&2
            exit 1
        fi
        echo "✅  no schemas and no generated bindings — nothing to check (see schemas/README.md)"
        exit 0
    fi
    if [[ ! -d "$PY_OUT" || ! -d "$JAVA_OUT" ]]; then
        echo "❌  schemas/ has YAML but generated bindings are missing — run ./regen.sh" >&2
        exit 1
    fi

    TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
    generate_into "$TMP/py" "$TMP/java" "$VERSION" >/dev/null
    rc=0
    diff -ru "$PY_OUT"   "$TMP/py/inventzia/pulse/finance/schemas"       || rc=1
    diff -ru "$JAVA_OUT" "$TMP/java/com/inventzia/pulse/finance/schemas" || rc=1
    if (( rc )); then
        echo "❌  committed bindings differ from freshly generated output — run ./regen.sh" >&2
        exit 1
    fi
    echo "✅  committed bindings match the pinned generators"
    exit 0
fi

if ! have_schemas; then
    echo "⚠   schemas/ contains no YAML — nothing to generate (see schemas/README.md)."
    exit 0
fi

generate_into "$HERE/src" "$HERE/core/java/src/main/java" "$VERSION"
echo "regenerated finance bindings + $PROVIDER_CLASS (version $VERSION)"
echo
echo "NOTE: the generators hardcode a 'This file is part of pulse-data.' licence header and"
echo "      offer no --project-name. Generated finance files therefore misattribute ownership."
echo "      Tracked as a pulse-data extension-packaging fix; see initialtransitioning.md."
