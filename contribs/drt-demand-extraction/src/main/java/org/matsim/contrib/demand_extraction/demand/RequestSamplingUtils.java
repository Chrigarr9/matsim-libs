package org.matsim.contrib.demand_extraction.demand;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;

/**
 * Utility class for sampling and re-indexing DRT requests.
 */
public class RequestSamplingUtils {
	private static final Logger log = LogManager.getLogger(RequestSamplingUtils.class);

	private RequestSamplingUtils() {
		// Utility class
	}

	/**
	 * Samples requests based on configuration (count or fraction) and re-indexes them.
	 * 
	 * @param requests The original list of requests
	 * @param exMasConfig The configuration containing sampling parameters
	 * @param randomSeed The random seed for reproducibility
	 * @return A new list of sampled and re-indexed requests
	 */
	public static List<DrtRequest> sampleRequests(List<DrtRequest> requests, ExMasConfigGroup exMasConfig, long randomSeed) {
		Integer count = exMasConfig.getRequestCount();
		double fraction = exMasConfig.getRequestSampleSize();

		if (count == null && fraction >= 1.0) {
			return requests;
		}

		log.info("Sampling requests: count={}, fraction={}", count, fraction);
		
		Random random = new Random(randomSeed);
		List<DrtRequest> sampled = new ArrayList<>(requests);
		Collections.shuffle(sampled, random);

		if (count != null) {
			if (count < sampled.size()) {
				sampled = sampled.subList(0, count);
			}
		} else if (fraction < 1.0) {
			int targetSize = (int) (sampled.size() * fraction);
			sampled = sampled.subList(0, targetSize);
		}
		
		// Re-index requests to ensure contiguous indices (0 to N-1)
		List<DrtRequest> reindexed = new ArrayList<>(sampled.size());
		for (int i = 0; i < sampled.size(); i++) {
			reindexed.add(sampled.get(i).toBuilder().index(i).build());
		}
		
		log.info("Sampled {} requests from original {}", reindexed.size(), requests.size());
		return reindexed;
	}
}
