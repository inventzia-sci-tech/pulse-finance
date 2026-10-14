#!/usr/bin/env bash
# Build this fixture's jar and wheel. Uses javac directly: it is three generated classes, and a
# Maven module would add a build system to a test fixture for no benefit.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
M2="$HOME/.m2/repository"
CP="$M2/com/inventzia/pulse/pulse-data/0.4.0-SNAPSHOT/pulse-data-0.4.0-SNAPSHOT.jar"
CP="$CP:$M2/com/fasterxml/jackson/core/jackson-annotations/2.18.2/jackson-annotations-2.18.2.jar"
CP="$CP:$M2/org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar"

rm -rf "$HERE/build-classes" "$HERE/dist"
mkdir -p "$HERE/build-classes/META-INF/services"
javac -cp "$CP" -d "$HERE/build-classes" \
      $(find "$HERE/java" -name '*.java') 2>&1 | grep -v "^Note:" || true

# The services registration: the file is named for the interface, its content is Acme's class.
echo "com.acme.telemetry.schemas.AcmeDatumTypeProvider" \
  > "$HERE/build-classes/META-INF/services/com.inventzia.pulse.data.datum.DatumTypeProvider"

jar --create --file "$HERE/src/acme/telemetry/_runtime/acme-telemetry.jar" \
    -C "$HERE/build-classes" .
echo "staged acme-telemetry.jar ($(stat -c%s "$HERE/src/acme/telemetry/_runtime/acme-telemetry.jar") bytes)"

python -m build --wheel --no-isolation --outdir "$HERE/dist" "$HERE" >/dev/null
echo "built $(ls "$HERE/dist")"
