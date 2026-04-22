#!/usr/bin/env bash
# Regenerates src/test/resources/golden/exmas-kelheim/{exmas_rides,drt_requests}.csv
# by running this branch's R1 profile (ExMasAlgorithmE2ETest with AlgorithmProfile.R1).
#
# After the 2026-04-22 fix (getAllPairRideCombinations — full 2^n extension
# enumeration per paper Algorithm 2), the golden represents the corrected R1
# and can no longer be regenerated from main's binary. The golden is now
# self-referential: it captures the current branch's R1 output for future
# drift detection.
#
# Pass --force to overwrite an existing golden (otherwise refuses to clobber).
# Run from the matsim-libs/contribs/drt-demand-extraction/ directory.
# Requires JDK 25 on PATH.

set -euo pipefail

CONTRIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GOLDEN_DIR="$CONTRIB_DIR/src/test/resources/golden/exmas-kelheim"
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

cd "$CONTRIB_DIR"
echo "Running ExMasAlgorithmE2ETest with AlgorithmProfile.R1 (Kelheim)..."
mvn test -Dtest="ExMasAlgorithmE2ETest#kelheimAlgorithmRunsEndToEnd[Kelheim + R1]" \
    -Denforcer.skip=true

OUT="test/output/kelheim-R1/drt_demand"
if [[ ! -e "$OUT/kelheim-mini.exmas_rides.csv" ]]; then
  echo "Expected output CSV missing at $OUT — test invocation may have changed?" >&2
  exit 1
fi

mkdir -p "$GOLDEN_DIR"
cp "$OUT/kelheim-mini.exmas_rides.csv" "$GOLDEN_DIR/exmas_rides.csv"
cp "$OUT/kelheim-mini.drt_requests.csv" "$GOLDEN_DIR/drt_requests.csv"

BRANCH_SHA=$(git rev-parse HEAD)
RIDE_COUNT=$(($(wc -l < "$GOLDEN_DIR/exmas_rides.csv") - 1))
REQUEST_COUNT=$(($(wc -l < "$GOLDEN_DIR/drt_requests.csv") - 1))

cat > "$GOLDEN_DIR/METADATA" <<EOF
Golden CSVs for ExMasReferencePortRegressionTest.

source_branch=feature/exmas-reference-fork
source_sha=$BRANCH_SHA
generated_at=$(date -Iseconds)
generator=ExMasAlgorithmE2ETest (kelheim + AlgorithmProfile.R1)
generator_invocation=mvn test -Dtest="ExMasAlgorithmE2ETest#kelheimAlgorithmRunsEndToEnd[Kelheim + R1]"
java=$(java -version 2>&1 | head -1)
scenario=matsim-examples kelheim-mini (1% sample)

R1 configuration (paper Algorithm 2, full 2^n enumeration):
  - Full Cartesian product of FIFO/LIFO edges per candidate (fix 2026-04-22)
  - No pruning (all PruningMode gates off)
  - MATSim scoring model (replaces Python WtS formula)

Note: this golden no longer matches main's binary (main still takes [0] from
  list(product(*E))). The regression test guards against future drift in the
  corrected R1 implementation, not against main parity.

Counts:
  exmas_rides.csv:    $RIDE_COUNT rides
  drt_requests.csv:   $REQUEST_COUNT requests

Regenerate with: scripts/regenerate_exmas_reference_golden.sh [--force]
EOF

echo "Golden refreshed (paper Algorithm 2 R1):"
echo "  $GOLDEN_DIR/exmas_rides.csv ($RIDE_COUNT rides)"
echo "  $GOLDEN_DIR/drt_requests.csv ($REQUEST_COUNT requests)"
echo "  branch_sha=$BRANCH_SHA"
