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
 * Inter-degree pruning: within each degree, keep only the top fraction of rides
 * ranked by distance savings. Singles are never pruned.
 */
public final class PostExtensionPruner {
	private static final Logger log = LogManager.getLogger(PostExtensionPruner.class);

	private final double keepTopFraction;

	public PostExtensionPruner(double keepTopFraction) {
		this.keepTopFraction = keepTopFraction;
	}

	public List<Ride> prune(List<Ride> rides) {
		if (rides == null || rides.isEmpty() || keepTopFraction >= 1.0) {
			return rides;
		}

		log.info("Post-extension pruning: {} rides input (keepTopFraction={})",
				rides.size(), keepTopFraction);

		Map<Integer, List<Ride>> byDegree = new HashMap<>();
		for (Ride ride : rides) {
			byDegree.computeIfAbsent(ride.getDegree(), k -> new ArrayList<>()).add(ride);
		}

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
				double sumReqDist = 0;
				for (DrtRequest req : ride.getRequests()) {
					sumReqDist += req.getDistance();
				}
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
}
