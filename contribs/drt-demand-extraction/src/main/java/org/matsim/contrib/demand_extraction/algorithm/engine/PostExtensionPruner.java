package org.matsim.contrib.demand_extraction.algorithm.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.OrderingCodec;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubScaling;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Inter-degree pruning between extension stages. Two modes:
 * <ul>
 *   <li>{@link Mode#RATIO_THRESHOLD} — legacy: per-degree top fraction by savingsRatio.</li>
 *   <li>{@link Mode#COVERAGE_TOPK}   — per-request top-K by quality metric; every request
 *       keeps up to K options per degree. Dominates legacy on retention at smaller DB
 *       (see {@code .project-memory/pruning-quality-analysis-2026-04-17.md}).</li>
 * </ul>
 * Singles (degree 1) are always passed through unchanged.
 */
public final class PostExtensionPruner {
	private static final Logger log = LogManager.getLogger(PostExtensionPruner.class);

	public enum Mode { RATIO_THRESHOLD, COVERAGE_TOPK }

	/** Quality metric used to rank rides inside a pruning pass. */
	public interface QualityMetric {
		double of(Ride ride, double sumDirectDistance);
	}

	/** Absolute distance saved (meters). Grows with degree. */
	public static final QualityMetric ABS_SAVINGS =
			(ride, sumDirect) -> sumDirect - ride.getRideDistance();

	/** Fractional savings. Degree-invariant. */
	public static final QualityMetric RATIO_SAVINGS =
			(ride, sumDirect) -> sumDirect > 0 ? 1.0 - ride.getRideDistance() / sumDirect : 0.0;

	private final Mode mode;
	private final double keepTopFraction;
	private final IntUnaryOperator kFunction;
	private final QualityMetric metric;

	private PostExtensionPruner(Mode mode, double keepTopFraction, IntUnaryOperator kFunction, QualityMetric metric) {
		this.mode = mode;
		this.keepTopFraction = keepTopFraction;
		this.kFunction = kFunction;
		this.metric = metric;
	}

	/** Legacy: keep top {@code keepTopFraction} of each degree's rides by savingsRatio. */
	public static PostExtensionPruner ratioThreshold(double keepTopFraction) {
		return new PostExtensionPruner(Mode.RATIO_THRESHOLD, keepTopFraction, null, RATIO_SAVINGS);
	}

	/** Coverage-aware: flat K applied to all degrees. */
	public static PostExtensionPruner coverageTopK(int K, QualityMetric metric) {
		if (K < 1) throw new IllegalArgumentException("coverage K must be >= 1, got: " + K);
		if (metric == null) throw new IllegalArgumentException("metric must not be null");
		return new PostExtensionPruner(Mode.COVERAGE_TOPK, 1.0, d -> K, metric);
	}

	/** Coverage-aware: per-degree K supplied by {@code kFunction}. */
	public static PostExtensionPruner coverageTopK(IntUnaryOperator kFunction, QualityMetric metric) {
		if (kFunction == null) throw new IllegalArgumentException("kFunction must not be null");
		if (metric == null) throw new IllegalArgumentException("metric must not be null");
		return new PostExtensionPruner(Mode.COVERAGE_TOPK, 1.0, kFunction, metric);
	}

	public Mode getMode() {
		return mode;
	}

	public List<Ride> prune(List<Ride> rides) {
		if (rides == null || rides.isEmpty()) {
			return rides;
		}
		return switch (mode) {
			case RATIO_THRESHOLD -> pruneRatioThreshold(rides);
			case COVERAGE_TOPK -> pruneCoverageTopK(rides);
		};
	}

	// --- RATIO_THRESHOLD (legacy) ---------------------------------------------

	private List<Ride> pruneRatioThreshold(List<Ride> rides) {
		if (keepTopFraction >= 1.0) {
			return rides;
		}
		log.info("Post-extension pruning (RATIO_THRESHOLD): {} rides input (keepTopFraction={})",
				rides.size(), keepTopFraction);

		Map<Integer, List<Ride>> byDegree = groupByDegree(rides);
		List<Ride> kept = new ArrayList<>();

		for (Map.Entry<Integer, List<Ride>> entry : byDegree.entrySet()) {
			int degree = entry.getKey();
			List<Ride> group = entry.getValue();

			if (degree <= 1) {
				kept.addAll(group);
				continue;
			}

			double[] savings = new double[group.size()];
			for (int i = 0; i < group.size(); i++) {
				Ride ride = group.get(i);
				double sumReqDist = sumDirectDistance(ride);
				savings[i] = sumReqDist > 0 ? 1.0 - ride.getRideDistance() / sumReqDist : 0;
			}

			double[] sorted = savings.clone();
			Arrays.sort(sorted);
			int thresholdIndex = (int) Math.floor(sorted.length * (1.0 - keepTopFraction));
			thresholdIndex = Math.min(thresholdIndex, sorted.length - 1);
			double threshold = sorted[thresholdIndex];

			int keptAtDegree = 0;
			for (int i = 0; i < group.size(); i++) {
				if (savings[i] >= threshold) {
					kept.add(group.get(i));
					keptAtDegree++;
				}
			}

			log.info("  Degree {}: threshold={}, kept {}/{} ({})",
					degree, String.format("%+.3f", threshold), keptAtDegree, group.size(),
					String.format("%.1f%%", keptAtDegree * 100.0 / group.size()));
		}

		log.info("Post-extension pruning complete: {} -> {} rides ({} removed, {} reduction)",
				rides.size(), kept.size(), rides.size() - kept.size(),
				String.format("%.1f%%", (1.0 - (double) kept.size() / rides.size()) * 100));

		return kept;
	}

	// --- COVERAGE_TOPK --------------------------------------------------------

	private List<Ride> pruneCoverageTopK(List<Ride> rides) {
		log.info("Post-extension pruning (COVERAGE_TOPK): {} rides input (metric={})",
				rides.size(), metricName(metric));

		Map<Integer, List<Ride>> byDegree = groupByDegree(rides);
		List<Ride> kept = new ArrayList<>();

		for (Map.Entry<Integer, List<Ride>> entry : byDegree.entrySet()) {
			int degree = entry.getKey();
			List<Ride> group = entry.getValue();

			if (degree <= 1) {
				kept.addAll(group);
				continue;
			}

			int effectiveK = kFunction.applyAsInt(degree);

			// Pre-compute quality per ride.
			double[] quality = new double[group.size()];
			for (int i = 0; i < group.size(); i++) {
				Ride ride = group.get(i);
				quality[i] = metric.of(ride, sumDirectDistance(ride));
			}

			// Sort indices by quality descending.
			Integer[] order = new Integer[group.size()];
			for (int i = 0; i < order.length; i++) order[i] = i;
			Arrays.sort(order, (a, b) -> Double.compare(quality[b], quality[a]));

			// Per-request coverage counter. Dense int[] indexed by request.index;
			// per-degree reset matches the Java cascade (each degree's pruner is
			// isolated from other degrees' retention state).
			int maxIdx = -1;
			for (Ride ride : group) {
				int deg = ride.getDegree();
				for (int j = 0; j < deg; j++) {
					int idx = ride.getRequest(j).index;
					if (idx > maxIdx) maxIdx = idx;
				}
			}
			int[] cov = new int[maxIdx + 1];

			int keptAtDegree = 0;
			int requestsCovered = 0;
			for (int idx : order) {
				Ride ride = group.get(idx);
				int deg = ride.getDegree();
				boolean hit = false;
				for (int j = 0; j < deg; j++) {
					int r = ride.getRequest(j).index;
					if (cov[r] < effectiveK) { hit = true; break; }
				}
				if (hit) {
					kept.add(ride);
					keptAtDegree++;
					for (int j = 0; j < deg; j++) {
						int r = ride.getRequest(j).index;
						if (cov[r] == 0) requestsCovered++;
						cov[r]++;
					}
				}
			}

			log.info("  Degree {}: kept {}/{} ({}) K={}, {} requests covered",
					degree, keptAtDegree, group.size(),
					String.format("%.1f%%", keptAtDegree * 100.0 / group.size()),
					effectiveK, requestsCovered);
		}

		log.info("Post-extension pruning complete: {} -> {} rides ({} removed, {} reduction)",
				rides.size(), kept.size(), rides.size() - kept.size(),
				String.format("%.1f%%", (1.0 - (double) kept.size() / rides.size()) * 100));

		return kept;
	}

	// --- stub-mode pruning (seam b) -------------------------------------------

	/**
	 * Prune one per-degree {@link StubColumns} layer in stub mode, returning a new
	 * layer containing only the survivors in their original (lex) row order.
	 *
	 * <p>This is the byte-exact stub analogue of {@link #prune(List)} restricted to a
	 * single degree. It computes the same quality metrics off stub-derived values:
	 * ride distance via {@link StubScaling#fromDeci} (bit-identical to
	 * {@code Ride.getRideDistance()}; Task 4) and {@code sumDirectDistance} summed in
	 * PICKUP order (FP addition is non-commutative; the fat path sums {@code requests[]}
	 * which IS pickup order). Survivors are appended in row order, which equals the fat
	 * path's per-degree group order, so the COVERAGE_TOPK stable-sort tie-break resolves
	 * identically.
	 *
	 * <p>Degree-1 layers are returned unchanged (singles are never pruned). In stub mode
	 * singles stay fat, so this is only ever called with degree-3+ layers, but the guard
	 * is kept for symmetry with {@link #prune(List)}.
	 *
	 * @param layer        one degree's winning rides as a SoA container
	 * @param requestById  global request lookup keyed by {@link DrtRequest#index},
	 *                     built identically to the extender's {@code requestMap}.
	 *                     Positional indexing is NOT safe: Paper-2 Extension-2 hub
	 *                     expansion emits virtual copies sharing the parent's
	 *                     {@code index}, so the fat path resolves direct distances
	 *                     through this same last-write-wins map.
	 * @return a filtered {@link StubColumns} (survivors only), same degree, lex order
	 */
	public StubColumns pruneStubLayer(StubColumns layer, Map<Integer, DrtRequest> requestById) {
		if (layer == null || layer.size() == 0 || layer.degree() <= 1) {
			return layer;
		}
		return switch (mode) {
			case RATIO_THRESHOLD -> pruneStubRatioThreshold(layer, requestById);
			case COVERAGE_TOPK -> pruneStubCoverageTopK(layer, requestById);
		};
	}

	private StubColumns pruneStubRatioThreshold(StubColumns layer, Map<Integer, DrtRequest> requestById) {
		if (keepTopFraction >= 1.0) {
			return layer;
		}
		int n = layer.size();
		int degree = layer.degree();

		// RATIO_THRESHOLD always ranks by fractional savings (mirrors the fat path,
		// which hardcodes savingsRatio here regardless of the configured metric).
		double[] savings = new double[n];
		for (int i = 0; i < n; i++) {
			double sumDirect = sumDirectDistanceStub(layer, i, requestById);
			double rideDist = StubScaling.fromDeci(layer.rideDistanceDm(i));
			savings[i] = sumDirect > 0 ? 1.0 - rideDist / sumDirect : 0;
		}

		double[] sorted = savings.clone();
		Arrays.sort(sorted);
		int thresholdIndex = (int) Math.floor(sorted.length * (1.0 - keepTopFraction));
		thresholdIndex = Math.min(thresholdIndex, sorted.length - 1);
		double threshold = sorted[thresholdIndex];

		StubColumns kept = new StubColumns(degree);
		int keptAtDegree = 0;
		for (int i = 0; i < n; i++) {
			if (savings[i] >= threshold) {
				copyRow(layer, i, kept);
				keptAtDegree++;
			}
		}
		log.info("Post-extension pruning (RATIO_THRESHOLD, stub): degree {} threshold={}, kept {}/{}",
				degree, String.format("%+.3f", threshold), keptAtDegree, n);
		return kept;
	}

	private StubColumns pruneStubCoverageTopK(StubColumns layer, Map<Integer, DrtRequest> requestById) {
		int n = layer.size();
		int degree = layer.degree();
		int effectiveK = kFunction.applyAsInt(degree);

		// Quality per row, computed in stored (lex) row order so the stable descending
		// sort below resolves equal-quality ties by lex order — identical to the fat
		// path, where the per-degree group is iterated in the extender's sorted order.
		double[] quality = new double[n];
		for (int i = 0; i < n; i++) {
			double sumDirect = sumDirectDistanceStub(layer, i, requestById);
			double rideDist = StubScaling.fromDeci(layer.rideDistanceDm(i));
			quality[i] = metricValue(rideDist, sumDirect);
		}

		// Stable sort of row indices by quality descending. Integer[] + Arrays.sort is a
		// stable mergesort, so ties keep ascending row (= lex) order — matches the fat
		// path's Arrays.sort(order, byQualityDesc).
		Integer[] order = new Integer[n];
		for (int i = 0; i < n; i++) order[i] = i;
		Arrays.sort(order, (a, b) -> Double.compare(quality[b], quality[a]));

		// Per-request coverage counter, indexed by request.index (same as fat path).
		int maxIdx = -1;
		for (int i = 0; i < n; i++) {
			for (int g : layer.requestIndices(i)) {
				if (g > maxIdx) maxIdx = g;
			}
		}
		int[] cov = new int[maxIdx + 1];

		// Mark survivors, then emit in `order` (quality-descending, lex tie-break) — NOT
		// row order. This must mirror the fat path's pruneCoverageTopK, which builds `kept`
		// via `for (idx : order) kept.add(...)`, i.e. survivors in quality-descending order.
		// The engine's final sort (variant, degree, first-PICKUP index) is STABLE and only
		// ties-breaks on the first-pickup index, so within a tie group it preserves the
		// pruner's emission order. With Paper-2 Ext-2 hub copies, many degree-3/4 rides share
		// a first-pickup index, so emitting row (lex) order here instead of quality order
		// flips ~21 tied rides at the tail vs the fat golden (byte drift, identical multiset).
		boolean[] keepRow = new boolean[n];
		int keptAtDegree = 0;
		int requestsCovered = 0;
		for (int idx : order) {
			int[] reqs = layer.requestIndices(idx);
			boolean hit = false;
			for (int r : reqs) {
				if (cov[r] < effectiveK) { hit = true; break; }
			}
			if (hit) {
				keepRow[idx] = true;
				keptAtDegree++;
				for (int r : reqs) {
					if (cov[r] == 0) requestsCovered++;
					cov[r]++;
				}
			}
		}

		StubColumns kept = new StubColumns(degree);
		for (int idx : order) {
			if (keepRow[idx]) copyRow(layer, idx, kept);
		}
		log.info("Post-extension pruning (COVERAGE_TOPK, stub): degree {} kept {}/{} K={}, {} requests covered",
				degree, keptAtDegree, n, effectiveK, requestsCovered);
		return kept;
	}

	/** Sum of per-passenger direct distances in PICKUP order (FP non-commutative). */
	private static double sumDirectDistanceStub(StubColumns layer, int row, Map<Integer, DrtRequest> requestById) {
		int degree = layer.degree();
		int[] sortedSet = layer.requestIndices(row);
		int[] originsLocal = OrderingCodec.unpack(layer.originOrder(row), degree);
		double sum = 0;
		for (int i = 0; i < degree; i++) {
			sum += requestById.get(sortedSet[originsLocal[i]]).getDistance();
		}
		return sum;
	}

	/**
	 * Quality value from stub-derived primitives, mirroring {@link #ABS_SAVINGS} /
	 * {@link #RATIO_SAVINGS}. The metric instances only read {@code ride.getRideDistance()}
	 * and {@code sumDirect}, both available here directly.
	 */
	private double metricValue(double rideDistance, double sumDirect) {
		if (metric == ABS_SAVINGS) {
			return sumDirect - rideDistance;
		}
		if (metric == RATIO_SAVINGS) {
			return sumDirect > 0 ? 1.0 - rideDistance / sumDirect : 0.0;
		}
		throw new IllegalStateException("metricValue: unsupported QualityMetric " + metric);
	}

	private static void copyRow(StubColumns src, int row, StubColumns dst) {
		dst.addRow(
				src.requestIndices(row),
				src.originOrder(row),
				src.destOrder(row),
				src.rideDistanceDm(row),
				src.travelTimeDs(row),
				src.flags(row));
	}

	// --- helpers --------------------------------------------------------------

	private static Map<Integer, List<Ride>> groupByDegree(List<Ride> rides) {
		Map<Integer, List<Ride>> byDegree = new HashMap<>();
		for (Ride ride : rides) {
			byDegree.computeIfAbsent(ride.getDegree(), k -> new ArrayList<>()).add(ride);
		}
		return byDegree;
	}

	private static double sumDirectDistance(Ride ride) {
		double sum = 0;
		int deg = ride.getDegree();
		for (int j = 0; j < deg; j++) {
			sum += ride.getRequest(j).getDistance();
		}
		return sum;
	}

	private static String metricName(QualityMetric m) {
		if (m == ABS_SAVINGS) return "ABS_SAVINGS";
		if (m == RATIO_SAVINGS) return "RATIO_SAVINGS";
		return "custom";
	}
}
