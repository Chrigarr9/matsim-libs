#!/usr/bin/env bash
# Lyon 10% R1/R2/R3/R4 comparison chain.
#
# Runs the capped R1/R2 degree-4 comparison in a SINGLE mvn invocation so both
# engines share one JVM and one warmed routing cache. Keep R2 capped at degree 4
# by default; uncapped R2/R3/R4 runs take multiple hours and must be launched
# explicitly, not from this quick chain.
#
# In-JVM: R1 vs R2 are byte-identical (verified at d=2, 179,906 = 179,906).
# Cross-JVM: residual ~0.006% pair drift even with shared+quantized cache.
#
# Memory: r1Rides / r2Rides are local to their if-blocks in the test, so each
# list is eligible for GC after its writeRides() returns.
#
# R3 vs R4 isolates the contribution of the post-extension pruner: R3 = distance
# heuristic gate only, R4 = distance heuristic + per-request top-K coverage.
# Together with R2 (no pruning) this gives a clean ladder across the pruning
# configurations, all measured against R1 from the SAME JVM cache.
# Memory: r1Rides / r2Rides are local to their if-blocks in the test, so each
# list is eligible for GC after its writeRides() returns.
#
# R3 vs R4 isolates the contribution of the post-extension pruner: R3 = distance
# heuristic gate only, R4 = distance heuristic + per-request top-K coverage.
# Together with R2 (no pruning) this gives a clean ablation ladder, all measured
# against R1 from the SAME JVM cache.

set -euo pipefail

cd "$(git rev-parse --show-toplevel)/contribs/drt-demand-extraction" || \
  cd "$(dirname "$0")/.."

REPO_ROOT="$(git rev-parse --show-toplevel | xargs -I{} dirname {} | xargs -I{} dirname {} | xargs -I{} dirname {})"
PLANNING_DIR="${REPO_ROOT}/.planning"
mkdir -p "${PLANNING_DIR}"

export JAVA_HOME='C:\Users\VWAUCCY\dev\msf\.jdk\jdk-25.0.2+10'
export PATH="C:\Users\VWAUCCY\dev\msf\.jdk\jdk-25.0.2+10\bin:$PATH"
export LYON_SCENARIO_DIR='C:\Users\VWAUCCY\dev\msf\projects\Dissertation\matsim_scenarios\eqasim-france\output_lyon_drt_10pct\lyon_drt_area'
export LYON_REQUESTS_CSV='C:\Users\VWAUCCY\dev\msf\projects\Dissertation\outputs\lyon-eqasim-demand-extraction-10pct\drt_demand\lyon-drt-10pct-eqasim-exmas.drt_requests.csv'
export LYON_SAMPLE_PCT=10

# Per-engine overrides keep both broad engines capped at degree 4. This is the
# intended fast parity check while the remaining algorithmic work is active.
LOG="${PLANNING_DIR}/r1r2r3r4-chain-10pct-v13-singlejvm.log"

echo "[$(date -Iseconds)] === R1 d<=4 + R2/R3/R4 d<=4 (single JVM) ===" | tee "${LOG}"
mvn -o test -Dtest=ExMasLyonR1R2FastComparisonTest -Denforcer.skip=true \
  -Djunit.groups=scenario-lyon -Djunit.excludedGroups= \
  -DargLine="-Xmx100g -DmaxPoolingDegreeR1=4 -DmaxPoolingDegreeR2=4 -DmaxPoolingDegreeR3=4 -DmaxPoolingDegreeR4=4 -DrunR3=true -DrunR4=true" \
  -DmaxPoolingDegreeR1=4 -DmaxPoolingDegreeR2=4 -DmaxPoolingDegreeR3=4 -DmaxPoolingDegreeR4=4 \
  -DrunR3=true -DrunR4=true \
  >> "${LOG}" 2>&1

echo "[$(date -Iseconds)] === Chain complete ==="
