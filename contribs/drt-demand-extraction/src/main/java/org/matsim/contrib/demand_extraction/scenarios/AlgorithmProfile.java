package org.matsim.contrib.demand_extraction.scenarios;

import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.Algorithm;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.PruningMode;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

/**
 * Declarative algorithm + pruning knobs for the Paper 1 R1/R2/R3 matrix.
 *
 * <ul>
 *   <li><b>R1</b> = vanilla ExMAS (frozen reference port). EXMAS strategy ignores
 *       the BAMAS-side pruning knobs entirely; we still null them out for clarity.</li>
 *   <li><b>R2</b> = BAMAS without pruning. Both gates off — heuristic per-degree
 *       distance-savings filter disabled, and post-extension pruner disabled by
 *       choosing RATIO_THRESHOLD with interDegreeKeepFraction=1.0 (the only "off"
 *       state currently expressible via the config; COVERAGE_TOPK always builds
 *       a pruner).</li>
 *   <li><b>R3</b> = BAMAS with the production-default pruning (heuristic gate ON,
 *       COVERAGE_TOPK with K=20 — Pareto-minimal per the 2026-04-17 cascade
 *       analysis).</li>
 * </ul>
 *
 * <p>R1 is "vanilla" regardless of what {@code main}'s runner defaults happen
 * to be — see {@code .project-memory/exmas-reference-pruner-default-2026-04-21.md}.
 */
public record AlgorithmProfile(
		String label,
		Algorithm algorithm,
		boolean heuristicPruningEnabled,
		boolean postExtensionPruningEnabled
) {
	public static final AlgorithmProfile R1 =
			new AlgorithmProfile("R1", Algorithm.EXMAS, false, false);
	public static final AlgorithmProfile R2 =
			new AlgorithmProfile("R2", Algorithm.BAMAS, false, false);
	public static final AlgorithmProfile R3 =
			new AlgorithmProfile("R3", Algorithm.BAMAS, true, true);

	/**
	 * Apply this profile to {@code config}. Overrides only the algorithm + pruning
	 * knobs; all other ExMAS settings are left intact so each scenario fixture's
	 * domain choices survive.
	 */
	public void apply(Config config) {
		ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		exMas.setAlgorithm(algorithm);
		exMas.setHeuristicPruningEnabled(heuristicPruningEnabled);
		if (!postExtensionPruningEnabled) {
			exMas.setPruningMode(PruningMode.RATIO_THRESHOLD);
			exMas.setInterDegreeKeepFraction(1.0);
		}
	}

	@Override
	public String toString() {
		return label;
	}
}
