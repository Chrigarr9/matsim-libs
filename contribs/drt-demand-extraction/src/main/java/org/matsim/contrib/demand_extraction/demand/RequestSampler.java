package org.matsim.contrib.demand_extraction.demand;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.core.config.Config;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Handles the sampling of DRT requests before they are processed by the ExMAS algorithm.
 * Supports sampling by count or fraction, and re-indexes the requests to ensure contiguous IDs.
 */
@Singleton
public class RequestSampler {
	private static final Logger log = LogManager.getLogger(RequestSampler.class);

	private final ExMasConfigGroup exMasConfig;
	private final Config config;

	@Inject
	public RequestSampler(ExMasConfigGroup exMasConfig, Config config) {
		this.exMasConfig = exMasConfig;
		this.config = config;
	}

	public List<DrtRequest> sampleRequests(List<DrtRequest> requests) {
		Integer count = exMasConfig.getRequestCount();
		double fraction = exMasConfig.getRequestSampleSize();

		if (count == null && fraction >= 1.0) {
			return requests;
		}

		log.info("Sampling requests: count={}, fraction={}", count, fraction);
		
		Random random = new Random(config.global().getRandomSeed());
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
