package org.matsim.contrib.demand_extraction.algorithm.extension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.graph.ShareabilityGraph;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Extends rides from degree N to N+1 using shareability graph.
 *
 * Uses parallel processing with deterministic output ordering.
 * Works with direct DrtRequest references stored in Rides.
 *
 * Python reference: extensions.py lines 13-194
 */
public final class RideExtender {
	private static final Logger log = LogManager.getLogger(RideExtender.class);

	private final MatsimNetworkCache network;
	private final ShareabilityGraph graph;
	private final BudgetValidator budgetValidator;
	private final Map<Integer, DrtRequest> requestMap;
	private final Map<Integer, Ride> rideMap;
	private final ExMasConfigGroup exMasConfig;
	private static final double EPSILON = 1e-9;

	private final java.util.concurrent.atomic.AtomicLong beelineExtensionRejected = new java.util.concurrent.atomic.AtomicLong();

	private static double beeline(double x1, double y1, double x2, double y2) {
		double dx = x2 - x1, dy = y2 - y1;
		return Math.sqrt(dx * dx + dy * dy);
	}

	public RideExtender(MatsimNetworkCache network, ShareabilityGraph graph, BudgetValidator budgetValidator,
			List<DrtRequest> requests, List<Ride> rides, ExMasConfigGroup exMasConfig) {
		this.network = network;
		this.graph = graph;
		this.budgetValidator = budgetValidator;
		this.requestMap = new HashMap<>();
		for (DrtRequest r : requests) requestMap.put(r.index, r);
		this.rideMap = new HashMap<>();
		for (Ride r : rides) rideMap.put(r.getIndex(), r);
		this.exMasConfig = exMasConfig;
	}

	/**
	 * Intermediate candidate holding validated extension before index assignment.
	 */
	private record ExtensionCandidate(
			int baseRideIndex, int newRequestIndex, Ride validatedRide) {

		static final Comparator<ExtensionCandidate> COMPARATOR = Comparator
				.comparingInt((ExtensionCandidate c) -> c.baseRideIndex)
				.thenComparingInt(c -> c.newRequestIndex);
	}

