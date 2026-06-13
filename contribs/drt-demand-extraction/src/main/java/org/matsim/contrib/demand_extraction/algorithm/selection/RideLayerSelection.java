package org.matsim.contrib.demand_extraction.algorithm.selection;

import java.util.Arrays;
import java.util.Map;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.OrderingCodec;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideLayer;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideMetricScaling;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Adapter between the primitive {@link RideSelector} engine and a stub-mode
 * {@link RideLayer} layer. Computes per-row quality metrics off stub-derived values
 * (ride distance via {@link RideMetricScaling#fromDeci}, {@code sumDirectDistance} summed in
 * PICKUP order through the index-collision-safe {@code requestById} map), runs the
 * selector, and rebuilds a filtered {@link RideLayer} of the survivors.
 *
 * <p>This replaces the stub methods of the former {@code PostExtensionPruner}
 * ({@code pruneLayer}/{@code pruneCoverageTopK}/{@code pruneRatioThreshold}) and
 * {@code ExtensionParentFilter}. The selection rule logic now lives once in
 * {@link RideSelector}; this class owns only the stub I/O and the per-site emission order.
 *
 * <p><b>Emission order is byte-significant and differs per site</b> — the engine's final
 * sort (variant, degree, first-pickup index) is stable and only tie-breaks on first-pickup
 * index, so the order survivors are appended here leaks into the {@code exmas_rides} bytes
 * within each tie group. Each method preserves the order its predecessor produced:
 * <ul>
 *   <li>{@link #prune} COVERAGE_TOPK — survivors in {@link SelectionTieBreak} order
 *       (quality descending, lex-smaller set, lower row); matches the former
 *       {@code pruneCoverageTopK} emission.</li>
 *   <li>{@link #prune} RATIO_THRESHOLD and {@link #filterParents} — survivors in ascending
 *       row order; matches the former {@code pruneRatioThreshold} and
 *       {@code ExtensionParentFilter}.</li>
 * </ul>
 */
public final class RideLayerSelection {

	private RideLayerSelection() { /* non-instantiable */ }

	// === post-extension / inter-degree pruning (replaces PostExtensionPruner.pruneLayer) ===

	/**
	 * Prune one per-degree layer per {@code cfg}'s pruning mode, returning a new layer of
	 * survivors. Degree-1 (and empty) layers are returned unchanged. RATIO_THRESHOLD with
	 * {@code keepTopFraction >= 1.0} is a no-op pass-through (matches the old
	 * {@code buildPruner} returning null).
	 */
	public static RideLayer prune(RideLayer layer, Map<Integer, DrtRequest> requestById,
			ExMasConfigGroup cfg) {
		if (layer == null || layer.size() == 0 || layer.degree() <= 1) {
			return layer;
		}
		return switch (cfg.getPruningMode()) {
			case RATIO_THRESHOLD -> pruneRatioThreshold(layer, requestById, cfg);
			case COVERAGE_TOPK   -> pruneCoverageTopK(layer, requestById, cfg);
		};
	}

	private static RideLayer pruneCoverageTopK(RideLayer layer,
			Map<Integer, DrtRequest> requestById, ExMasConfigGroup cfg) {
		int n = layer.size();
		int degree = layer.degree();
		int effectiveK = effectiveCoverageK(cfg, degree);

		int[][] sets = new int[n][];
		double[] quality = new double[n];
		for (int i = 0; i < n; i++) {
			sets[i] = layer.requestIndices(i);
			double sumDirect = sumDirectDistanceRow(layer, i, requestById);
			double rideDist = RideMetricScaling.fromDeci(layer.rideDistanceDm(i));
			quality[i] = pruneMetric(cfg.getPruningQualityMetric(), rideDist, sumDirect);
		}

		IntOpenHashSet kept = RideSelector.select(sets, quality,
				SelectionRule.COVERAGE_TOPK, effectiveK, 0.0);

		// Emit survivors in SelectionTieBreak order (quality desc, lex tie, row tie) — NOT
		// stored row order. This mirrors the former pruneCoverageTopK, which emitted in
		// `order` (quality descending). The stable final engine sort preserves this within a
		// first-pickup-index tie group, so the emission order is byte-significant.
		Integer[] order = new Integer[n];
		for (int i = 0; i < n; i++) order[i] = i;
		Arrays.sort(order, SelectionTieBreak.comparator(quality, sets));

		RideLayer out = new RideLayer(degree);
		for (int row : order) {
			if (kept.contains(row)) copyRow(layer, row, out);
		}
		return out;
	}

	private static RideLayer pruneRatioThreshold(RideLayer layer,
			Map<Integer, DrtRequest> requestById, ExMasConfigGroup cfg) {
		double keepTopFraction = cfg.getInterDegreeKeepFraction();
		if (keepTopFraction >= 1.0) {
			return layer; // no-op pass-through
		}
		int n = layer.size();
		int degree = layer.degree();

		// RATIO_THRESHOLD always ranks by fractional savings (mirrors the old fat path, which
		// hardcoded savingsRatio here regardless of the configured quality metric). Threshold by
		// the floor index — kept iff savings >= sorted[floor(n*(1-frac))]; survivors emit in
		// ascending row order, matching the former pruneRatioThreshold.
		double[] savings = new double[n];
		for (int i = 0; i < n; i++) {
			double sumDirect = sumDirectDistanceRow(layer, i, requestById);
			double rideDist = RideMetricScaling.fromDeci(layer.rideDistanceDm(i));
			savings[i] = sumDirect > 0 ? 1.0 - rideDist / sumDirect : 0;
		}

		double[] sorted = savings.clone();
		Arrays.sort(sorted);
		int thresholdIndex = (int) Math.floor(sorted.length * (1.0 - keepTopFraction));
		thresholdIndex = Math.min(thresholdIndex, sorted.length - 1);
		double threshold = sorted[thresholdIndex];

		RideLayer out = new RideLayer(degree);
		for (int i = 0; i < n; i++) {
			if (savings[i] >= threshold) copyRow(layer, i, out);
		}
		return out;
	}

	// === extension-parent filtering (replaces ExtensionParentFilter.filter) ===

	/**
	 * Filter a degree-D parent layer to the per-request top-K rows (union across each row's
	 * member requests), returning a new layer with only the marked rows in ascending row order.
	 * {@code k <= 0} keeps everything. The input layer is never mutated.
	 */
	public static RideLayer filterParents(RideLayer parents,
			Map<Integer, DrtRequest> requestById, int k,
			ExMasConfigGroup.PruningQualityMetric metricKind,
			ExMasConfigGroup.ExtensionParentsSelectionRule ruleKind,
			double mmrLambda) {
		int n = parents.size();
		int degree = parents.degree();

		int[][] sets = new int[n][];
		double[] metric = new double[n];
		for (int row = 0; row < n; row++) {
			sets[row] = parents.requestIndices(row);
			double sumDirect = sumDirectDistanceRow(parents, row, requestById);
			double rideDist = RideMetricScaling.fromDeci(parents.rideDistanceDm(row));
			metric[row] = parentMetric(metricKind, rideDist, sumDirect, degree);
		}

		SelectionRule rule = ruleKind == ExMasConfigGroup.ExtensionParentsSelectionRule.MMR
				? SelectionRule.MMR
				: SelectionRule.PER_REQUEST_TOP_K;
		IntOpenHashSet marked = RideSelector.select(sets, metric, rule, k, mmrLambda);

		RideLayer filtered = new RideLayer(degree);
		for (int row = 0; row < n; row++) {
			if (marked.contains(row)) copyRow(parents, row, filtered);
		}
		return filtered;
	}

	// === metrics ===========================================================================

	/**
	 * Quality metric for the post-extension / inter-degree pruner. Mirrors the former
	 * {@code PostExtensionPruner.metricValue}: COVERAGE_TOPK consumes ABS_SAVINGS or
	 * RATIO_SAVINGS; OP_COST_PER_PAX falls back to ABS_SAVINGS (it is consumed only by the
	 * extension-parents ranker, see {@link #parentMetric}).
	 */
	private static double pruneMetric(ExMasConfigGroup.PruningQualityMetric kind,
			double rideDistance, double sumDirect) {
		return switch (kind) {
			case ABS_SAVINGS, OP_COST_PER_PAX -> sumDirect - rideDistance;
			case RATIO_SAVINGS -> sumDirect > 0 ? 1.0 - rideDistance / sumDirect : 0.0;
		};
	}

	/**
	 * Quality metric for the extension-parents ranker (higher = better). Mirrors the former
	 * {@code ExtensionParentFilter}: OP_COST_PER_PAX ranks by {@code -(rideDist / degree)}.
	 */
	private static double parentMetric(ExMasConfigGroup.PruningQualityMetric kind,
			double rideDistance, double sumDirect, int degree) {
		return switch (kind) {
			case ABS_SAVINGS -> sumDirect - rideDistance;
			case RATIO_SAVINGS -> sumDirect > 0 ? 1.0 - rideDistance / sumDirect : 0.0;
			case OP_COST_PER_PAX -> -(rideDistance / degree);
		};
	}

	private static int effectiveCoverageK(ExMasConfigGroup cfg, int degree) {
		Map<Integer, Integer> kByDegree = cfg.getPruningCoverageKByDegree();
		int defaultK = cfg.getPruningCoverageK();
		return kByDegree.isEmpty() ? defaultK : kByDegree.getOrDefault(degree, defaultK);
	}

	// === stub I/O ==========================================================================

	/**
	 * Sum of per-passenger direct distances in PICKUP order (FP addition is non-commutative,
	 * so the order must match the fat path, which sums {@code requests[]} in pickup order).
	 *
	 * <p>{@code requestById} MUST be the extender's last-write-wins index map (see
	 * {@code RequestResolver}): Paper-2 Extension-2 hub expansion emits virtual copies sharing
	 * a parent's index, so positional indexing would resolve a different copy → wrong distance.
	 */
	public static double sumDirectDistanceRow(RideLayer layer, int row,
			Map<Integer, DrtRequest> requestById) {
		int degree = layer.degree();
		int[] sortedSet = layer.requestIndices(row);
		int[] originsLocal = OrderingCodec.unpack(layer.originOrder(row), degree);
		double sum = 0;
		for (int i = 0; i < degree; i++) {
			sum += requestById.get(sortedSet[originsLocal[i]]).getDistance();
		}
		return sum;
	}

	private static void copyRow(RideLayer src, int row, RideLayer dst) {
		dst.addRow(
				src.requestIndices(row),
				src.originOrder(row),
				src.destOrder(row),
				src.rideDistanceDm(row),
				src.travelTimeDs(row),
				src.flags(row));
	}
}
