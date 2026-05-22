#!/usr/bin/env bash
# Lyon 100% R6 production run — Block 1 of papers/paper1/planning/simulation-flow.md.
#
# Goal: full end-to-end demand extraction at 100% Lyon with R6 (production profile:
# scale=0.25 + COVERAGE_TOPK K=20), producing the ride DB for the Python MIP.
#
# History:
#   R4 (scale=0.25) OOM'd at d4 on 2026-05-02 — 11.1M rides / 108.4 GB heap.
#   R5 (scale=0.30) succeeded on 2026-05-03 — 5.59M rides, stable 90 GB, d11 cascade.
#   R6 (scale=0.25 + K=20) runs 2026-05-03, overwrites R5 output dir as production result.
#   R6 expected tractable: post-GC stable set ~90 GB (same as R5); K=20 post-hoc
#   compression keeps the final DB manageable for MIP.
#
# Settings:
#   --sample 100                          full Lyon DRT-area population
#   --trip-filter-radius-km 20.0          20 km cluster radius
#   --no-exclusion-zone                   no Métropole de Lyon exclusion polygon
#   --profile r7                          BAMAS + scale=0.30 + COVERAGE_TOPK K=20
#
# Predecessors + Shapley enabled (required for MIP: connection_cache.csv + per-person value attribution)
#
# Travel-time matrix: per simulation-flow.md §2 ("single travel-time matrix
# computed from Lyon 10% base sim, reused at every scale") we point at the
# fullregion 10% travel_times.tsv.

set -euo pipefail

cd "$(git rev-parse --show-toplevel)/contribs/drt-demand-extraction" || \
  cd "$(dirname "$0")/.."

REPO_ROOT="$(git rev-parse --show-superproject-working-tree)"
PLANNING_DIR="${REPO_ROOT}/.planning"
mkdir -p "${PLANNING_DIR}"

export JAVA_HOME='C:\Users\VWAUCCY\dev\msf\.jdk\jdk-25.0.2+10'
export PATH="C:\Users\VWAUCCY\dev\msf\.jdk\jdk-25.0.2+10\bin:$PATH"
export MAVEN_OPTS="-Xmx100g -Djava.awt.headless=true"

SCENARIO_DIR="${REPO_ROOT}/matsim_scenarios/eqasim-france/output_100pct/lyon_drt_area"
TRAVEL_TIMES="${REPO_ROOT}/matsim_scenarios/eqasim-france/output_fullregion_10pct/travel_times.tsv"
OUTPUT_DIR="${REPO_ROOT}/outputs/lyon-eqasim-demand-extraction-100pct-r5"
LOG="${PLANNING_DIR}/lyon100pct-r7-run.log"

mkdir -p "${OUTPUT_DIR}"

echo "[$(date -Iseconds)] === Lyon 100% R7 production run (scale=0.30 + K=20, 20 km, no exclusion) ===" | tee "${LOG}"
echo "  scenario:     ${SCENARIO_DIR}"      | tee -a "${LOG}"
echo "  travel-times: ${TRAVEL_TIMES}"      | tee -a "${LOG}"
echo "  output-dir:   ${OUTPUT_DIR}"        | tee -a "${LOG}"

mvn -o exec:java \
  -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunLyonEqasimDemandExtraction" \
  -Dexec.args="--sample 100 \
               --scenario-dir ${SCENARIO_DIR} \
               --prefix lyon_drt_area_ \
               --travel-times ${TRAVEL_TIMES} \
               --output-dir ${OUTPUT_DIR} \
               --profile r7 \
               --trip-filter-radius-km 20.0 \
               --no-exclusion-zone \
               --predecessors-filter-time 1800" \
  -DargLine="-Xmx100g -Djava.awt.headless=true" \
  -Denforcer.skip=true \
  >> "${LOG}" 2>&1

echo "[$(date -Iseconds)] === R7 production run complete ===" | tee -a "${LOG}"
echo "Output: ${OUTPUT_DIR}"                                    | tee -a "${LOG}"