	/**
	 * Extend rides from degree D to D+1 using set-driven enumeration.
	 *
	 * <p>Each input ride represents one request set (1 ride per set). For each set,
	 * common neighbors are found once, then all degree-(D+1) candidate sets are formed.
	 * For each candidate set, ALL decompositions × ALL base rides × ALL FIFO/LIFO
	 * pair-ride combinations are tried exhaustively, then the single best ride is kept.
	 * The output is again 1 ride per set, ready for the next degree.
	 *
	 * <p>The result map ({@code setHash → bestRide}) serves as both output storage and
	 * deduplication: if a D+1 set is discovered from multiple D-sets, the second encounter
	 * finds the result already present and skips. No separate dedup structure needed.
	 *
	 * <p><b>Improvement over original ExMAS:</b> The original algorithm
	 * (Kucharski &amp; Cats, 2020) uses only the first FIFO/LIFO combination per extension:
	 * {@code exts = list(product(*E))[0]} in extensions.py. Since FIFO edges are added
	 * before LIFO, this always picks the all-FIFO combination. Empirical testing shows
	 * this misses 33-69% of unique destination orderings at degree 3-4, and even more at
	 * higher degrees. We explore the full cartesian product of pair-ride combinations,
	 * producing genuinely different rides with different routings and travel times.
	 */
	public List<Ride> extendRides(List<Ride> ridesToExtend, int nextRideIndex) {
		int targetDegree = ridesToExtend.isEmpty() ? 0 : ridesToExtend.get(0).getDegree() + 1;
		ExtensionAttemptStats stats = new ExtensionAttemptStats(targetDegree, exMasConfig);
		log.info("Extending {} sets from degree {} to {} ...",
				ridesToExtend.size(), targetDegree - 1, targetDegree);
		long startTime = System.currentTimeMillis();
		beelineExtensionRejected.set(0);

		// --- Step 1: Build base ride lookup ---
		// Input may have multiple rides per set (e.g. FIFO+LIFO pairs at degree 2).
		// We keep all variants as bases — different orderings enable different extensions.
		Map<String, List<Ride>> baseRidesBySet = new HashMap<>();
		List<int[]> uniqueBaseSets = new ArrayList<>();
		for (Ride ride : ridesToExtend) {
			int[] idx = ride.getRequestIndices().clone();
			Arrays.sort(idx);
			String key = Arrays.toString(idx);
			if (baseRidesBySet.putIfAbsent(key, new ArrayList<>()) == null) {
				uniqueBaseSets.add(idx);
			}
			baseRidesBySet.get(key).add(ride);
		}
		log.info("  {} base rides in {} unique request sets", ridesToExtend.size(), uniqueBaseSets.size());

		// --- Step 2: Discover and process degree-(D+1) sets ---
		// Result map: setHash → bestRide. Serves as BOTH output and dedup.
		// When a D+1 set is encountered again from another base set, containsKey() skips it.
		// Uses long hash key (~16 bytes/entry) instead of String (~120 bytes/entry).
		Long2ObjectOpenHashMap<Ride> resultBySetHash = new Long2ObjectOpenHashMap<>();
		int setsProcessed = 0;
		int setsSkippedDedup = 0;

		// findCommonNeighborsSorted is called once per UNIQUE SET, not per ride variant.
		for (int[] baseSetIndices : uniqueBaseSets) {
			int[] neighbors = graph.findCommonNeighborsSorted(baseSetIndices);

			for (int newReq : neighbors) {
				int[] newSet = buildSortedRequestSet(baseSetIndices, newReq);
				long newSetHash = hashRequestSet(newSet);

				// Dedup: if this set already has a result, skip
				if (resultBySetHash.containsKey(newSetHash)) {
					setsSkippedDedup++;
					continue;
				}

				setsProcessed++;
				if (isPowerOfTwo(setsProcessed)) {
					long now = System.currentTimeMillis();
					double elapsed = Math.max(0.001, (now - startTime) / 1000.0);
					double rate = setsProcessed / elapsed;
					log.info("  Progress: {} sets processed, {} results, {} skipped (dedup), {}/s",
							setsProcessed, resultBySetHash.size(), setsSkippedDedup,
							String.format("%.0f", rate));
				}

				// Try all decompositions × base rides × FIFO/LIFO combos for this set.
				// Track the single best ride (lowest objective value).
				Ride bestRide = null;
				double bestObjective = Double.MAX_VALUE;

				for (int i = 0; i < newSet.length; i++) {
					int addedReq = newSet[i];
					int[] baseIndices = new int[newSet.length - 1];
					for (int j = 0, k = 0; j < newSet.length; j++) {
						if (j != i) baseIndices[k++] = newSet[j];
					}
					String baseKey = Arrays.toString(baseIndices);
					List<Ride> bases = baseRidesBySet.get(baseKey);
					if (bases == null) continue;

					DrtRequest addedRequest = requestMap.get(addedReq);

					for (Ride base : bases) {
						// Duplicate person check
						boolean duplicatePerson = false;
						for (DrtRequest existingReq : base.getRequests()) {
							if (addedRequest.getPaxId().equals(existingReq.getPaxId())) {
								duplicatePerson = true;
								break;
							}
						}
						if (duplicatePerson) {
							if (stats != null) stats.duplicatePersonSkipped.increment();
							continue;
						}

						// Beeline pre-filter
						if (!passesExtensionBeelineFilter(base, addedRequest)) {
							beelineExtensionRejected.incrementAndGet();
							continue;
						}

						// Try ALL pair ride combinations (cartesian product of FIFO/LIFO edges).
						List<int[]> allCombos = getAllPairRideCombinations(base.getRequestIndices(), addedReq);
						if (allCombos.isEmpty()) {
							if (stats != null) stats.missingPairRidesSkipped.increment();
							continue;
						}

						for (int[] pairRides : allCombos) {
							Ride ext = tryExtend(base, addedRequest, pairRides, 0);
							if (ext == null) {
								if (stats != null) stats.tryExtendFailed.increment();
								continue;
							}

							Ride validated = budgetValidator.validateAndPopulateBudgets(ext);
							if (validated == null) {
								if (stats != null) stats.budgetValidationFailed.increment();
								continue;
							}

							if (exMasConfig != null && exMasConfig.getPruningDistanceSavingsLogScale() >= 0
									&& !passesDistanceSavingsPruning(validated)) {
								if (stats != null) stats.distanceSavingsPrunedEarly.increment();
								continue;
							}

							if (stats != null) stats.candidatesAdded.increment();

							// Keep only the best ride for this set
							double obj = objectiveValue(validated);
							if (obj < bestObjective) {
								bestObjective = obj;
								bestRide = validated;
							}
						}
					}
				}

				// Store best ride for this set (also marks it as processed for dedup)
				if (bestRide != null) {
					resultBySetHash.put(newSetHash, rebuildWithIndex(bestRide, nextRideIndex++));
				}
			}
		}

		if (beelineExtensionRejected.get() > 0) {
			log.info("  Beeline extension pre-filter rejected {} candidates before routing",
					beelineExtensionRejected.get());
		}
		stats.logSummary(resultBySetHash.size());

		long elapsed = System.currentTimeMillis() - startTime;
		double seconds = elapsed / 1000.0;
		log.info("Extension complete: {} sets at degree {} in {}s ({} candidate sets, {} skipped dedup, {} base sets)",
				resultBySetHash.size(), targetDegree, String.format("%.1f", seconds),
				setsProcessed, setsSkippedDedup, uniqueBaseSets.size());

		return new ArrayList<>(resultBySetHash.values());
	}

	/**
	 * Hash a sorted request index array to a long for memory-efficient set deduplication.
	 * Polynomial rolling hash — collision probability ~n²/2^64 (negligible at 100M+ sets).
	 */
	private static long hashRequestSet(int[] sortedIndices) {
		long h = 0;
		for (int idx : sortedIndices) {
			h = h * 1000003L + idx;
		}
		return h;
	}

