#!/usr/bin/env bash
# Regenerates src/test/resources/golden/exmas-kelheim/{exmas_rides,drt_requests}.csv
# by running matsim-libs main's ExMasKelheimE2ETest in a temporary worktree.
#
# Per .project-memory/exmas-reference-pruner-default-2026-04-21.md, main's
# stock ExMasKelheimE2ETest is already "vanilla ExMAS" — its default pruner
# knobs are off — so no test override is required.
#
# Use this script after major changes to the algorithm/exmas/ port if you
# want to refresh the golden against a newer main HEAD. Pass --force to
# overwrite an existing golden (otherwise refuses to clobber).
#
# Run from the matsim-libs/contribs/drt-demand-extraction/ directory. Requires
# JDK 25 on PATH (eqasim 2.1.0 dependency was compiled for Java 25, although
# main itself is on eqasim 2.0.0 — JDK 25 covers both).

set -euo pipefail

CONTRIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GOLDEN_DIR="$CONTRIB_DIR/src/test/resources/golden/exmas-kelheim"
SUBMODULE_ROOT="$(cd "$CONTRIB_DIR/../.." && pwd)"
WORKTREE_PATH="$SUBMODULE_ROOT/.worktrees/main-port-golden"
FORCE=0

for arg in "$@"; do
  case "$arg" in
    --force) FORCE=1 ;;
    *) echo "Unknown arg: $arg" >&2; exit 2 ;;
  esac
done

if [[ -e "$GOLDEN_DIR/exmas_rides.csv" && $FORCE -eq 0 ]]; then
  echo "Golden already exists at $GOLDEN_DIR — pass --force to overwrite." >&2
  exit 1
fi

if [[ ! -d "$WORKTREE_PATH" ]]; then
  echo "Creating main worktree at $WORKTREE_PATH"
  git -C "$SUBMODULE_ROOT" worktree add "$WORKTREE_PATH" main
fi

cd "$WORKTREE_PATH/contribs/drt-demand-extraction"
echo "Running main's ExMasKelheimE2ETest..."
mvn test -Dtest=ExMasKelheimE2ETest -Denforcer.skip=true

OUT="test/output/exmas-kelheim-e2e-test/drt_demand"
if [[ ! -e "$OUT/kelheim-mini.exmas_rides.csv" ]]; then
  echo "Expected output CSV missing at $OUT — test must have changed?" >&2
  exit 1
fi

mkdir -p "$GOLDEN_DIR"
cp "$OUT/kelheim-mini.exmas_rides.csv" "$GOLDEN_DIR/exmas_rides.csv"
cp "$OUT/kelheim-mini.drt_requests.csv" "$GOLDEN_DIR/drt_requests.csv"

MAIN_SHA=$(git -C "$WORKTREE_PATH" rev-parse HEAD)
RIDE_COUNT=$(($(wc -l < "$GOLDEN_DIR/exmas_rides.csv") - 1))
REQUEST_COUNT=$(($(wc -l < "$GOLDEN_DIR/drt_requests.csv") - 1))

cat > "$GOLDEN_DIR/METADATA" <<EOF
Golden CSVs for ExMasReferencePortRegressionTest.

source_branch=main
source_sha=$MAIN_SHA
generated_at=$(date -Iseconds)
generator=ExMasKelheimE2ETest (matsim-libs/contribs/drt-demand-extraction)
generator_invocation=mvn test -Dtest=ExMasKelheimE2ETest -Denforcer.skip=true
java=$(java -version 2>&1 | head -1)
scenario=matsim-examples kelheim-mini (1% sample)

Vanilla ExMAS configuration:
  Per .project-memory/exmas-reference-pruner-default-2026-04-21.md, main's
  default PostExtensionPruner knobs are off — no override needed.

Counts:
  exmas_rides.csv:    $RIDE_COUNT rides
  drt_requests.csv:   $REQUEST_COUNT requests

Regenerate with: scripts/regenerate_exmas_reference_golden.sh [--force]
EOF

echo "Golden refreshed:"
echo "  $GOLDEN_DIR/exmas_rides.csv ($RIDE_COUNT rides)"
echo "  $GOLDEN_DIR/drt_requests.csv ($REQUEST_COUNT requests)"
echo "  main_sha=$MAIN_SHA"
