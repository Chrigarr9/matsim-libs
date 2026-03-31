package org.matsim.contrib.demand_extraction.algorithm.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Post-extension pruning: compresses the ride database after all extension degrees
 * are complete, before expensive post-processing (Shapley, predecessors).
 *
 * <p>Two sequential passes:
 * <ol>
 *   <li><b>MaxPerSet</b>: For each request set (same passenger group), keep only the
 *       N best variants (by rideDistance). This collapses redundant routing variants.</li>
 *   <li><b>Per-degree percentile</b>: Within each degree, compute the distance savings
 *       threshold at the configured percentile, and drop rides below it. This removes
 *       low-quality rides that the MIP would never select.</li>
 * </ol>
 *
 * <p>Singles (degree 1) are never pruned -- they are needed as fallback options.
 */
public final class PostExtensionPruner {
	private static final Logger log = LogManager.getLogger(PostExtensionPruner.class);

	private final ExMasConfigGroup config;
	private final int maxPerSetOverride;

	public PostExtensionPruner(ExMasConfigGroup config) {
		this.config = config;
		this.maxPerSetOverride = -1; // use config
	}

	/**
	 * MaxPerSet-only pruner (no percentile filter). Used for inter-degree pruning
	 * inside the extension loop to bound memory without cascade-killing higher degrees.
	 */
	public PostExtensionPruner(int maxPerSet) {
		this.config = null;
		this.maxPerSetOverride = maxPerSet;
	}

	/**
	 * Apply post-extension pruning. Returns a new list (does not modify the input).
	 */
	public List<Ride> prune(List<Ride> rides) {
		if (rides == null || rides.isEmpty()) {
			return rides;
		}

		int maxPerSet = maxPerSetOverride > 0 ? maxPerSetOverride
				: (config != null ? config.getPostExtensionMaxPerSet() : 0);
		double keepTopFraction = maxPerSetOverride > 0 ? 1.0
				: (config != null ? config.getPostExtensionKeepTopFraction() : 1.0);

		if (maxPerSet <= 0 && keepTopFraction >= 1.0) {
			log.info("Post-extension pruning: disabled (maxPerSet={}, keepTopFraction={})",
					maxPerSet, keepTopFraction);
			return rides;
		}

		log.info("Post-extension pruning: {} rides input (maxPerSet={}, keepTopFraction={})",
				rides.size(), maxPerSet <= 0 ? "off" : maxPerSet,
				keepTopFraction >= 1.0 ? "off" : keepTopFraction);

		List<Ride> result = rides;

		// Pass 1: MaxPerSet
		if (maxPerSet > 0) {
			result = applyMaxPerSet(result, maxPerSet);
		}

		// Pass 2: Per-degree percentile
		if (keepTopFraction < 1.0) {
			result = applyPerDegreePercentile(result, keepTopFraction);
		}

		log.info("Post-extension pruning complete: {} -> {} rides ({} removed, {} reduction)",
				rides.size(), result.size(), rides.size() - result.size(),
				String.format("%.1f%%", (1.0 - (double) result.size() / rides.size()) * 100));

		return result;
	}

	private List<Ride> applyMaxPerSet(List<Ride> rides, int maxPerSet) {
		// Group by request set key (sorted request indices)
		Map<String, List<Ride>> byRequestSet = new HashMap<>();
		List<Ride> singles = new ArrayList<>();

		for (Ride ride : rides) {
			if (ride.getDegree() <= 1) {
				singles.add(ride);
				continue;
			}
			int[] indices = ride.getRequestIndices().clone();
			Arrays.sort(indices);
			String key = Arrays.toString(indices);
			byRequestSet.computeIfAbsent(key, k -> new ArrayList<>()).add(ride);
		}

		List<Ride> kept = new ArrayList<>(singles);
		int totalGroups = byRequestSet.size();
		int prunedGroups = 0;

		for (List<Ride> group : byRequestSet.values()) {
			if (group.size() <= maxPerSet) {
				kept.addAll(group);
			} else {
				prunedGroups++;
				group.sort(Comparator.comparingDouble(Ride::getRideDistance));
				kept.addAll(group.subList(0, maxPerSet));
			}
		}

		log.info("  MaxPerSet={}: {} -> {} rides ({} request sets pruned of {})",
				maxPerSet, rides.size(), kept.size(), prunedGroups, totalGroups);
		return kept;
	}

	private List<Ride> applyPerDegreePercentile(List<Ride> rides, double keepTopFraction) {
		// Group by degree
		Map<Integer, List<Ride>> byDegree = new HashMap<>();
		for (Ride ride : rides) {
			byDegree.computeIfAbsent(ride.getDegree(), k -> new ArrayList<>()).add(ride);
		}

		List<Ride> kept = new ArrayList<>();

		for (Map.Entry<Integer, List<Ride>> entry : byDegree.entrySet()) {
			int degree = entry.getKey();
			List<Ride> group = entry.getValue();

			// Never prune singles
			if (degree <= 1) {
				kept.addAll(group);
				continue;
			}

			// Compute distance savings for each ride
			double[] savings = new double[group.size()];
			for (int i = 0; i < group.size(); i++) {
				Ride ride = group.get(i);
				double sumReqDist = 0;
				for (DrtRequest req : ride.getRequests()) {
					sumReqDist += req.getDistance();
				}
				savings[i] = sumReqDist > 0 ? 1.0 - ride.getRideDistance() / sumReqDist : 0;
			}

			// Find threshold at (1 - keepTopFraction) percentile
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

		return kept;
	}
}
