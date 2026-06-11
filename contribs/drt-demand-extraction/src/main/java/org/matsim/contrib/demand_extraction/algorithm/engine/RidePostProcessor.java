package org.matsim.contrib.demand_extraction.algorithm.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.RideStore;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.network.TravelSegmentLookup;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Post-process ExMAS rides to enrich them with:
 * - maxCosts: maximum fare per passenger before utility equals best alternative
 * - shapleyValues: distance contribution per passenger
 * - predecessors/successors: feasible ride sequencing edges
 */
public final class RidePostProcessor {
    private static final Logger log = LogManager.getLogger(RidePostProcessor.class);

    /**
     * Resolves the maximum acceptable fare (EUR) for one passenger of one ride.
     * Phase 1 wires this from {@code BudgetToConstraintsCalculator.budgetToMaxCost(...)}
     * with a Person lookup; Phase 2 wires it from cost parameters alone (no Population).
     */
    @FunctionalInterface
    public interface MaxCostResolver {
        double maxCost(double budget, DrtRequest request, double travelTime, double distance);
    }

    private final ExMasConfigGroup config;
    private final TravelSegmentLookup networkCache;
    private final MaxCostResolver maxCostResolver;

    /**
     * Production constructor — wires a full {@link MatsimNetworkCache}.
     * Prefer this in runners and Guice modules.
     */
    public RidePostProcessor(ExMasConfigGroup config, MatsimNetworkCache networkCache,
                            MaxCostResolver maxCostResolver) {
        this(config, (TravelSegmentLookup) networkCache, maxCostResolver);
    }

    /**
     * Flexible constructor — accepts any {@link TravelSegmentLookup}.
     * Used in tests with lightweight stubs.
     */
    public RidePostProcessor(ExMasConfigGroup config, TravelSegmentLookup networkCache,
                            MaxCostResolver maxCostResolver) {
        this.config = config;
        this.networkCache = networkCache;
        this.maxCostResolver = maxCostResolver;
    }