	private static final class ExtensionAttemptStats {
		private final int targetDegree;
		private final ExMasConfigGroup config;

		private final LongAdder baseRides = new LongAdder();
		private final LongAdder neighborRequestsConsidered = new LongAdder();
		private final LongAdder duplicatePersonSkipped = new LongAdder();
		private final LongAdder missingPairRidesSkipped = new LongAdder();
		private final LongAdder tryExtendFailed = new LongAdder();
		private final LongAdder budgetValidationFailed = new LongAdder();
		private final LongAdder distanceSavingsPrunedEarly = new LongAdder();
		private final LongAdder candidatesAdded = new LongAdder();
		private final LongAdder prunedByTopNPerBase = new LongAdder();

		private ExtensionAttemptStats(int targetDegree, ExMasConfigGroup config) {
			this.targetDegree = targetDegree;
			this.config = config;
		}

		void logSummary(int totalCandidatesAfterGeneration) {
			long bases = baseRides.sum();
			if (bases == 0) {
				bases = 0;
			}

			long neighborCandidates = neighborRequestsConsidered.sum();
			long added = candidatesAdded.sum();
			long prunedSavings = distanceSavingsPrunedEarly.sum();
			long prunedTopN = prunedByTopNPerBase.sum();
			long dup = duplicatePersonSkipped.sum();
			long missingPairs = missingPairRidesSkipped.sum();
			long failedExtend = tryExtendFailed.sum();
			long failedBudget = budgetValidationFailed.sum();
			double denom = neighborCandidates > 0 ? neighborCandidates : 1.0;

			log.info("  Extension generation summary (targetDegree={}):", targetDegree);
			log.info("    base rides processed: {}", bases);
			log.info("    neighbor candidates considered: {}", neighborCandidates);
			String outcomes = String.format(Locale.ROOT,
					"    outcomes (of %,d neighbor candidates):%n" +
					"      added: %,d (%.2f%%)%n" +
					"      pruned (distance-savings): %,d (%.2f%%)%n" +
					"      failed: tryExtend %,d (%.2f%%), budgetValidation %,d (%.2f%%)%n" +
					"      skipped: missingPairSupport %,d (%.2f%%), duplicatePerson %,d (%.2f%%)%n" +
					"      pruned (top-N per base): %,d (%.2f%%)",
					neighborCandidates,
					added, 100.0 * added / denom,
					prunedSavings, 100.0 * prunedSavings / denom,
					failedExtend, 100.0 * failedExtend / denom,
					failedBudget, 100.0 * failedBudget / denom,
					missingPairs, 100.0 * missingPairs / denom,
					dup, 100.0 * dup / denom,
					prunedTopN, 100.0 * prunedTopN / denom);
			log.info(outcomes);

			if (config != null && config.getPruningDistanceSavingsLogScale() >= 0) {
				double scale = config.getPruningDistanceSavingsLogScale();
				double maxSaving = Math.min(0.99, Math.max(0.0, config.getPruningDistanceSavingsMax()));
				int minDegree = Math.max(2, config.getPruningDistanceSavingsMinDegree());
				double requiredSaving = requiredSavingForDegree(targetDegree, scale, maxSaving, minDegree);
				String scaleStr = String.format(Locale.ROOT, "%.3f", scale);
				String maxSavingStr = String.format(Locale.ROOT, "%.2f", maxSaving);
				String requiredPctStr = String.format(Locale.ROOT, "%.1f", 100.0 * requiredSaving);
				log.info(
						"    distance-savings gate (early): scale={}, minDegree={}, maxSaving={} -> requiredSaving(degree={})>={}%",
						scaleStr,
						minDegree,
						maxSavingStr,
						targetDegree,
						requiredPctStr);
			} else {
				log.info("    distance-savings gate (early): disabled");
			}

			if (totalCandidatesAfterGeneration == 0) {
				long wouldBeFeasible = neighborCandidates - missingPairs - dup - failedExtend - failedBudget;

				String likelyCause;
				if (neighborCandidates == 0) {
					likelyCause = "no eligible neighbors found (shareability graph empty or too restrictive)";
				} else if (prunedSavings > 0 && candidatesAdded.sum() == 0 && wouldBeFeasible == prunedSavings) {
					likelyCause = "distance-savings pruning eliminated all feasible candidates (all others failed tryExtend/budget/graph checks)";
				} else if (missingPairs > 0 && prunedSavings == 0 && failedExtend == 0 && failedBudget == 0) {
					likelyCause = "no complete pair-ride support for any 3rd request (shareability graph edges missing)";
				} else if ((failedExtend > 0 || failedBudget > 0) && prunedSavings == 0 && missingPairs == 0) {
					likelyCause = "feasibility/budget validation rejected all candidates";
				} else {
					likelyCause = String.format(
							"mixed causes (distance-savings pruned %.1f%%, tryExtend failed %.1f%%, budget validation failed %.1f%%, missing pair support %.1f%%)",
							100.0 * prunedSavings / denom,
							100.0 * failedExtend / denom,
							100.0 * failedBudget / denom,
							100.0 * missingPairs / denom);
				}
				log.info("    RESULT: 0 candidates generated -> {}.", likelyCause);
			}
		}
	}

