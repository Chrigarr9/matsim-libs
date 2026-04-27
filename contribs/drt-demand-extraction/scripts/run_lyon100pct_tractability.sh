#!/usr/bin/env bash
# Lyon 100% R3 tractability probe — Block 1 of papers/paper1/planning/simulation-flow.md.
#
# Goal: find out whether a full end-to-end demand extraction at 100% Lyon is
# tractable on this hardware, so the Block 3 MIP branch (A vs B) can be chosen.
# This is *not* a comparison run — only R3 (BAMAS, full production pruning) is
# executed. Predecessor/successor and Shapley exports are disabled because they
# are downstream-MIP plumbing that does not affect the tractability question and
# would otherwise dominate runtime at 100% scale.
#
# Settings (per user spec, 2026-04-26):
#   --sample 100                          full Lyon DRT-area population
#   --trip-filter-radius-km 20.0          20 km cluster radius
#   --no-exclusion-zone                   no Métropole de Lyon exclusion polygon
#   --no-predecessors                     skip predecessor/successor calculation
#   --no-shapley                          skip Shapley value calculation
#   --profile r3                          BAMAS production-default pruning
#
# Travel-time matrix: per simulation-flow.md §2 ("single travel-time matrix
# computed from Lyon 10% base sim, reused at every scale") we point at the
# fullregion 10% travel_times.tsv.

set -euo pipefail

cd "$(git rev-parse --show-toplevel)/contribs/drt-demand-extraction" || \
  cd "$(dirname "$0")/.."

REPO_ROOT="$(git rev-parse --show-toplevel | xargs -I{} dirname {} | xargs -I{} dirname {} | xargs -I{} dirname {})"
PLANNING_DIR="${REPO_ROOT}/.planning"
mkdir -p "${PLANNING_DIR}"

export JAVA_HOME='C:\Users\VWAUCCY\dev\msf\.jdk\jdk-25.0.2+10'
export PATH="C:\Users\VWAUCCY\dev\msf\.jdk\jdk-25.0.2+10\bin:$PATH"

SCENARIO_DIR="${REPO_ROOT}/matsim_scenarios/eqasim-france/output_100pct/lyon_drt_area"
TRAVEL_TIMES="${REPO_ROOT}/matsim_scenarios/eqasim-france/output_fullregion_10pct/travel_times.tsv"
OUTPUT_DIR="${REPO_ROOT}/outputs/lyon-eqasim-demand-extraction-100pct-tractability"
LOG="${PLANNING_DIR}/lyon100pct-tractability.log"

mkdir -p "${OUTPUT_DIR}"

echo "[$(date -Iseconds)] === Lyon 100% tractability probe (R3, 20 km, no exclusion) ===" | tee "${LOG}"
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
               --profile r3 \
               --trip-filter-radius-km 20.0 \
               --no-exclusion-zone \
               --no-predecessors \
               --no-shapley" \
  -DargLine="-Xmx100g -Djava.awt.headless=true" \
  -Denforcer.skip=true \
  >> "${LOG}" 2>&1

echo "[$(date -Iseconds)] === Tractability probe complete ===" | tee -a "${LOG}"
echo "Output: ${OUTPUT_DIR}"                                    | tee -a "${LOG}"