    public List<Ride> process(RideStore store) {
        if (store == null || store.size() == 0) {
            return new ArrayList<>();
        }
        // Materialize the full ordered list once — leaves the entire body unchanged
        // and guarantees byte-identical output. The memory optimization (stream over
        // stubs without a full list) is deferred to the stub-backing phase.
        List<Ride> rides = new ArrayList<>(store.size());
        store.forEachMaterialized(rides::add);

		log.info("Post-processing {} rides...", rides.size());
		long startTime = System.currentTimeMillis();

		log.info("  Computing max costs...");
		long maxCostStart = System.currentTimeMillis();
        Map<Integer, MaxCostResult> maxCostByRide = computeMaxCosts(rides);
		log.info("  Max costs computed in {} ms", System.currentTimeMillis() - maxCostStart);

		Map<Integer, double[]> shapleyByRide;
		if (config.isCalcShapleyValues()) {
			log.info("  Computing Shapley values...");
			long shapleyStart = System.currentTimeMillis();
			shapleyByRide = computeShapleyValues(rides);
			log.info("  Shapley values computed in {} ms", System.currentTimeMillis() - shapleyStart);
		} else {
			log.info("  Shapley values disabled (skipped)");
			shapleyByRide = Collections.emptyMap();
		}

		PredSucc predsAndSuccs;
		if (config.isCalcPredecessors()) {
			log.info("  Computing predecessors/successors...");
			long predStart = System.currentTimeMillis();
			predsAndSuccs = computePredecessors(rides);
			log.info("  Predecessors/successors computed in {} ms", System.currentTimeMillis() - predStart);
		} else {
			log.info("  Predecessors/successors disabled (skipped)");
			predsAndSuccs = new PredSucc(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
		}

        List<Ride> enriched = new ArrayList<>(rides.size());
        for (Ride ride : rides) {
            double[] shapley = shapleyByRide.get(ride.getIndex());
            int[] preds = predsAndSuccs.predecessors().getOrDefault(ride.getIndex(), new int[0]);
            int[] succs = predsAndSuccs.successors().getOrDefault(ride.getIndex(), new int[0]);
            MaxCostResult maxCostResult = maxCostByRide.get(ride.getIndex());
            double reposMean = predsAndSuccs.reposTimeMeans().getOrDefault(ride.getIndex(), -1.0);

            Ride rebuilt = ride.toBuilder()
                    .maxCosts(maxCostResult.maxCosts())
                    .maxCostsPerKm(maxCostResult.maxCostsPerKm())
                    .shapleyValues(shapley)
                    .predecessors(preds)
                    .successors(succs)
                    .reposTimeMeanOutgoing(reposMean)
                    .build();
            enriched.add(rebuilt);
        }

		long totalTime = System.currentTimeMillis() - startTime;
		log.info("Post-processing complete in {} ms", totalTime);
        return enriched;
    }

    private record MaxCostResult(double[] maxCosts, double[] maxCostsPerKm) {}

    private Map<Integer, MaxCostResult> computeMaxCosts(List<Ride> rides) {
        Map<Integer, MaxCostResult> maxCostByRide = new HashMap<>(rides.size());
        for (Ride ride : rides) {
            double[] remainingBudgets = ride.getRemainingBudgets();
            double[] maxCosts = new double[ride.getDegree()];
            double[] maxCostsPerKm = new double[ride.getDegree()];
            DrtRequest[] requests = ride.getRequests();
            double[] travelTimes = ride.getPassengerTravelTimes();
            double[] distances = ride.getPassengerDistances();

            for (int i = 0; i < ride.getDegree(); i++) {
                DrtRequest request = requests[i];
                double budget = (remainingBudgets != null && remainingBudgets.length > i) ? remainingBudgets[i] : 0.0;

                maxCosts[i] = maxCostResolver.maxCost(budget, request, travelTimes[i], distances[i]);

                // Derive per-km cost (source of truth for Python optimization pipeline)
                maxCostsPerKm[i] = distances[i] > 0
                    ? maxCosts[i] / (distances[i] / 1000.0)
                    : Double.MAX_VALUE;
            }

            maxCostByRide.put(ride.getIndex(), new MaxCostResult(maxCosts, maxCostsPerKm));
        }
        return maxCostByRide;
    }

    private Map<Integer, double[]> computeShapleyValues(List<Ride> rides) {
        Map<Set<Integer>, Double> subsetDistance = new HashMap<>();
        for (Ride ride : rides) {
            Set<Integer> subset = Arrays.stream(ride.getRequestIndices())
                    .boxed()
                    .collect(Collectors.toCollection(HashSet::new));
            double rideDistance = ride.getRideDistance();
            subsetDistance.merge(subset, rideDistance, Math::min);
        }
        subsetDistance.put(Collections.emptySet(), 0.0);

        Map<Integer, double[]> shapleyByRide = new ConcurrentHashMap<>();
        int availableParallelism = resolveParallelism();
        var stream = IntStream.range(0, rides.size());
        if (availableParallelism > 1) {
            stream = stream.parallel();
        }

        stream.forEach(idx -> {
            Ride ride = rides.get(idx);
            int[] requests = ride.getRequestIndices();
            int n = requests.length;
            Set<Integer> rideSet = Arrays.stream(requests).boxed().collect(Collectors.toCollection(HashSet::new));

            if (n == 1) {
                Set<Integer> singleton = new HashSet<>(rideSet);
                shapleyByRide.put(ride.getIndex(), new double[] { subsetDistance.getOrDefault(singleton, ride.getRideDistance()) });
                return;
            }

            double nFactorial = factorial(n);
            double[] shapley = new double[n];
            List<Integer> restList;

            for (int i = 0; i < n; i++) {
                int player = requests[i];
                Set<Integer> rest = new HashSet<>(rideSet);
                rest.remove(player);
                restList = new ArrayList<>(rest);
                int restSize = restList.size();
                int subsetCount = 1 << restSize;

                for (int mask = 0; mask < subsetCount; mask++) {
                    Set<Integer> subset = new HashSet<>();
                    for (int bit = 0; bit < restSize; bit++) {
                        if ((mask & (1 << bit)) != 0) {
                            subset.add(restList.get(bit));
                        }
                    }
                    double vS = subsetDistance.getOrDefault(subset, 0.0);
                    Set<Integer> withPlayer = new HashSet<>(subset);
                    withPlayer.add(player);
                    double vSi = subsetDistance.getOrDefault(withPlayer, 0.0);
                    int sSize = subset.size();
                    double weight = (factorial(sSize) * factorial(n - sSize - 1)) / nFactorial;
                    shapley[i] += weight * (vSi - vS);
                }
            }

            shapleyByRide.put(ride.getIndex(), shapley);
        });

        return shapleyByRide;
    }

    private PredSucc computePredecessors(List<Ride> rides) {
		log.info("    Sorting {} rides by start time...", rides.size());
        List<Ride> sortedByStart = new ArrayList<>(rides);
        sortedByStart.sort(Comparator.comparingDouble(Ride::getStartTime));
        int total = sortedByStart.size();

        double[] startTimes = new double[total];
        double[] endTimes = new double[total];
        double[] rideDistances = new double[total];
        @SuppressWarnings("unchecked")
        Id<Link>[] firstOrigins = (Id<Link>[]) new Id[total];
        @SuppressWarnings("unchecked")
        Id<Link>[] lastDests = (Id<Link>[]) new Id[total];
        List<Set<Integer>> requestSets = new ArrayList<>(total);

        for (int idx = 0; idx < total; idx++) {
            Ride ride = sortedByStart.get(idx);
            startTimes[idx] = ride.getStartTime();
            endTimes[idx] = ride.getEndTime();
            rideDistances[idx] = ride.getRideDistance();
            Id<Link>[] origins = ride.getOriginsOrdered();
            Id<Link>[] destinations = ride.getDestinationsOrdered();
            firstOrigins[idx] = origins.length > 0 ? origins[0] : null;
            lastDests[idx] = destinations.length > 0 ? destinations[destinations.length - 1] : null;
            requestSets.add(Arrays.stream(ride.getRequestIndices()).boxed().collect(Collectors.toSet()));
        }

        // -1 or null => unbounded (no filter)
        Double rawFilterTime = config.getPredecessorsFilterTime();
        double filterTime = (rawFilterTime != null && rawFilterTime >= 0) ? rawFilterTime : Double.POSITIVE_INFINITY;
        Double rawFilterDist = config.getPredecessorsFilterDistanceFactor();
        double filterDistanceFactor = (rawFilterDist != null && rawFilterDist >= 0) ? rawFilterDist : Double.POSITIVE_INFINITY;
        int maxSuccessors = config.getMaxSuccessors();

        Map<Integer, List<Integer>> predecessors = new ConcurrentHashMap<>();
        Map<Integer, List<Integer>> successors = new ConcurrentHashMap<>();
        Map<Integer, Double> reposTimeMeans = new ConcurrentHashMap<>();

        int parallelism = resolveParallelism();
		log.info("    Computing predecessor/successor connections (parallelism: {})...", parallelism);
		log.info("    Filter: time={}, distanceFactor={}, maxSuccessors={}",
				Double.isInfinite(filterTime) ? "unbounded" : String.format("%.0fs", filterTime),
				Double.isInfinite(filterDistanceFactor) ? "unbounded" : String.format("%.2f", filterDistanceFactor),
				maxSuccessors <= 0 ? "all" : maxSuccessors);
		if (Double.isInfinite(filterTime)) {
			log.warn("    predecessorsFilterTime is unbounded (-1) — all {} ride pairs will be considered. " +
					"This creates a complete connection cache but scales O(n²).", (long) total * (total - 1) / 2);
		}
		log.info("    This requires routing up to {} potential connections via network...", (long) total * (total - 1) / 2);

		long routingStartTime = System.currentTimeMillis();
        IntStream stream = IntStream.range(0, total);
        if (parallelism > 1) {
            stream = stream.parallel();
        }
		AtomicInteger processed = new AtomicInteger(0);
		long routingStartNanos = System.nanoTime();

        // Forward search: For each ride i, find successors j
        stream.forEach(i -> {
			int done = processed.incrementAndGet();
			if (done == total || (int)(100.0 * done / total) > (int)(100.0 * (done - 1) / total)) {
				double elapsedSeconds = (System.nanoTime() - routingStartNanos) / 1e9;
				double remainingSeconds = done <= 0 ? 0.0 : (elapsedSeconds / done) * (total - done);
				double percent = 100.0 * done / total;
				log.info("      Predecessor/successor routing progress: {}/{} ({}%), ETA {}",
						done, total, String.format("%.1f", percent), formatDuration(remainingSeconds));
			}
            double endTime = endTimes[i];
            double minStartTime = endTime; // Successor must start after predecessor ends
            double maxStartTime = endTime + filterTime;

            // Find range in sortedByStart [minStartTime, maxStartTime]
            int sliceStart = Arrays.binarySearch(startTimes, minStartTime);
            if (sliceStart < 0) {
                sliceStart = -sliceStart - 1;
            } else {
                // Handle duplicates: move left to first occurrence
                while (sliceStart > 0 && startTimes[sliceStart - 1] >= minStartTime) {
                    sliceStart--;
                }
            }
            
            int sliceEnd = Arrays.binarySearch(startTimes, maxStartTime);
            if (sliceEnd < 0) {
                sliceEnd = -sliceEnd - 1;
            } else {
                sliceEnd += 1; // upper bound
            }

            // SSSP batch precompute: one Dijkstra tree from lastDests[i] populates the cache
            // for all candidate destination links in [sliceStart, sliceEnd). Replaces N
            // point-to-point routing calls with 1 tree. Pattern from PairGenerator.java:203.
            // The no-op default on TravelSegmentLookup keeps test stubs working unchanged.
            Id<Link> originLink = lastDests[i];
            if (originLink != null && sliceEnd > sliceStart) {
                List<Id<Link>> candidateLinksList = new ArrayList<>(sliceEnd - sliceStart);
                for (int j = sliceStart; j < sliceEnd; j++) {
                    if (i == j) continue;
                    Id<Link> to = firstOrigins[j];
                    if (to != null) candidateLinksList.add(to);
                }
                if (!candidateLinksList.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Id<Link>[] toArr = candidateLinksList.toArray(new Id[0]);
                    networkCache.batchPrecompute(originLink, endTime, toArr, filterTime);
                }
            }

            List<ConnectionCandidate> candidates = new ArrayList<>();

            for (int j = sliceStart; j < sliceEnd; j++) {
                if (i == j) continue;

                // Basic checks
                Id<Link> from = lastDests[i];
                Id<Link> to = firstOrigins[j];
                if (from == null || to == null) continue;

                // Disjoint requests check
                if (!Collections.disjoint(requestSets.get(i), requestSets.get(j))) {
                    continue;
                }

                // Network routing
                TravelSegment connection = networkCache.getSegment(from, to, endTime);
                if (!connection.isReachable()) continue;

                double arrivalTime = endTime + connection.getTravelTime();
                // Arrival at successor start must be feasible? 
                // Actually, successor starts at startTimes[j].
                // We arrive at 'arrivalTime'.
                // If arrivalTime > startTimes[j], we are late.
                if (arrivalTime > startTimes[j]) continue;
                
                // Also check if we arrive too early? (Wait time constraint?)
                // The filterTime constrains (startTimes[j] - endTime).
                // So the gap is bounded.

                if (Double.isFinite(filterDistanceFactor)
                        && connection.getDistance() > rideDistances[i] * filterDistanceFactor) {
                    continue;
                }

                double idlingTime = startTimes[j] - arrivalTime;
                candidates.add(new ConnectionCandidate(sortedByStart.get(j).getIndex(), to, connection.getDistance(), idlingTime, connection.getTravelTime()));
            }

            // Prune to Top-K closest successors
            if (maxSuccessors > 0 && candidates.size() > maxSuccessors) {
                // Keep smallest score (distance * idling)
                // "Short distance doesn't help us if we have a low idling time" -> interpreted as minimizing the product
                // We use Math.max(1.0, idling) to ensure distance is still the primary factor when idling is near zero
                candidates.sort(Comparator.comparingDouble(ConnectionCandidate::getScore));
                candidates = candidates.subList(0, maxSuccessors);
            }

            // Compute mean outgoing repositioning travel time over the (post-pruning) candidate set
            double meanReposTime = -1.0;
            if (!candidates.isEmpty()) {
                double sum = 0.0;
                for (ConnectionCandidate c : candidates) {
                    sum += c.travelTime();
                }
                meanReposTime = sum / candidates.size();
            }
            reposTimeMeans.put(sortedByStart.get(i).getIndex(), meanReposTime);

            List<Integer> succIds = candidates.stream().map(c -> c.rideId).collect(Collectors.toList());
            successors.put(sortedByStart.get(i).getIndex(), succIds);
        });

		long routingTime = System.currentTimeMillis() - routingStartTime;
		log.info("    Network routing completed in {} ms", routingTime);

		log.info("    Deriving predecessor relationships...");
        // Derive predecessors from successors
        for (Map.Entry<Integer, List<Integer>> entry : successors.entrySet()) {
            int predId = entry.getKey();
            for (int succId : entry.getValue()) {
                predecessors.computeIfAbsent(succId, k -> new ArrayList<>()).add(predId);
            }
        }

		log.info("    Converting to arrays...");
        Map<Integer, int[]> predArrays = new HashMap<>();
        Map<Integer, int[]> succArrays = new HashMap<>();
        predecessors.forEach((rideId, list) -> {
            Collections.sort(list);
            predArrays.put(rideId, list.stream().mapToInt(Integer::intValue).toArray());
        });
        successors.forEach((rideId, list) -> {
            // Already sorted by distance if pruned, but let's sort by ID for consistency or keep distance order?
            // The original code sorted by ID. Let's stick to ID sort for deterministic output.
            Collections.sort(list);
            succArrays.put(rideId, list.stream().mapToInt(Integer::intValue).toArray());
        });

		int totalPreds = predArrays.values().stream().mapToInt(arr -> arr.length).sum();
		log.info("    Found {} predecessor connections", totalPreds);

        return new PredSucc(predArrays, succArrays, reposTimeMeans);
    }

    private record ConnectionCandidate(int rideId, Id<Link> toLink, double distance, double idlingTime, double travelTime) {
        double getScore() {
            return distance * Math.max(1.0, idlingTime);
        }
    }

    private int resolveParallelism() {
        int configured = config.getHeuristicsProcessCount();
        if (configured == 1) {
            return 1;
        }
        if (configured <= 0) {
            return Math.max(1, Runtime.getRuntime().availableProcessors());
        }
        return configured;
    }

	private static String formatDuration(double seconds) {
		if (!Double.isFinite(seconds) || seconds <= 0) {
			return "0s";
		}

		long totalSeconds = (long) Math.ceil(seconds);
		long hours = TimeUnit.SECONDS.toHours(totalSeconds);
		long minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60;
		long secs = totalSeconds % 60;

		if (hours > 0) {
			return String.format("%dh%02dm%02ds", hours, minutes, secs);
		}
		if (minutes > 0) {
			return String.format("%dm%02ds", minutes, secs);
		}
		return String.format("%ds", secs);
	}

    private double factorial(int n) {
        double result = 1.0;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }

    private record PredSucc(
        Map<Integer, int[]> predecessors,
        Map<Integer, int[]> successors,
        Map<Integer, Double> reposTimeMeans
    ) {}
}