	private static boolean isPowerOfTwo(int value) {
		return value > 0 && (value & (value - 1)) == 0;
	}

	private static String formatDuration(double seconds) {
		long totalSeconds = Math.max(0L, Math.round(seconds));
		long hours = totalSeconds / 3600;
		long minutes = (totalSeconds % 3600) / 60;
		long secs = totalSeconds % 60;
		if (hours > 0) {
			return String.format("%dh%02dm%02ds", hours, minutes, secs);
		}
		if (minutes > 0) {
			return String.format("%dm%02ds", minutes, secs);
		}
		return String.format("%ds", secs);
	}

	/**
	 * Generate all valid extensions for a single base ride.
	 */
	private List<ExtensionCandidate> generateExtensionsForRide(Ride ride) {
		return generateExtensionsForRide(ride, null);
	}

	private List<ExtensionCandidate> generateExtensionsForRide(Ride ride, ExtensionAttemptStats stats) {
		List<ExtensionCandidate> results = new ArrayList<>();
		int[] neighbors = graph.findCommonNeighborsSorted(ride.getRequestIndices());
		if (stats != null) {
			stats.baseRides.increment();
		}

		for (int candidateReq : neighbors) {
			if (stats != null) {
				stats.neighborRequestsConsidered.increment();
			}
			DrtRequest newRequest = requestMap.get(candidateReq);

			// Check for duplicate person
			boolean duplicatePerson = false;
			for (DrtRequest existingRequest : ride.getRequests()) {
				if (newRequest.getPaxId().equals(existingRequest.getPaxId())) {
					duplicatePerson = true;
					break;
				}
			}
			if (duplicatePerson) {
				if (stats != null) {
					stats.duplicatePersonSkipped.increment();
				}
				continue;
			}

			int[] pairRides = getPairRides(ride.getRequestIndices(), candidateReq);
			if (pairRides == null) {
				if (stats != null) {
					stats.missingPairRidesSkipped.increment();
				}
				continue;
			}

			// Beeline pre-filter: skip if new passenger is too far from existing stops
			{
				double maxDist = newRequest.directDistance * newRequest.maxDetourFactor;
				double oX = newRequest.originX, oY = newRequest.originY;
				double dX = newRequest.destinationX, dY = newRequest.destinationY;
				boolean originReachable = false;
				boolean destReachable = false;
				for (DrtRequest existing : ride.getRequests()) {
					if (!originReachable) {
						double beeToO = beeline(oX, oY, existing.originX, existing.originY);
						double beeToD = beeline(oX, oY, existing.destinationX, existing.destinationY);
						if (Math.min(beeToO, beeToD) <= maxDist) originReachable = true;
					}
					if (!destReachable) {
						double beeToO = beeline(dX, dY, existing.originX, existing.originY);
						double beeToD = beeline(dX, dY, existing.destinationX, existing.destinationY);
						if (Math.min(beeToO, beeToD) <= maxDist) destReachable = true;
					}
					if (originReachable && destReachable) break;
				}
				if (!originReachable || !destReachable) {
					beelineExtensionRejected.incrementAndGet();
					continue;
				}
			}

			// Use temp index (will be reassigned later)
			Ride ext = tryExtend(ride, newRequest, pairRides, 0);
			if (ext == null) {
				if (stats != null) {
					stats.tryExtendFailed.increment();
				}
				continue;
			}

			Ride validated = budgetValidator.validateAndPopulateBudgets(ext);
			if (validated == null) {
				if (stats != null) {
					stats.budgetValidationFailed.increment();
				}
				continue;
			}

			// Early pruning: drop candidates that don't meet required distance savings
			if (exMasConfig != null && exMasConfig.getPruningDistanceSavingsLogScale() >= 0
					&& !passesDistanceSavingsPruning(validated)) {
				if (stats != null) {
					stats.distanceSavingsPrunedEarly.increment();
				}
				continue;
			}
			results.add(new ExtensionCandidate(ride.getIndex(), candidateReq, validated));
			if (stats != null) {
				stats.candidatesAdded.increment();
			}
		}

		// Optional per-base top-N pruning of extensions by objective
		int maxExtensionsPerBaseRide = exMasConfig != null ? exMasConfig.getPruningKeepTopNExtensionsPerBaseRide() : 0;
		if (maxExtensionsPerBaseRide > 0 && results.size() > maxExtensionsPerBaseRide) {
			if (stats != null) {
				stats.prunedByTopNPerBase.add(results.size() - maxExtensionsPerBaseRide);
			}
			boolean minimize = exMasConfig.getPruningRankingGoal() == null
					|| exMasConfig.getPruningRankingGoal().equalsIgnoreCase("minimize");
			Comparator<ExtensionCandidate> cmp = Comparator.comparingDouble(c -> objectiveValue(c.validatedRide));
			if (!minimize)
				cmp = cmp.reversed();
			results.sort(cmp);
			return new ArrayList<>(results.subList(0, Math.min(maxExtensionsPerBaseRide, results.size())));
		}

		return results;
	}

