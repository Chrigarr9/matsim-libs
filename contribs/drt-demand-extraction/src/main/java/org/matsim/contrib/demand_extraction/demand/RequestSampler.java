package org.matsim.contrib.demand_extraction.demand;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.core.config.Config;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Handles the sampling of DRT requests before they are processed by the ExMAS algorithm.
 * Supports sampling by count or fraction, and re-indexes the requests to ensure contiguous IDs.
 * Ensures that all requests of a single person are sampled together.
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
		
		// Group requests by person
		Map<Id<Person>, List<DrtRequest>> requestsByPerson = new HashMap<>();
		for (DrtRequest req : requests) {
			requestsByPerson.computeIfAbsent(req.personId, k -> new ArrayList<>()).add(req);
		}
		
		List<Id<Person>> personIds = new ArrayList<>(requestsByPerson.keySet());
		Collections.shuffle(personIds, random);
		
		List<DrtRequest> sampled = new ArrayList<>();
		int targetSize;
		if (count != null) {
			targetSize = count;
		} else {
			targetSize = (int) (requests.size() * fraction);
		}
		
		for (Id<Person> personId : personIds) {
			if (sampled.size() >= targetSize) {
				break;
			}
			
			List<DrtRequest> personRequests = requestsByPerson.get(personId);
			int potentialTotal = sampled.size() + personRequests.size();
			
			// If adding this person exceeds the limit, skip unless we haven't sampled anything yet
			// (to ensure we get at least something if the first person has more requests than the limit)
			if (potentialTotal > targetSize && !sampled.isEmpty()) {
				continue;
			}
			
			sampled.addAll(personRequests);
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
