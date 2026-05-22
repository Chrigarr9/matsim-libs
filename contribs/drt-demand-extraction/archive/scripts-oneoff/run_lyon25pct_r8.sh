#!/usr/bin/env bash
# Lyon 25% R8 production run.
#
# Profile R8: BAMAS + distance gate scale=0.20 + post-extension COVERAGE_TOPK K=20.
# Lighter gate than R6 (0.25) to recover pooling coverage at the smaller sample;
# K=20 post-cascade compression keeps the ride DB tractable for the Python MIP.
#
# Settings:
#   --sample 25                           25% Lyon DRT-area population
#   --trip-filter-radius-km 20.0          20 km cluster radius
#   --no-exclusion-zone                   no Métropole de Lyon exclusion polygon
#   --profile r8                          BAMAS + scale=0.20 + COVERAGE_TOPK K=20
#
# Predecessors + Shapley enabled (required for MIP: connection_cache.csv + per-person value attribution)
#
# Travel-time matrix: reused from the Lyon 10% base sim (fullregion).

set -euo pipefail

cd "$(git rev-parse --show-toplevel)/contribs/drt-demand-extraction" || \
  cd "$(dirname "$0")/.."

REPO_ROOT="$(git rev-parse --show-superproject-working-tree)"
PLANNING_DIR="${REPO_ROOT}/.planning"
mkdir -p "${PLANNING_DIR}"

export JAVA_HOME='C:\Users\VWAUCCY\dev\msf\.jdk\jdk-25.0.2+10'
export PATH="C:\Users\VWAUCCY\dev\msf\.jdk\jdk-25.0.2+10\bin:$PATH"
export MAVEN_OPTS="-Xmx100g -Djava.awt.headless=true"

SCENARIO_DIR="${REPO_ROOT}/matsim_scenarios/eqasim-france/output_lyon_drt_25pct"
TRAVEL_TIMES="${REPO_ROOT}/matsim_scenarios/eqasim-france/output_fullregion_10pct/travel_times.tsv"
OUTPUT_DIR="${REPO_ROOT}/outputs/lyon-eqasim-demand-extraction-25pct-r8"
LOG="${PLANNING_DIR}/lyon25pct-r8-run.log"

mkdir -p "${OUTPUT_DIR}"

echo "[$(date -Iseconds)] === Lyon 25% R8 run (scale=0.20 + K=20, 20 km, no exclusion) ===" | tee "${LOG}"
echo "  scenario:     ${SCENARIO_DIR}"      | tee -a "${LOG}"
echo "  travel-times: ${TRAVEL_TIMES}"      | tee -a "${LOG}"
echo "  output-dir:   ${OUTPUT_DIR}"        | tee -a "${LOG}"

mvn -o exec:java \
  -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunLyonEqasimDemandExtraction" \
  -Dexec.args="--sample 25 \
               --scenario-dir ${SCENARIO_DIR} \
               --prefix lyon_drt_25pct_ \
               --travel-times ${TRAVEL_TIMES} \
               --output-dir ${OUTPUT_DIR} \
               --profile r8 \
               --trip-filter-radius-km 20.0 \
               --no-exclusion-zone \
               --predecessors-filter-time 7200" \
  -DargLine="-Xmx100g -Djava.awt.headless=true" \
  -Denforcer.skip=true \
  >> "${LOG}" 2>&1

echo "[$(date -Iseconds)] === R8 25% run complete ===" | tee -a "${LOG}"
echo "Output: ${OUTPUT_DIR}"                            | tee -a "${LOG}"
