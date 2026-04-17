package org.matsim.contrib.demand_extraction.algorithm.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
	private final int coverageK;
	private final QualityMetric metric;

	private PostExtensionPruner(Mode mode, double keepTopFraction, int coverageK, QualityMetric metric) {
		this.mode = mode;
		this.keepTopFraction = keepTopFraction;
		this.coverageK = coverageK;
		this.metric = metric;
	}

	/** Legacy: keep top {@code keepTopFraction} of each degree's rides by savingsRatio. */
	public static PostExtensionPruner ratioThreshold(double keepTopFraction) {
		return new PostExtensionPruner(Mode.RATIO_THRESHOLD, keepTopFraction, 0, RATIO_SAVINGS);
	}

	/** Coverage-aware: per-request top-K by quality metric. */
	public static PostExtensionPruner coverageTopK(int K, QualityMetric metric) {
		if (K < 1) throw new IllegalArgumentException("coverage K must be >= 1, got: " + K);
		if (metric == null) throw new IllegalArgumentException("metric must not be null");
		return new PostExtensionPruner(Mode.COVERAGE_TOPK, 1.0, K, metric);
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
		log.info("Post-extension pruning (COVERAGE_TOPK): {} rides input (K={}, metric={})",
				rides.size(), coverageK, metricName(metric));

		Map<Integer, List<Ride>> byDegree = groupByDegree(rides);
		List<Ride> kept = new ArrayList<>();

		for (Map.Entry<Integer, List<Ride>> entry : byDegree.entrySet()) {
			int degree = entry.getKey();
			List<Ride> group = entry.getValue();

			if (degree <= 1) {
				kept.addAll(group);
				continue;
			}

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
					if (cov[r] < coverageK) { hit = true; break; }
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

			log.info("  Degree {}: kept {}/{} ({}), {} requests covered",
					degree, keptAtDegree, group.size(),
					String.format("%.1f%%", keptAtDegree * 100.0 / group.size()),
					requestsCovered);
		}

		log.info("Post-extension pruning complete: {} -> {} rides ({} removed, {} reduction)",
				rides.size(), kept.size(), rides.size() - kept.size(),
				String.format("%.1f%%", (1.0 - (double) kept.size() / rides.size()) * 100));

		return kept;
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
