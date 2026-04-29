#!/usr/bin/env bash
# Lyon 10% R1/R2/R3/R4 comparison chain.
#
# Runs sequentially:
#   1. R1 d≤4 with new memory + time-breakdown profiling (~10 min)
#      → r1_rides.csv, log: .planning/r1r2-fast-test-10pct-v8-r1-d4.log
#   2. R2 full + R3 full + R4 full with new profiling (~6-7h)
#      → r2_rides.csv (no pruning), r3_rides.csv (distance only),
#        r4_rides.csv (distance + post-extension),
#        log: .planning/r1r2-fast-test-10pct-v9-r2-r3-r4.log
#
# Why two invocations: keeps R1's log isolated for d≤4 and lets the R2+R3+R4
# trio share a warmed routing cache within a single mvn process.
#
# R3 vs R4 isolates the contribution of the post-extension pruner: R3 = distance
# heuristic gate only, R4 = distance heuristic + per-request top-K coverage.
# Together with R2 (no pruning) this gives a clean ablation across all three
# pruning configurations against the same R1 reference.

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

R1_LOG="${PLANNING_DIR}/r1r2-fast-test-10pct-v8-r1-d4.log"
R2R3R4_LOG="${PLANNING_DIR}/r1r2-fast-test-10pct-v9-r2-r3-r4.log"

echo "[$(date -Iseconds)] === Step 1: R1 d≤4 ===" | tee "${R1_LOG}"
mvn -o test -Dtest=ExMasLyonR1R2FastComparisonTest -Denforcer.skip=true \
  -Djunit.groups=scenario-lyon -Djunit.excludedGroups= \
  -DargLine="-Xmx100g -DskipR2=true -DmaxPoolingDegree=4" \
  -DskipR2=true -DmaxPoolingDegree=4 \
  >> "${R1_LOG}" 2>&1

echo "[$(date -Iseconds)] === R1 done. Step 2: R2 full + R3 full + R4 full ===" | tee "${R2R3R4_LOG}"
mvn -o test -Dtest=ExMasLyonR1R2FastComparisonTest -Denforcer.skip=true \
  -Djunit.groups=scenario-lyon -Djunit.excludedGroups= \
  -DargLine="-Xmx100g -DskipR1=true -DrunR3=true -DrunR4=true" \
  -DskipR1=true -DrunR3=true -DrunR4=true \
  >> "${R2R3R4_LOG}" 2>&1

echo "[$(date -Iseconds)] === Chain complete ==="
