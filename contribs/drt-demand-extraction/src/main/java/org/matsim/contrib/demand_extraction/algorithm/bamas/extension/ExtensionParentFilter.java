package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import java.util.Map;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubScaling;
import org.matsim.contrib.demand_extraction.algorithm.engine.PostExtensionPruner;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Plan B glue between {@link ExtensionParentRanker} (pure selection) and the engine:
 * computes the per-stub quality metric for a degree-D parent layer, runs the ranker,
 * and returns a new {@link StubColumns} holding only the EXTEND-marked rows in their
 * original (lex) row order.
 *
 * <p>This is extracted from {@code BamasEngine} so the metric computation + filtered-layer
 * construction can be unit-tested without standing up the full engine. The input
 * {@code parents} layer is never mutated — the caller keeps using it unchanged for output
 * and the prior degree graph (only the producer parents for the next degree are restricted).
 *
 * <p>{@code k <= 0} returns a copy containing every row (exact / off).
 */
public final class ExtensionParentFilter {

	private ExtensionParentFilter() { /* non-instantiable */ }

	/**
	 * @param parents       degree-D parent stubs (unmodified)
	 * @param requestById   global request index → request (for direct distances)
	 * @param k             per-request top-K; {@code k <= 0} keeps everything
	 * @param metricKind    quality metric (higher = better after conversion)
	 * @param ruleKind      TOP_K or MMR selection
	 * @param mmrLambda     MMR diversity penalty (0 ≡ TOP_K)
	 * @return a new {@link StubColumns} with only the marked rows, in ascending row order
	 */
	public static StubColumns filter(
			StubColumns parents,
			Map<Integer, DrtRequest> requestById,
			int k,
			ExMasConfigGroup.PruningQualityMetric metricKind,
			ExMasConfigGroup.ExtensionParentsSelectionRule ruleKind,
			double mmrLambda) {

		int n = parents.size();
		int degree = parents.degree();

		int[][] requestSets = new int[n][];
		double[] metric = new double[n];
		for (int row = 0; row < n; row++) {
			requestSets[row] = parents.requestIndices(row);
			double sumDirect = PostExtensionPruner.sumDirectDistanceStub(parents, row, requestById);
			double rideDist = StubScaling.fromDeci(parents.rideDistanceDm(row));
			metric[row] = switch (metricKind) {
				case ABS_SAVINGS -> sumDirect - rideDist;
				case RATIO_SAVINGS -> sumDirect > 0 ? 1.0 - rideDist / sumDirect : 0.0;
				// higher = cheaper per passenger = better (the ranker wants higher = better)
				case OP_COST_PER_PAX -> -(rideDist / degree);
			};
		}

		ExtensionParentRanker.SelectionRule rule =
				ruleKind == ExMasConfigGroup.ExtensionParentsSelectionRule.MMR
						? ExtensionParentRanker.SelectionRule.MMR
						: ExtensionParentRanker.SelectionRule.TOP_K;
		IntOpenHashSet marked = ExtensionParentRanker.markExtend(requestSets, metric, k, rule, mmrLambda);

		StubColumns filtered = new StubColumns(degree);
		for (int row = 0; row < n; row++) {
			if (marked.contains(row)) {
				filtered.addRow(parents.requestIndices(row), parents.originOrder(row),
						parents.destOrder(row), parents.rideDistanceDm(row),
						parents.travelTimeDs(row), parents.flags(row));
			}
		}
		return filtered;
	}
}
