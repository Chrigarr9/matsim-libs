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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.demand.BudgetToConstraintsCalculator;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;

/**
 * Post-process ExMAS rides to enrich them with:
 * - maxCosts: maximum fare per passenger before utility equals best alternative
 * - shapleyValues: distance contribution per passenger
 * - predecessors/successors: feasible ride sequencing edges
 */
public final class RidePostProcessor {
    private static final Logger log = LogManager.getLogger(RidePostProcessor.class);

    private final ExMasConfigGroup config;
    private final MatsimNetworkCache networkCache;
    private final BudgetToConstraintsCalculator budgetToConstraintsCalculator;
    private final Population population;

    public RidePostProcessor(ExMasConfigGroup config, MatsimNetworkCache networkCache, 
                            BudgetToConstraintsCalculator budgetToConstraintsCalculator, Population population) {
        this.config = config;
        this.networkCache = networkCache;
        this.budgetToConstraintsCalculator = budgetToConstraintsCalculator;
        this.population = population;
    }

    public List<Ride> process(List<Ride> rides) {
        if (rides == null || rides.isEmpty()) {
            return rides;
        }

        Map<Integer, double[]> maxCostByRide = computeMaxCosts(rides);
        Map<Integer, double[]> shapleyByRide = config.isCalcShapleyValues() ? computeShapleyValues(rides) : Collections.emptyMap();
        PredSucc predsAndSuccs = config.isCalcPredecessors() ? computePredecessors(rides) : new PredSucc(Collections.emptyMap(), Collections.emptyMap());

        List<Ride> enriched = new ArrayList<>(rides.size());
        for (Ride ride : rides) {
            double[] shapley = shapleyByRide.get(ride.getIndex());
            int[] preds = predsAndSuccs.predecessors().getOrDefault(ride.getIndex(), new int[0]);
            int[] succs = predsAndSuccs.successors().getOrDefault(ride.getIndex(), new int[0]);
            double[] maxCosts = maxCostByRide.get(ride.getIndex());

            Ride rebuilt = Ride.builder()
                    .index(ride.getIndex())
                    .degree(ride.getDegree())
                    .kind(ride.getKind())
                    .requests(ride.getRequests())
                    .originsOrderedRequests(ride.getOriginsOrderedRequests())
                    .destinationsOrderedRequests(ride.getDestinationsOrderedRequests())
                    .passengerTravelTimes(ride.getPassengerTravelTimes())
                    .passengerDistances(ride.getPassengerDistances())
                    .passengerNetworkUtilities(ride.getPassengerNetworkUtilities())
                    .delays(ride.getDelays())
                    .detours(ride.getDetours())
                    .remainingBudgets(ride.getRemainingBudgets())
                    .maxCosts(maxCosts)
                    .connectionTravelTimes(ride.getConnectionTravelTimes())
                    .connectionDistances(ride.getConnectionDistances())
                    .connectionNetworkUtilities(ride.getConnectionNetworkUtilities())
                    .startTime(ride.getStartTime())
                    .shapleyValues(shapley)
                    .predecessors(preds)
                    .successors(succs)
                    .build();
            enriched.add(rebuilt);
        }

        return enriched;
    }

