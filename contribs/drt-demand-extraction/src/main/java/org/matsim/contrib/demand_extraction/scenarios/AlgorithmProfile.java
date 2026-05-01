package org.matsim.contrib.demand_extraction.scenarios;

import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.Algorithm;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.PruningMode;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

/**
 * Declarative algorithm + pruning knobs for the Paper 1 R1–R6 matrix.
 * Profiles form a strict-subset progression enabling clean ablation of each mechanism.
 *
 * <ul>
 *   <li><b>R1</b> = vanilla ExMAS (frozen reference port). No pruning.</li>
 *   <li><b>R2</b> = BAMAS, no pruning. Full enumeration baseline.</li>
 *   <li><b>R3</b> = BAMAS + heuristic distance gate (scale=0.15). Mild filtering;
 *       cascade reaches d12 on Lyon 10%. Isolates the gate mechanism from R2.</li>
 *   <li><b>R4</b> = BAMAS + distance gate (scale=0.25). Sweet-spot tightening:
 *       94% ride-count reduction vs R3, d10 cascade, 83% request coverage.
 *       Isolates scale sensitivity: R3 (0.15) vs R4 (0.25) vs R5 (0.30).</li>
 *   <li><b>R5</b> = BAMAS + distance gate (scale=0.30). Over-pruning ceiling:
 *       kills d8+, 64% request coverage — shows the cliff beyond scale=0.25.</li>
 *   <li><b>R6</b> = BAMAS + distance gate (scale=0.25) + post-extension
 *       COVERAGE_TOPK K=20. Production profile fed to the Python MIP optimiser.
 *       Adds the final compression layer on top of R4.</li>
 * </ul>
 *
 * <p>Gate formula: {@code requiredSaving(d) = min(max, max(0, heuristicScale * log2(d)))}.
 * A ride at degree d is kept iff
 * {@code rideDistance <= (1 - requiredSaving(d)) * sum(request.directDistance)}.
 * The {@code max} and {@code minDegree} parameters come from
 * {@link LyonEqasimScenarioFixture} / {@link KelheimScenarioFixture} (max=0.75,
 * minDegree=2). Sweep analysis showed max is irrelevant at scale=0.25: the cap is
 * not reached for any practical degree (d<=12). See
 * {@code .project-memory/lyon-dist-gate-sweep-2026-05-01.md}.
 *
 * <p>{@code calcPredecessors}: predecessor/successor ride-sequencing edges are
 * only needed by the Python optimisation pipeline (R6 production). R1–R5 are
 * demand-comparison / ablation profiles — skip the expensive per-ride routing step.
 *
 * <p>{@code maxPoolingDegree}: all profiles are uncapped (Integer.MAX_VALUE).
 *
 * <p>Naming history: labels were swapped on 2026-04-28 (R3↔R4); then on
 * 2026-05-01 old R4 (scale=0.15+K20) was split into R4 (scale=0.25, no K),
 * R5 (scale=0.30, no K), and R6 (scale=0.25+K20). Old run artefacts on disk
 * labelled "R4" refer to different configurations depending on date.
 */
public record AlgorithmProfile(
		String label,
		Algorithm algorithm,
		boolean heuristicPruningEnabled,
		double heuristicScale,
		boolean postExtensionPruningEnabled,
		int coverageK,
		boolean calcPredecessors,
		int maxPoolingDegree
) {
	public static final AlgorithmProfile R1 =
			new AlgorithmProfile("R1", Algorithm.EXMAS, false, -1, false, 0, false, Integer.MAX_VALUE);
	public static final AlgorithmProfile R2 =
			new AlgorithmProfile("R2", Algorithm.BAMAS, false, -1, false, 0, false, Integer.MAX_VALUE);
	public static final AlgorithmProfile R3 =
			new AlgorithmProfile("R3", Algorithm.BAMAS, true, 0.15, false, 0, false, Integer.MAX_VALUE);
	public static final AlgorithmProfile R4 =
			new AlgorithmProfile("R4", Algorithm.BAMAS, true, 0.25, false, 0, false, Integer.MAX_VALUE);
	public static final AlgorithmProfile R5 =
			new AlgorithmProfile("R5", Algorithm.BAMAS, true, 0.30, false, 0, false, Integer.MAX_VALUE);
	public static final AlgorithmProfile R6 =
			new AlgorithmProfile("R6", Algorithm.BAMAS, true, 0.25, true, 20, true, Integer.MAX_VALUE);

	/**
	 * Apply this profile to {@code config}. Idempotent across sequential calls on
	 * the same {@link Config}: every relevant pruning knob is written in BOTH the
	 * enabled and disabled directions, so a later {@code R4.apply()} fully
	 * recovers from an earlier {@code R2.apply()} (and vice-versa). Other ExMAS
	 * settings are left intact so scenario-fixture domain choices survive.
	 */
	public void apply(Config config) {
		ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		exMas.setAlgorithm(algorithm);
		exMas.setHeuristicPruningEnabled(heuristicPruningEnabled);
		// BamasEngine's distance-savings gate condition is `scale >= 0`.
		// scale=-1 disables; scale>=0 enables with the per-profile value.
		exMas.setPruningDistanceSavingsLogScale(
				heuristicPruningEnabled ? heuristicScale : -1.0);
		if (postExtensionPruningEnabled) {
			exMas.setPruningMode(PruningMode.COVERAGE_TOPK);
			exMas.setPruningCoverageK(coverageK);
			exMas.clearPruningCoverageKByDegree();
		} else {
			exMas.setPruningMode(PruningMode.RATIO_THRESHOLD);
			exMas.setInterDegreeKeepFraction(1.0);
			exMas.clearPruningCoverageKByDegree();
		}
		exMas.setCalcPredecessors(calcPredecessors);
		exMas.setMaxPoolingDegree(maxPoolingDegree);
	}

	@Override
	public String toString() {
		return label;
	}
}