	private List<Ride> applyHeuristicPruning(List<Ride> rides) {
		if (exMasConfig == null || !exMasConfig.isHeuristicPruningEnabled() || rides.isEmpty()) {
			return rides;
		}

		// Never prune paired rides (degree 2): they are required as building blocks for
		// higher-degree extensions (getPairRides/tryExtend) and for shareability graph
		// connectivity.
		if (rides.get(0).getDegree() == 2) {
			return rides;
		}

		int initialTotal = rides.size();
		double keepTopFractionPerRequestSet = Math.max(0.0,
				Math.min(1.0, exMasConfig.getPruningKeepTopFractionPerRequestSet()));
		int minRidesToKeepPerRequestSet = Math.max(0, exMasConfig.getPruningMinRidesToKeepPerRequestSet());
		int maxRidesToKeepPerRequestSet = Math.max(0, exMasConfig.getPruningMaxRidesToKeepPerRequestSet());
		boolean minimizeObjective = exMasConfig.getPruningRankingGoal() == null
				|| exMasConfig.getPruningRankingGoal().equalsIgnoreCase("minimize");

		if (keepTopFractionPerRequestSet >= 1.0 && minRidesToKeepPerRequestSet <= 0
				&& maxRidesToKeepPerRequestSet <= 0) {
			return rides;
		}

		int beforeFractional = rides.size();
		List<Ride> kept;

		if (keepTopFractionPerRequestSet >= 1.0 && minRidesToKeepPerRequestSet <= 0
				&& maxRidesToKeepPerRequestSet <= 0) {
			kept = rides;
		} else {
			Map<String, List<Ride>> byGroup = new HashMap<>();
			for (Ride r : rides) {
				int[] idx = r.getRequestIndices().clone();
				Arrays.sort(idx);
				String key = Arrays.toString(idx);
				byGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
			}

			kept = new ArrayList<>(beforeFractional);
			for (Map.Entry<String, List<Ride>> e : byGroup.entrySet()) {
				List<Ride> group = e.getValue();
				Comparator<Ride> cmp = Comparator.comparingDouble(this::objectiveValue);
				if (!minimizeObjective)
					cmp = cmp.reversed();
				group.sort(cmp);

				int size = group.size();
				int keep = (int) Math.ceil(size * keepTopFractionPerRequestSet);
				keep = Math.max(keep, minRidesToKeepPerRequestSet);
				if (maxRidesToKeepPerRequestSet > 0) {
					keep = Math.min(keep, maxRidesToKeepPerRequestSet);
				}
				keep = Math.min(keep, size);
				kept.addAll(group.subList(0, keep));
			}
		}

		int fractionalRemoved = beforeFractional - kept.size();
		log.info(
				"  Post-generation pruning (per-request-set): candidatesBefore={}, candidatesAfter={}, removed={}",
				initialTotal,
				kept.size(),
				fractionalRemoved);

		String objective = exMasConfig.getPruningRankingObjective();
		if (objective == null || objective.isBlank()) {
			objective = "rideDistance";
		}
		String goal = exMasConfig.getPruningRankingGoal();
		if (goal == null || goal.isBlank()) {
			goal = "minimize";
		}

		log.info(
				"  Pruning settings (per-request-set): keepTopFraction={}, minToKeep={}, maxToKeep={}, objective={}, goal={}",
				String.format(Locale.ROOT, "%.3f", keepTopFractionPerRequestSet),
				minRidesToKeepPerRequestSet,
				maxRidesToKeepPerRequestSet,
				objective,
				goal);

		return kept;
	}

	/**
	 * Build a sorted request set by adding newReq to existing indices.
	 */
	private static int[] buildSortedRequestSet(int[] existing, int newReq) {
		int[] result = new int[existing.length + 1];
		System.arraycopy(existing, 0, result, 0, existing.length);
		result[existing.length] = newReq;
		Arrays.sort(result);
		return result;
	}

	/**
	 * Beeline pre-filter for extensions: check if new passenger's origin and destination
	 * are each within reach of at least one existing stop in the ride.
	 */
	private boolean passesExtensionBeelineFilter(Ride base, DrtRequest newRequest) {
		double maxDist = newRequest.directDistance * newRequest.maxDetourFactor;
		double oX = newRequest.originX, oY = newRequest.originY;
		double dX = newRequest.destinationX, dY = newRequest.destinationY;

		boolean originReachable = false;
		boolean destReachable = false;
		for (DrtRequest existing : base.getRequests()) {
			if (!originReachable) {
				double beeO = Math.min(
					beeline(oX, oY, existing.originX, existing.originY),
					beeline(oX, oY, existing.destinationX, existing.destinationY));
				if (beeO <= maxDist) originReachable = true;
			}
			if (!destReachable) {
				double beeD = Math.min(
					beeline(dX, dY, existing.originX, existing.originY),
					beeline(dX, dY, existing.destinationX, existing.destinationY));
				if (beeD <= maxDist) destReachable = true;
			}
			if (originReachable && destReachable) return true;
		}
		return false;
	}