    private Map<Integer, double[]> computeMaxCosts(List<Ride> rides) {
        Map<Integer, double[]> maxCostByRide = new HashMap<>(rides.size());
        for (Ride ride : rides) {
            double[] remainingBudgets = ride.getRemainingBudgets();
            double[] maxCosts = new double[ride.getDegree()];
            DrtRequest[] requests = ride.getRequests();
            double[] travelTimes = ride.getPassengerTravelTimes();
            double[] distances = ride.getPassengerDistances();
            
            for (int i = 0; i < ride.getDegree(); i++) {
                DrtRequest request = requests[i];
                Person person = population.getPersons().get(request.personId);
                double budget = (remainingBudgets != null && remainingBudgets.length > i) ? remainingBudgets[i] : 0.0;
                
                if (person == null) {
                    maxCosts[i] = 0.0;
                    continue;
                }
                
                // Call budgetToMaxCost for each passenger (aligns with other budget methods)
                maxCosts[i] = budgetToConstraintsCalculator.budgetToMaxCost(
                    budget, 
                    person, 
                    travelTimes[i], 
                    distances[i]
                );
            }
            
            maxCostByRide.put(ride.getIndex(), maxCosts);
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
        Map<Integer, Integer> rideIndexToPosition = new HashMap<>(total);

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
            rideIndexToPosition.put(ride.getIndex(), idx);
        }

        int[] sortedByEnd = IntStream.range(0, total)
                .boxed()
                .sorted(Comparator.comparingDouble(i -> endTimes[i]))
                .mapToInt(Integer::intValue)
                .toArray();
        double[] endTimesSorted = Arrays.stream(sortedByEnd).mapToDouble(i -> endTimes[i]).toArray();

        double filterTime = config.getPredecessorsFilterTime() != null ? config.getPredecessorsFilterTime() : Double.POSITIVE_INFINITY;
        double filterDistanceFactor = config.getPredecessorsFilterDistanceFactor() != null
                ? config.getPredecessorsFilterDistanceFactor()
                : Double.POSITIVE_INFINITY;

        Map<Integer, List<Integer>> predecessors = new ConcurrentHashMap<>();
        Map<Integer, List<Integer>> successors = new ConcurrentHashMap<>();

        int parallelism = resolveParallelism();
        IntStream stream = IntStream.range(0, total);
        if (parallelism > 1) {
            stream = stream.parallel();
        }

        stream.forEach(j -> {
            double currentStart = startTimes[j];
            double minEndTime = currentStart - filterTime;
            double maxEndTime = currentStart;

            int sliceStart = Arrays.binarySearch(endTimesSorted, minEndTime);
            if (sliceStart < 0) {
                sliceStart = -sliceStart - 1;
            }
            int sliceEnd = Arrays.binarySearch(endTimesSorted, maxEndTime);
            if (sliceEnd < 0) {
                sliceEnd = -sliceEnd - 1;
            } else {
                sliceEnd += 1; // upper bound
            }

            List<Integer> preds = new ArrayList<>();
            for (int idx = sliceStart; idx < sliceEnd; idx++) {
                int i = sortedByEnd[idx];
                if (i >= j) {
                    continue;
                }

                Id<Link> from = lastDests[i];
                Id<Link> to = firstOrigins[j];
                if (from == null || to == null) {
                    continue;
                }

                TravelSegment connection = networkCache.getSegment(from, to, endTimes[i]);
                if (!connection.isReachable()) {
                    continue;
                }

                double arrivalTime = endTimes[i] + connection.getTravelTime();
                if (arrivalTime < minEndTime || arrivalTime > maxEndTime) {
                    continue;
                }

                if (Double.isFinite(filterDistanceFactor)
                        && connection.getDistance() > rideDistances[i] * filterDistanceFactor) {
                    continue;
                }

                if (!Collections.disjoint(requestSets.get(i), requestSets.get(j))) {
                    continue;
                }

                preds.add(sortedByStart.get(i).getIndex());
            }

            Collections.sort(preds);
            predecessors.put(sortedByStart.get(j).getIndex(), preds);
        });

        // Derive successors from predecessors
        for (Map.Entry<Integer, List<Integer>> entry : predecessors.entrySet()) {
            int rideId = entry.getKey();
            for (int predId : entry.getValue()) {
                successors.computeIfAbsent(predId, k -> new ArrayList<>()).add(rideId);
            }
        }

        Map<Integer, int[]> predArrays = new HashMap<>();
        Map<Integer, int[]> succArrays = new HashMap<>();
        predecessors.forEach((rideId, list) -> predArrays.put(rideId, list.stream().mapToInt(Integer::intValue).toArray()));
        successors.forEach((rideId, list) -> {
            Collections.sort(list);
            succArrays.put(rideId, list.stream().mapToInt(Integer::intValue).toArray());
        });

        return new PredSucc(predArrays, succArrays);
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

    private double factorial(int n) {
        double result = 1.0;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }

    private record PredSucc(Map<Integer, int[]> predecessors, Map<Integer, int[]> successors) {}
}
