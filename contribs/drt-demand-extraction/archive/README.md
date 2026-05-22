# archive/

Files moved out of the active `drt-demand-extraction/` contrib during the May 2026 cleanup. Everything here is preserved for traceability — old implementation reports, completed-phase planning logs, and one-off analysis/run scripts that informed the current pipeline but are not part of the canonical Paper 1 / dissertation workflow.

## Contents

| Subdir | What's in it |
|---|---|
| `root-implementation-notes/` | Root-level one-off implementation reports (Dec 2025 / Mar 2026): `IMPLEMENTATION_COMPLETE.md`, `LOGGING_IMPLEMENTATION.md`, `PAIRGENERATOR_OPTIMIZATION.md`, `PYTHON_JAVA_COMPARISON.md`, `REFACTORING_SUMMARY.md`, `ROUTING_STATISTICS_FEATURE.md`, `TESTING_GUIDE.md`, `CODE_REVIEW_IMPROVEMENTS.md`, and `mode_comparison_output.txt` (60 KB raw dump). Completed-feature reports superseded by `docs/drt-demand-extraction-architecture.md` + `CHANGELOG.md`. |
| `docs-plans-old/` | 49 of 51 `docs/plans/*.md` design / implementation / session-log files (2026-03-13 → 2026-04-22). Covers scoring-agnostic refactor, ExMAS scalability, pruned greedy, inter-degree pruning, ordering-based extension, degree-specific graph, ordering conflicts, BnB tightening, forbidden-prefix index, parent-ordering cache, ExMAS reference port, SSSP pair generation. All implementation work logged here is shipped and reflected in the current code. |
| `scripts-oneoff/` | 12 of 14 one-off analysis + run-driver scripts: `analyze_fallback_edges.py`, `analyze_missed_rides.py`, `analyze_ordering_constraints.py`, `analyze_routing_scaling.py`, `analyze_subtriple_feasibility.py`, `compare_scoring.py`, `conflict_density_analysis.py`, `compare_archives.ps1`, `first_diff_line.ps1`, plus 3 Lyon run-chain drivers (`run_lyon100pct_tractability.sh`, `run_lyon10pct_chain.sh`, `run_lyon25pct_r8.sh`). |
| `notebooks-old/` | 2 historical analysis notebooks: `10pct_param_sweep.ipynb`, `pruning_strategy_comparison.ipynb`. Superseded by paper1 notebook in main repo. |
| `planning-historical/` | 2 `.planning/` historical docs: `extension-algorithm-redesign.md` (2026-03-31 algorithm-evolution notes), `2026-04-24-r1-r2-equivalence-debug.md`. Both work is complete. |

## What stayed in the active tree

- `src/`, `test/src/` — Java source + tests (untouched).
- `pom.xml` — Maven build.
- Root: `README.md`, `CHANGELOG.md`, `CLAUDE.md`.
- `docs/`: `drt-demand-extraction-architecture.md`, `HYPERPOOL_INTEGRATION_PLAN.md`.
- `docs/plans/`: 2 kept — `2026-04-21-exmas-reference-fork-design.md` (referenced from CLAUDE.md), `2026-04-29-r1-partial-degree5-checkpointing-plan.md` (most recent).
- `scripts/`: 2 kept — `regenerate_exmas_reference_golden.sh` (referenced from CLAUDE.md commands), `run_kelheim_extraction.cmd` (active Kelheim driver).
- `.planning/`: `README.md`, `phase-8-hyperpool.md`.

## Recovering something from archive

Everything is tracked in git, so move history is preserved.

```bash
git mv archive/<subdir>/<file> <original-location>/<file>
```

Or browse history with `git log --follow archive/<subdir>/<file>`.