	/**
	 * Prune variants for a single request set using the configured percentage/min/max settings.
	 * Returns the kept rides (not yet reindexed).
	 */
	private List<Ride> pruneRequestSetVariants(List<ExtensionCandidate> variants) {
		if (variants.isEmpty()) return List.of();

		// Respect the heuristic pruning enabled flag
		if (exMasConfig == null || !exMasConfig.isHeuristicPruningEnabled()) {
			List<Ride> result = new ArrayList<>(variants.size());
			for (ExtensionCandidate c : variants) {
				result.add(c.validatedRide());
			}
			return result;
		}

		double keepFraction = Math.min(1.0, exMasConfig.getPruningKeepTopFractionPerRequestSet());
		int minKeep = Math.max(0, exMasConfig.getPruningMinRidesToKeepPerRequestSet());
		int maxKeep = Math.max(0, exMasConfig.getPruningMaxRidesToKeepPerRequestSet());
		boolean minimize = exMasConfig.getPruningRankingGoal() == null
				|| exMasConfig.getPruningRankingGoal().equalsIgnoreCase("minimize");

		Comparator<ExtensionCandidate> cmp = Comparator.comparingDouble(c -> objectiveValue(c.validatedRide()));
		if (!minimize) cmp = cmp.reversed();
		variants.sort(cmp);

		int size = variants.size();
		int keep = (int) Math.ceil(size * keepFraction);
		keep = Math.max(keep, minKeep);
		if (maxKeep > 0) keep = Math.min(keep, maxKeep);
		keep = Math.min(keep, size);

		List<Ride> result = new ArrayList<>(keep);
		for (int i = 0; i < keep; i++) {
			result.add(variants.get(i).validatedRide());
		}
		return result;
	}

	private boolean passesDistanceSavingsPruning(Ride ride) {
		if (exMasConfig == null) {
			return true;
		}
		double scale = exMasConfig.getPruningDistanceSavingsLogScale();
		if (scale < 0) {
			return true;
		}

		int degree = ride.getRequests() != null ? ride.getRequests().length : 0;
		int minDegree = Math.max(2, exMasConfig.getPruningDistanceSavingsMinDegree());
		if (degree < minDegree) {
			return true;
		}

		double sumDistances = sumRequestDistances(ride);
		if (!(sumDistances > 0)) {
			return true;
		}

		double maxSaving = exMasConfig.getPruningDistanceSavingsMax();
		if (!(maxSaving >= 0)) {
			maxSaving = 0.0;
		}
		maxSaving = Math.min(0.99, maxSaving);

		double requiredSaving = requiredSavingForDegree(degree, scale, maxSaving, minDegree);
		double maxRideDistance = (1.0 - requiredSaving) * sumDistances;
		return ride.getRideDistance() <= maxRideDistance;
	}

	private static double requiredSavingForDegree(int degree, double scale, double maxSaving, int minDegree) {
		if (scale < 0) {
			return 0.0;
		}
		if (degree < Math.max(2, minDegree)) {
			return 0.0;
		}
		// requiredSaving = scale * log2(degree)
		double requiredSaving = scale * (Math.log(degree) / Math.log(2.0));
		requiredSaving = Math.max(0.0, Math.min(Math.min(0.99, maxSaving), requiredSaving));
		return requiredSaving;
	}

	private double objectiveValue(Ride r) {
		String obj = exMasConfig.getPruningRankingObjective();
		if (obj == null)
			obj = "rideDistance";
		switch (obj) {
			case "passengerTravelTime":
				return sumPassengerTravelTimes(r);
			case "passengerUtility":
				return -sumPassengerUtilities(r); // higher is better
			case "rideDistance":
			default:
				return r.getRideDistance();
		}
	}

	private double sumRequestDistances(Ride r) {
		return Arrays.stream(r.getRequests())
				.mapToDouble(DrtRequest::getDistance)
				.sum();
	}

	private double sumPassengerTravelTimes(Ride r) {
		double[] t = r.getPassengerTravelTimes();
		double s = 0.0;
		if (t != null)
			for (double v : t)
				s += v;
		return s;
	}

	private double sumPassengerUtilities(Ride r) {
		double[] u = r.getPassengerNetworkUtilities();
		double s = 0.0;
		if (u != null)
			for (double v : u)
				s += v;
		return s;
	}

	/**
	 * Rebuild ride with new index.
	 */
	private Ride rebuildWithIndex(Ride ride, int newIndex) {
		return ride.toBuilder()
				.index(newIndex)
				.build();
	}

