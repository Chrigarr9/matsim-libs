package org.matsim.contrib.demand_extraction.scenarios;

import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.Algorithm;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.PruningMode;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

/**
 * Declarative algorithm + pruning knobs for the Paper 1 R1/R2/R3/R4 matrix.
 * Profiles form a strict-subset progression so the Lyon C3 ablation can attribute
 * the marginal effect of each pruning mechanism: R2 ⊂ R3 ⊂ R4 (by enabled gates).
 *
 * <ul>
 *   <li><b>R1</b> = vanilla ExMAS (frozen reference port). EXMAS strategy ignores
 *       the BAMAS-side pruning knobs entirely; we still null them out for clarity.</li>
 *   <li><b>R2</b> = BAMAS without pruning. Both gates off — heuristic per-degree
 *       distance-savings filter disabled, and post-extension pruner disabled by
 *       choosing RATIO_THRESHOLD with interDegreeKeepFraction=1.0 (the only "off"
 *       state currently expressible via the config; COVERAGE_TOPK always builds
 *       a pruner).</li>
 *   <li><b>R3</b> = BAMAS distance-pruning ablation: heuristic distance gate ON,
 *       post-extension pruner OFF. Sits between R2 (no pruning) and R4 (full
 *       production pruning) so the layered C3 ablation can isolate the in-DFS
 *       distance gate's contribution.</li>
 *   <li><b>R4</b> = BAMAS with the production-default pruning (heuristic distance
 *       gate ON + post-extension COVERAGE_TOPK with K=20 — Pareto-minimal per
 *       the 2026-04-17 cascade analysis). This is the profile fed to the Python
 *       MIP optimiser.</li>
 * </ul>
 *
 * <p>R1 is "vanilla" regardless of what {@code main}'s runner defaults happen
 * to be — see {@code .project-memory/exmas-reference-pruner-default-2026-04-21.md}.
 *
 * <p>{@code calcPredecessors}: predecessor/successor ride-sequencing edges are
 * only needed by the Python optimisation pipeline (R4 production). R1/R2/R3 are
 * demand-comparison / ablation profiles — skip the expensive per-ride routing step.
 *
 * <p>{@code maxPoolingDegree}: all profiles are uncapped (Integer.MAX_VALUE). With
 * maxDetourFactor=1.3, feasible-pair density drops enough that ExMAS does not OOM.
 * R4 inherits the fixture default via the uncapped value.
 *
 * <p>Naming history: the labels were swapped on 2026-04-28 (paper-side first,
 * code-side now) so the enum matches the paper's strict-subset presentation
 * order. Pre-2026-04-28 R3 ≡ post-2026-04-28 R4 (production); pre-2026-04-28 R4 ≡
 * post-2026-04-28 R3 (heuristic-only ablation). Old run artefacts on disk under
 * "R3" / "R4" directory names refer to the pre-swap meanings.
 */
public record AlgorithmProfile(
		String label,
		Algorithm algorithm,
		boolean heuristicPruningEnabled,
		boolean postExtensionPruningEnabled,
		boolean calcPredecessors,
		int maxPoolingDegree
) {
	public static final AlgorithmProfile R1 =
			new AlgorithmProfile("R1", Algorithm.EXMAS, false, false, false, Integer.MAX_VALUE);
	public static final AlgorithmProfile R2 =
			new AlgorithmProfile("R2", Algorithm.BAMAS, false, false, false, Integer.MAX_VALUE);
	public static final AlgorithmProfile R3 =
			new AlgorithmProfile("R3", Algorithm.BAMAS, true, false, false, Integer.MAX_VALUE);
	public static final AlgorithmProfile R4 =
			new AlgorithmProfile("R4", Algorithm.BAMAS, true, true, true, Integer.MAX_VALUE);

	/**
	 * Production-default heuristic distance-savings log-scale (matches
	 * {@code LyonEqasimScenarioFixture} and {@code RunBavaria*}). Used when
	 * {@code heuristicPruningEnabled=true} to make {@link #apply} idempotent —
	 * otherwise sequential profile applications on the same {@link Config} (e.g.
	 * R2 → R3 in the Lyon comparison test) would leave the gate disabled because
	 * R2 sets scale=-1 and R3 never restores it. {@code RunKelheimDemandExtraction}
	 * uses 0.25 outside this profile path.
	 */
	private static final double HEURISTIC_SCALE_PRODUCTION_DEFAULT = 0.15;

	/** Production-default top-K coverage (matches {@code ExMasConfigGroup} default). */
	private static final int COVERAGE_TOPK_PRODUCTION_DEFAULT = 20;

	/**
	 * Apply this profile to {@code config}. Idempotent across sequential calls on
	 * the same {@link Config}: every relevant pruning knob is written in BOTH the
	 * enabled and disabled directions, so a later {@code R3.apply()} fully
	 * recovers from an earlier {@code R2.apply()} (and vice-versa). Other ExMAS
	 * settings are left intact so scenario-fixture domain choices survive.
	 */
	public void apply(Config config) {
		ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		exMas.setAlgorithm(algorithm);
		exMas.setHeuristicPruningEnabled(heuristicPruningEnabled);
		// BamasEngine's pair-level distance-savings gate condition is `scale >= 0`.
		// scale=-1 disables; scale>=0 enables (production default 0.15).
		exMas.setPruningDistanceSavingsLogScale(
				heuristicPruningEnabled ? HEURISTIC_SCALE_PRODUCTION_DEFAULT : -1.0);
		if (postExtensionPruningEnabled) {
			exMas.setPruningMode(PruningMode.COVERAGE_TOPK);
			exMas.setPruningCoverageK(COVERAGE_TOPK_PRODUCTION_DEFAULT);
		} else {
			exMas.setPruningMode(PruningMode.RATIO_THRESHOLD);
			exMas.setInterDegreeKeepFraction(1.0);
		}
		exMas.setCalcPredecessors(calcPredecessors);
		exMas.setMaxPoolingDegree(maxPoolingDegree);
	}

	@Override
	public String toString() {
		return label;
	}
}
