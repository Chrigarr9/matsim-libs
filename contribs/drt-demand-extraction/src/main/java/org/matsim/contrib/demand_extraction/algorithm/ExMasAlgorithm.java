package org.matsim.contrib.demand_extraction.algorithm;

import java.util.List;

import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Strategy interface for the Stage-1 algorithm that turns DRT requests into
 * a list of feasible shared rides. Two implementations co-exist, selected by
 * {@link org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.Algorithm}:
 * <ul>
 *   <li>{@code BAMAS} — current Budget-Aware Matching of Autonomous Shared-rides.</li>
 *   <li>{@code EXMAS} — reference ExMAS algorithm ported from {@code main}, frozen.</li>
 * </ul>
 * The shared input pipeline (scoring, budget computation, request construction)
 * feeds both strategies identically; downstream post-processing (pruning, CSV
 * output, HyperPool) also acts on {@link AlgorithmResult#rides()} uniformly.
 */
public interface ExMasAlgorithm {
	AlgorithmResult run(List<DrtRequest> requests);
}