	private int[] getPairRides(int[] requests, int candidate) {
		int[] pairRides = new int[requests.length];
		for (int i = 0; i < requests.length; i++) {
			IntList edges = graph.getEdges(requests[i], candidate);
			if (edges.isEmpty()) return null;
			pairRides[i] = edges.getInt(0);
		}
		return pairRides;
	}

	/**
	 * Get ALL combinations of pair rides between existing requests and a candidate.
	 * Each existing request may have multiple pair rides (FIFO + LIFO) with the candidate.
	 * Returns the cartesian product of all edges.
	 */
	private List<int[]> getAllPairRideCombinations(int[] requests, int candidate) {
		List<IntList> edgesPerRequest = new ArrayList<>();
		for (int i = 0; i < requests.length; i++) {
			IntList edges = graph.getEdges(requests[i], candidate);
			if (edges.isEmpty()) return List.of();
			edgesPerRequest.add(edges);
		}
		// Cartesian product
		List<int[]> result = new ArrayList<>();
		int[] current = new int[requests.length];
		generateCombinations(edgesPerRequest, 0, current, result);
		return result;
	}

	private void generateCombinations(List<IntList> edgesPerRequest, int depth, int[] current, List<int[]> result) {
		if (depth == edgesPerRequest.size()) {
			result.add(current.clone());
			return;
		}
		IntList edges = edgesPerRequest.get(depth);
		for (int i = 0; i < edges.size(); i++) {
			current[depth] = edges.getInt(i);
			generateCombinations(edgesPerRequest, depth + 1, current, result);
		}
	}

	private Ride tryExtend(Ride base, DrtRequest newRequest, int[] pairRides, int index) {
		int degree = base.getDegree();
		DrtRequest[] destOrderedRequests = base.getDestinationsOrderedRequests();

		// Determine insertion position based on pair ride kinds
		int fifoCount = 0;
		int lifoCount = 0;
		int minLifoPos = Integer.MAX_VALUE;
		int maxFifoPos = -1;

		DrtRequest[] baseRequests = base.getRequests();
		for (int i = 0; i < pairRides.length; i++) {
			Ride pairRide = rideMap.get(pairRides[i]);
			DrtRequest oldReq = baseRequests[i];
			int posInDest = indexOfRequest(destOrderedRequests, oldReq);

			if (pairRide.getKind() == RideKind.FIFO) {
				fifoCount++;
				maxFifoPos = Math.max(maxFifoPos, posInDest);
			} else if (pairRide.getKind() == RideKind.LIFO) {
				lifoCount++;
				minLifoPos = Math.min(minLifoPos, posInDest);
			}
		}

		RideKind kind;
		int insertPos;

		if (lifoCount == 0) {
			kind = RideKind.FIFO;
			insertPos = degree;
		} else if (fifoCount == 0) {
			kind = RideKind.LIFO;
			insertPos = 0;
		} else if (minLifoPos > maxFifoPos) {
			kind = RideKind.MIXED;
			insertPos = minLifoPos;
		} else {
			return null;
		}

		// Build new request arrays
		DrtRequest[] requests = appendRequest(base.getRequests(), newRequest);
		DrtRequest[] originsOrderedRequests = appendRequest(base.getOriginsOrderedRequests(), newRequest);
		DrtRequest[] destinationsOrderedRequests = insertRequest(base.getDestinationsOrderedRequests(), insertPos, newRequest);

		// Build connection sequence from request arrays (derive Link IDs)
		int seqLen = (degree + 1) * 2;
		Id<Link>[] sequence = buildSequence(originsOrderedRequests, destinationsOrderedRequests);

		double[] connTT = new double[seqLen - 1];
		double[] connDist = new double[seqLen - 1];
		double[] connUtil = new double[seqLen - 1];

		double startTime = requests[0].getRequestTime();
		for (int i = 0; i < seqLen - 1; i++) {
			TravelSegment seg = network.getSegment(sequence[i], sequence[i + 1], startTime);
			if (!seg.isReachable()) return null;
			connTT[i] = seg.getTravelTime();
			connDist[i] = seg.getDistance();
			connUtil[i] = seg.getNetworkUtility();
		}

		// Calculate passenger metrics
		double[] pttActual = new double[degree + 1];
		double[] pDist = new double[degree + 1];
		double[] pUtil = new double[degree + 1];

		for (int i = 0; i < degree + 1; i++) {
			DrtRequest req = requests[i];

			// Origin position is always i
			int origIdx = i;

			// Find where this request appears in the destinations ordering
			int destPosInDestArray = indexOfRequest(destinationsOrderedRequests, req);
			if (destPosInDestArray < 0) {
				throw new IllegalStateException("Request " + req.index + " not found in destinationsOrderedRequests");
			}
			int destIdx = degree + 1 + destPosInDestArray;

			for (int j = origIdx; j < destIdx; j++) {
				pttActual[i] += connTT[j];
				pDist[i] += connDist[j];
				pUtil[i] += connUtil[j];
			}

			// Fix numerical issues
			if (pttActual[i] < req.getTravelTime() - EPSILON) {
				pttActual[i] = req.getTravelTime();
			}

			if (pttActual[i] > req.getMaxTravelTime()) return null;
		}

		// Calculate delays
		double[] delays = new double[degree + 1];
		for (int i = 0; i < degree + 1; i++) {
			double arrivalAtOrigin = startTime;
			for (int j = 0; j < i; j++) {
				arrivalAtOrigin += connTT[j];
			}
			delays[i] = arrivalAtOrigin - requests[i].getRequestTime();
		}

		// Calculate effective delays and detours
		double[] effMaxNeg = new double[degree + 1];
		double[] effMaxPos = new double[degree + 1];
		double[] detours = new double[degree + 1];

		for (int i = 0; i < degree + 1; i++) {
			DrtRequest req = requests[i];
			double detourFactor = pttActual[i] / req.getTravelTime();
			detours[i] = detourFactor;

			// Convert detour factor to absolute time for delay budget calculations
			double detourTime = req.getTravelTime() * (detourFactor - 1.0);

			double posAdj = req.getPositiveDelayRelComponent() > 0.0
					? Math.max(0.0, req.getPositiveDelayRelComponent() - detourTime)
					: 0.0;
			double negAdj = req.getNegativeDelayRelComponent() > 0.0
					? Math.max(0.0, req.getNegativeDelayRelComponent() - detourTime)
					: 0.0;

			effMaxPos[i] = (req.getMaxPositiveDelay() - detourTime) - posAdj;
			effMaxNeg[i] = req.getMaxNegativeDelay() - negAdj;
		}

		// Optimize delays
		double[] adjDelays = optimizeDelays(delays, effMaxNeg, effMaxPos);
		if (adjDelays == null) return null;

		return Ride.builder()
				.index(index)
				.degree(degree + 1)
				.kind(kind)
				.requests(requests)
				.originsOrderedRequests(originsOrderedRequests)
				.destinationsOrderedRequests(destinationsOrderedRequests)
				.passengerTravelTimes(pttActual)
				.passengerDistances(pDist)
				.passengerNetworkUtilities(pUtil)
				.delays(adjDelays)
				.detours(detours)
				.connectionTravelTimes(connTT)
				.connectionDistances(connDist)
				.connectionNetworkUtilities(connUtil)
				.startTime(startTime)
				.build();
	}

	@SuppressWarnings("unchecked")
	private Id<Link>[] buildSequence(DrtRequest[] origins, DrtRequest[] destinations) {
		Id<Link>[] seq = (Id<Link>[]) new Id[origins.length + destinations.length];
		for (int i = 0; i < origins.length; i++) {
			seq[i] = origins[i].originLinkId;
		}
		for (int i = 0; i < destinations.length; i++) {
			seq[origins.length + i] = destinations[i].destinationLinkId;
		}
		return seq;
	}

	private double[] optimizeDelays(double[] delays, double[] maxNeg, double[] maxPos) {
		for (int i = 0; i < delays.length; i++) {
			if (maxPos[i] < -maxNeg[i]) return null;
		}

		double lower = Double.NEGATIVE_INFINITY, upper = Double.POSITIVE_INFINITY;
		for (int i = 0; i < delays.length; i++) {
			lower = Math.max(lower, -delays[i] - maxNeg[i]);
			upper = Math.min(upper, maxPos[i] - delays[i]);
		}

		if (lower > upper + EPSILON) return null;

		double maxDelay = Double.NEGATIVE_INFINITY, minDelay = Double.POSITIVE_INFINITY;
		for (double d : delays) {
			maxDelay = Math.max(maxDelay, d);
			minDelay = Math.min(minDelay, d);
		}

		double depOpt = -(maxDelay + minDelay) / 2.0;
		depOpt = Math.max(lower, Math.min(upper, depOpt));

		double[] adjusted = new double[delays.length];
		for (int i = 0; i < delays.length; i++) {
			adjusted[i] = delays[i] + depOpt;
			if (adjusted[i] < -maxNeg[i] - EPSILON || adjusted[i] > maxPos[i] + EPSILON) return null;
		}
		return adjusted;
	}

	private int indexOfRequest(DrtRequest[] arr, DrtRequest req) {
		for (int i = 0; i < arr.length; i++) {
			if (arr[i].index == req.index) return i;
		}
		return -1;
	}

	private DrtRequest[] appendRequest(DrtRequest[] arr, DrtRequest val) {
		DrtRequest[] res = Arrays.copyOf(arr, arr.length + 1);
		res[arr.length] = val;
		return res;
	}

	private DrtRequest[] insertRequest(DrtRequest[] arr, int pos, DrtRequest val) {
		DrtRequest[] res = new DrtRequest[arr.length + 1];
		System.arraycopy(arr, 0, res, 0, pos);
		res[pos] = val;
		System.arraycopy(arr, pos, res, pos + 1, arr.length - pos);
		return res;
	}
}
