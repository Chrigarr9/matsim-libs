package org.matsim.contrib.demand_extraction.algorithm.generation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideVariant;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.stops.StopFinder;
import org.matsim.contrib.demand_extraction.algorithm.stops.WalkingDistanceCalculator;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Generates stop-to-stop ride variants from existing door-to-door rides.
 *
 * For each door-to-door ride with degree >= 2:
 * 1. Find optimal shared pickup stop that all passengers can walk to
 * 2. Find optimal shared dropoff stop that all passengers can walk from
 * 3. Calculate actual walk distances for each passenger
 * 4. Route the stop-to-stop segment
 * 5. Validate budget with actual walk distances
 *
 * Rides that cannot be converted (no valid stops found, budget exceeded)
 * are skipped and statistics are logged.
 *
 * Uses parallel processing for efficiency, similar to PairGenerator.
 */
public final class StopBasedRideGenerator {

	private static final Logger log = LogManager.getLogger(StopBasedRideGenerator.class);

	private final MatsimNetworkCache networkCache;
	private final StopFinder stopFinder;
	private final WalkingDistanceCalculator walkCalculator;
	private final BudgetValidator budgetValidator;
	private final ExMasConfigGroup config;
	private final WalkBudgetProvider walkBudgetProvider;
	private final boolean useParallel;

	// Statistics
	private final AtomicInteger totalProcessed = new AtomicInteger();
	private final AtomicInteger successfulConversions = new AtomicInteger();
	private final AtomicInteger failedNoPickupStop = new AtomicInteger();
	private final AtomicInteger failedNoDropoffStop = new AtomicInteger();
	private final AtomicInteger failedWalkDistanceExceeded = new AtomicInteger();
	private final AtomicInteger failedBudgetExceeded = new AtomicInteger();
	private final AtomicInteger skippedSingleRides = new AtomicInteger();
	private final AtomicLong totalAccessWalkDistance = new AtomicLong();
	private final AtomicLong totalEgressWalkDistance = new AtomicLong();
	private final AtomicLong totalPassengers = new AtomicLong();

	public StopBasedRideGenerator(
			MatsimNetworkCache networkCache,
			StopFinder stopFinder,
			WalkingDistanceCalculator walkCalculator,
			BudgetValidator budgetValidator,
			ExMasConfigGroup config,
			int algorithmProcessCount,
			WalkBudgetProvider walkBudgetProvider) {
		this.networkCache = networkCache;
		this.stopFinder = stopFinder;
		this.walkCalculator = walkCalculator;
		this.budgetValidator = budgetValidator;
		this.config = config;
		this.walkBudgetProvider = walkBudgetProvider;
		this.useParallel = algorithmProcessCount != 1;
		if (config.isEnableBudgetAwareConstraints() && walkBudgetProvider == null) {
			throw new IllegalArgumentException(
					"WalkBudgetProvider must not be null when enableBudgetAwareConstraints=true");
		}
	}

	/**
	 * Generate stop-to-stop variants for all eligible door-to-door rides.
	 *
	 * @param doorToDoorRides List of door-to-door rides to convert
	 * @param startIndex Starting index for new rides (to avoid collisions)
	 * @return List of stop-to-stop rides (may be smaller than input)
	 */
	public List<Ride> generateStopBasedRides(List<Ride> doorToDoorRides, int startIndex) {
		log.info("Generating stop-based rides from {} door-to-door rides using {} strategy [{}]...",
				doorToDoorRides.size(), stopFinder.getName(), useParallel ? "parallel" : "sequential");
		long startTime = System.currentTimeMillis();

		resetStatistics();

		// Filter to rides with degree >= 2 (single rides stay door-to-door)
		List<Ride> eligibleRides = doorToDoorRides.stream()
				.filter(ride -> {
					if (ride.getDegree() < 2) {
						skippedSingleRides.incrementAndGet();
						return false;
					}
					return true;
				})
				.collect(Collectors.toList());

		int total = eligibleRides.size();
		log.info("  {} rides eligible for stop-based conversion (degree >= 2)", total);

		if (total == 0) {
			log.info("  No eligible rides to convert");
			return new ArrayList<>();
		}

		AtomicInteger processedCount = new AtomicInteger(0);

		// Phase 1: Parallel conversion (without final indices)
		IntStream indexStream = IntStream.range(0, total);
		if (useParallel) {
			indexStream = indexStream.parallel();
		}

		List<ConversionCandidate> candidates = indexStream
				.mapToObj(i -> {
					Ride doorToDoor = eligibleRides.get(i);
					ConversionCandidate candidate = convertToStopBased(doorToDoor);

					int processed = processedCount.incrementAndGet();
					if (processed == total || (int)(100.0 * processed / total) > (int)(100.0 * (processed - 1) / total)) {
						double percent = (processed * 100.0) / total;
						log.info("  Stop-based conversion progress: {}/{} ({}%)",
								processed, total, String.format("%.1f", percent));
					}

					return candidate;
				})
				.filter(c -> c != null)
				.sorted(ConversionCandidate.COMPARATOR)
				.collect(Collectors.toList());

		// Phase 2: Assign indices sequentially
		List<Ride> stopBasedRides = new ArrayList<>(candidates.size());
		int nextIndex = startIndex;
		for (ConversionCandidate candidate : candidates) {
			Ride stopBasedRide = candidate.toRide(nextIndex++);
			stopBasedRides.add(stopBasedRide);
		}

		long elapsed = System.currentTimeMillis() - startTime;
		logStatistics(elapsed, total);

		return stopBasedRides;
	}

	/**
	 * Attempt to convert a single door-to-door ride to stop-based.
	 *
	 * <p>Dispatches to {@link #convertToStopBasedBudgetAware} when
	 * {@code enableBudgetAwareConstraints=true}, otherwise falls back to
	 * {@link #convertToStopBasedLegacy}.
	 *
	 * @param doorToDoor The door-to-door ride to convert
	 * @return ConversionCandidate if successful, null if conversion failed
	 */
	private ConversionCandidate convertToStopBased(Ride doorToDoor) {
		if (config.isEnableBudgetAwareConstraints()) {
			return convertToStopBasedBudgetAware(doorToDoor);
		} else {
			return convertToStopBasedLegacy(doorToDoor);
		}
	}

	/**
	 * Legacy symmetric stop search. Both Phase A (pickup) and Phase B (dropoff)
	 * use the same per-passenger walk cap derived from
	 * {@link #deriveBudgetBasedMaxWalk}.
	 */
	private ConversionCandidate convertToStopBasedLegacy(Ride doorToDoor) {
		totalProcessed.incrementAndGet();

		DrtRequest[] requests = doorToDoor.getRequests();
		int degree = doorToDoor.getDegree();

		// Step 1: Collect passenger origins and calculate max walk distances
		List<Coord> origins = new ArrayList<>(degree);
		double[] maxWalkDistances = new double[degree];
		double hardCap = config.getMaxWalkDistanceMeters();

		for (int i = 0; i < degree; i++) {
			origins.add(new Coord(requests[i].originX, requests[i].originY));
			// Max walk = min(budget-based, hard cap)
			// Use remaining budget from D2D ride to derive walk budget
			double budgetBasedMax = deriveBudgetBasedMaxWalk(requests[i], doorToDoor.getRemainingBudgets()[i]);
			maxWalkDistances[i] = Math.min(budgetBasedMax, hardCap);
		}

		// Step 2: Find shared pickup stop
		Optional<StopLocation> pickupStopOpt = stopFinder.findStop(
				origins, maxWalkDistances, doorToDoor.getStartTime());

		if (pickupStopOpt.isEmpty()) {
			failedNoPickupStop.incrementAndGet();
			log.trace("Ride {} rejected: no valid pickup stop found", doorToDoor.getIndex());
			return null;
		}
		StopLocation pickupStop = pickupStopOpt.get();

		// Step 3: Collect passenger destinations
		List<Coord> destinations = new ArrayList<>(degree);
		for (int i = 0; i < degree; i++) {
			destinations.add(new Coord(requests[i].destinationX, requests[i].destinationY));
		}

		// Step 4: Find shared dropoff stop
		// Estimate arrival time at dropoff (use D2D ride time as approximation)
		double estimatedDropoffTime = doorToDoor.getStartTime() + doorToDoor.getRideTravelTime();
		Optional<StopLocation> dropoffStopOpt = stopFinder.findStop(
				destinations, maxWalkDistances, estimatedDropoffTime);

		if (dropoffStopOpt.isEmpty()) {
			failedNoDropoffStop.incrementAndGet();
			log.trace("Ride {} rejected: no valid dropoff stop found", doorToDoor.getIndex());
			return null;
		}
		StopLocation dropoffStop = dropoffStopOpt.get();

		// Step 5: Calculate actual walk distances for each passenger
		double[] accessWalkDistances = new double[degree];
		double[] egressWalkDistances = new double[degree];
		Link pickupLink = networkCache.getNetwork().getLinks().get(pickupStop.getLinkId());
		Link dropoffLink = networkCache.getNetwork().getLinks().get(dropoffStop.getLinkId());

		for (int i = 0; i < degree; i++) {
			accessWalkDistances[i] = walkCalculator.calculateWalkDistance(origins.get(i), pickupLink);
			egressWalkDistances[i] = walkCalculator.calculateWalkDistance(destinations.get(i), dropoffLink);

			// Validate against hard cap
			double totalWalk = accessWalkDistances[i] + egressWalkDistances[i];
			if (totalWalk > hardCap * 2) { // Allow access + egress each up to hardCap
				failedWalkDistanceExceeded.incrementAndGet();
				log.trace("Ride {} rejected: passenger {} total walk {:.1f}m > cap {:.1f}m",
						doorToDoor.getIndex(), i, totalWalk, hardCap * 2);
				return null;
			}
		}

		// Step 6: Route the stop-to-stop segment
		TravelSegment stopToStopSegment = networkCache.getSegment(
				pickupStop.getLinkId(),
				dropoffStop.getLinkId(),
				doorToDoor.getStartTime());

		if (stopToStopSegment == null) {
			log.trace("Ride {} rejected: cannot route between stops", doorToDoor.getIndex());
			failedNoPickupStop.incrementAndGet(); // Use same counter for routing failures
			return null;
		}

		// Step 7: Calculate passenger metrics for stop-based ride
		double[] passengerTravelTimes = new double[degree];
		double[] passengerDistances = new double[degree];
		double[] passengerNetworkUtilities = new double[degree];
		double[] delays = new double[degree];
		double[] detours = new double[degree];

		// In stop-to-stop, all passengers share the same in-vehicle segment
		double inVehicleTime = stopToStopSegment.getTravelTime();
		double inVehicleDistance = stopToStopSegment.getDistance();
		double inVehicleUtility = stopToStopSegment.getNetworkUtility();

		for (int i = 0; i < degree; i++) {
			// Passenger travel time = access walk time + in-vehicle + egress walk time
			double walkSpeed = config.getWalkSpeedMps();
			double accessTime = accessWalkDistances[i] / walkSpeed;
			double egressTime = egressWalkDistances[i] / walkSpeed;

			passengerTravelTimes[i] = accessTime + inVehicleTime + egressTime;
			passengerDistances[i] = accessWalkDistances[i] + inVehicleDistance + egressWalkDistances[i];
			passengerNetworkUtilities[i] = inVehicleUtility; // Walk utility handled separately

			// Delay = total travel time - direct travel time
			delays[i] = passengerTravelTimes[i] - requests[i].getTravelTime();

			// Detour factor
			detours[i] = requests[i].getTravelTime() > 0
					? passengerTravelTimes[i] / requests[i].getTravelTime()
					: 1.0;
		}

		// Step 8: Validate budgets with actual walk distances
		// Calculate remaining budgets using stop-based metrics
		double[] remainingBudgets = calculateStopBasedBudgets(
				requests, delays, passengerTravelTimes, passengerDistances,
				accessWalkDistances, egressWalkDistances);

		if (remainingBudgets == null) {
			failedBudgetExceeded.incrementAndGet();
			log.trace("Ride {} rejected: budget validation failed", doorToDoor.getIndex());
			return null;
		}

		// Success! Update statistics
		successfulConversions.incrementAndGet();
		totalPassengers.addAndGet(degree);
		for (int i = 0; i < degree; i++) {
			totalAccessWalkDistance.addAndGet((long) (accessWalkDistances[i] * 100)); // Store as cm for precision
			totalEgressWalkDistance.addAndGet((long) (egressWalkDistances[i] * 100));
		}

		// Create conversion candidate (will be assigned index later)
		return new ConversionCandidate(
				doorToDoor,
				pickupStop,
				dropoffStop,
				accessWalkDistances,
				egressWalkDistances,
				passengerTravelTimes,
				passengerDistances,
				passengerNetworkUtilities,
				delays,
				detours,
				remainingBudgets,
				new double[]{stopToStopSegment.getTravelTime()},
				new double[]{stopToStopSegment.getDistance()},
				new double[]{stopToStopSegment.getNetworkUtility()},
				doorToDoor.getStartTime()
		);
	}

	/**
	 * Asymmetric two-phase stop search using per-passenger budget envelopes.
	 *
	 * <p>Phase A (pickup): cap = min(2·mid, hardCap) per passenger, where
	 * {@code mid = budgetToConstraints.budgetToMaxWalkDistance(remainingBudget, null, request, actualTT, actualDist, delay)}.
	 *
	 * <p>Phase B (dropoff): cap = max(0, min(2·mid − accessWalk[i], hardCap)) per
	 * passenger, so passengers who walked less on access can walk farther on egress.
	 */
	private ConversionCandidate convertToStopBasedBudgetAware(Ride doorToDoor) {
		totalProcessed.incrementAndGet();

		DrtRequest[] requests = doorToDoor.getRequests();
		int degree = doorToDoor.getDegree();
		double hardCap = config.getMaxWalkDistanceMeters();
		double[] remainingBudgetsD2D = doorToDoor.getRemainingBudgets();
		double[] passengerTravelTimesD2D = doorToDoor.getPassengerTravelTimes();
		double[] passengerDistancesD2D = doorToDoor.getPassengerDistances();
		double[] delaysD2D = doorToDoor.getDelays();

		// Step 1: Compute per-passenger total walk budget envelope (2·mid)
		double[] maxTotalWalk = new double[degree];
		for (int i = 0; i < degree; i++) {
			double mid = walkBudgetProvider.getMid(
					remainingBudgetsD2D[i], requests[i],
					passengerTravelTimesD2D[i], passengerDistancesD2D[i], delaysD2D[i]);
			maxTotalWalk[i] = 2.0 * mid;
		}

		// Step 2 (Phase A): Find shared pickup stop — per-pax access cap = min(maxTotalWalk[i], hardCap)
		List<Coord> origins = new ArrayList<>(degree);
		double[] accessCaps = new double[degree];
		for (int i = 0; i < degree; i++) {
			origins.add(new Coord(requests[i].originX, requests[i].originY));
			accessCaps[i] = Math.min(maxTotalWalk[i], hardCap);
		}

		Optional<StopLocation> pickupStopOpt = stopFinder.findStop(
				origins, accessCaps, doorToDoor.getStartTime());

		if (pickupStopOpt.isEmpty()) {
			failedNoPickupStop.incrementAndGet();
			log.trace("Ride {} rejected: no valid pickup stop found (budget-aware)", doorToDoor.getIndex());
			return null;
		}
		StopLocation pickupStop = pickupStopOpt.get();

		// Step 3: Measure actual access walk distances
		Link pickupLink = networkCache.getNetwork().getLinks().get(pickupStop.getLinkId());
		double[] accessWalkDistances = new double[degree];
		for (int i = 0; i < degree; i++) {
			accessWalkDistances[i] = walkCalculator.calculateWalkDistance(origins.get(i), pickupLink);
		}

		// Step 4 (Phase B): Find shared dropoff stop — egress cap = max(0, min(maxTotalWalk[i] - accessWalk[i], hardCap))
		List<Coord> destinations = new ArrayList<>(degree);
		double[] egressCaps = new double[degree];
		for (int i = 0; i < degree; i++) {
			destinations.add(new Coord(requests[i].destinationX, requests[i].destinationY));
			egressCaps[i] = Math.max(0.0, Math.min(maxTotalWalk[i] - accessWalkDistances[i], hardCap));
		}

		double estimatedDropoffTime = doorToDoor.getStartTime() + doorToDoor.getRideTravelTime();
		Optional<StopLocation> dropoffStopOpt = stopFinder.findStop(
				destinations, egressCaps, estimatedDropoffTime);

		if (dropoffStopOpt.isEmpty()) {
			failedNoDropoffStop.incrementAndGet();
			log.trace("Ride {} rejected: no valid dropoff stop found (budget-aware)", doorToDoor.getIndex());
			return null;
		}
		StopLocation dropoffStop = dropoffStopOpt.get();

		// Step 5: Measure actual egress walk distances and validate hard cap per leg
		Link dropoffLink = networkCache.getNetwork().getLinks().get(dropoffStop.getLinkId());
		double[] egressWalkDistances = new double[degree];
		for (int i = 0; i < degree; i++) {
			egressWalkDistances[i] = walkCalculator.calculateWalkDistance(destinations.get(i), dropoffLink);

			// Validate against hard cap (each leg individually)
			if (accessWalkDistances[i] > hardCap || egressWalkDistances[i] > hardCap) {
				failedWalkDistanceExceeded.incrementAndGet();
				log.trace("Ride {} rejected: passenger {} walk exceeds hard cap",
						doorToDoor.getIndex(), i);
				return null;
			}
		}

		// Step 6: Route the stop-to-stop segment
		TravelSegment stopToStopSegment = networkCache.getSegment(
				pickupStop.getLinkId(),
				dropoffStop.getLinkId(),
				doorToDoor.getStartTime());

		if (stopToStopSegment == null) {
			log.trace("Ride {} rejected: cannot route between stops (budget-aware)", doorToDoor.getIndex());
			failedNoPickupStop.incrementAndGet();
			return null;
		}

		// Step 7: Calculate passenger metrics
		double[] passengerTravelTimes = new double[degree];
		double[] passengerDistances = new double[degree];
		double[] passengerNetworkUtilities = new double[degree];
		double[] delays = new double[degree];
		double[] detours = new double[degree];

		double inVehicleTime = stopToStopSegment.getTravelTime();
		double inVehicleDistance = stopToStopSegment.getDistance();
		double inVehicleUtility = stopToStopSegment.getNetworkUtility();

		for (int i = 0; i < degree; i++) {
			double walkSpeed = config.getWalkSpeedMps();
			double accessTime = accessWalkDistances[i] / walkSpeed;
			double egressTime = egressWalkDistances[i] / walkSpeed;

			passengerTravelTimes[i] = accessTime + inVehicleTime + egressTime;
			passengerDistances[i] = accessWalkDistances[i] + inVehicleDistance + egressWalkDistances[i];
			passengerNetworkUtilities[i] = inVehicleUtility;

			delays[i] = passengerTravelTimes[i] - requests[i].getTravelTime();
			detours[i] = requests[i].getTravelTime() > 0
					? passengerTravelTimes[i] / requests[i].getTravelTime()
					: 1.0;
		}

		// Step 8: Validate budgets with actual walk distances
		double[] remainingBudgets = calculateStopBasedBudgets(
				requests, delays, passengerTravelTimes, passengerDistances,
				accessWalkDistances, egressWalkDistances);

		if (remainingBudgets == null) {
			failedBudgetExceeded.incrementAndGet();
			log.trace("Ride {} rejected: budget validation failed (budget-aware)", doorToDoor.getIndex());
			return null;
		}

		successfulConversions.incrementAndGet();
		totalPassengers.addAndGet(degree);
		for (int i = 0; i < degree; i++) {
			totalAccessWalkDistance.addAndGet((long) (accessWalkDistances[i] * 100));
			totalEgressWalkDistance.addAndGet((long) (egressWalkDistances[i] * 100));
		}

		return new ConversionCandidate(
				doorToDoor,
				pickupStop,
				dropoffStop,
				accessWalkDistances,
				egressWalkDistances,
				passengerTravelTimes,
				passengerDistances,
				passengerNetworkUtilities,
				delays,
				detours,
				remainingBudgets,
				new double[]{stopToStopSegment.getTravelTime()},
				new double[]{stopToStopSegment.getDistance()},
				new double[]{stopToStopSegment.getNetworkUtility()},
				doorToDoor.getStartTime()
		);
	}

	/**
	 * Derive budget-based maximum walk distance for a passenger.
	 * Uses remaining budget from D2D ride and walk disutility parameters.
	 */
	private double deriveBudgetBasedMaxWalk(DrtRequest request, double remainingBudget) {
		// Use the request's pre-calculated maxWalkDistance if available
		if (request.maxWalkDistance > 0) {
			return request.maxWalkDistance;
		}

		// Otherwise, derive from remaining budget using walk utility parameters
		// This is a simplified calculation - the actual calculation should use
		// BudgetToConstraintsCalculator.budgetToMaxWalkDistance()
		// For now, use a conservative estimate based on walking speed
		double walkSpeed = config.getWalkSpeedMps();
		double maxWalkTime = Math.min(remainingBudget / walkSpeed, 600); // Max 10 minutes walk
		return maxWalkTime * walkSpeed;
	}

	/**
	 * Calculate remaining budgets for stop-based ride with actual walk distances.
	 *
	 * @return Array of remaining budgets, or null if any passenger has negative budget
	 */
	private double[] calculateStopBasedBudgets(
			DrtRequest[] requests,
			double[] delays,
			double[] travelTimes,
			double[] distances,
			double[] accessWalkDistances,
			double[] egressWalkDistances) {

		double[] budgets = new double[requests.length];

		for (int i = 0; i < requests.length; i++) {
			// Calculate DRT score with actual walk distances
			double drtScore = budgetValidator.calculateDrtScoreWithWalks(
					requests[i],
					delays[i],
					travelTimes[i],
					distances[i],
					accessWalkDistances[i],
					egressWalkDistances[i]);

			budgets[i] = drtScore - requests[i].bestModeScore;

			if (budgets[i] < 0) {
				log.trace("Passenger {} budget negative: {:.2f}", requests[i].index, budgets[i]);
				return null;
			}
		}

		return budgets;
	}

	private void resetStatistics() {
		totalProcessed.set(0);
		successfulConversions.set(0);
		failedNoPickupStop.set(0);
		failedNoDropoffStop.set(0);
		failedWalkDistanceExceeded.set(0);
		failedBudgetExceeded.set(0);
		skippedSingleRides.set(0);
		totalAccessWalkDistance.set(0);
		totalEgressWalkDistance.set(0);
		totalPassengers.set(0);
	}

	private void logStatistics(long elapsedMs, int total) {
		int success = successfulConversions.get();
		double conversionRate = total > 0 ? (success * 100.0) / total : 0;

		log.info("Stop-based ride generation completed in {}ms", elapsedMs);
		log.info("  Conversion rate: {}/{} ({:.1f}%)", success, total, conversionRate);
		log.info("  Skipped (degree 1): {}", skippedSingleRides.get());
		log.info("  Failed - no pickup stop: {}", failedNoPickupStop.get());
		log.info("  Failed - no dropoff stop: {}", failedNoDropoffStop.get());
		log.info("  Failed - walk distance exceeded: {}", failedWalkDistanceExceeded.get());
		log.info("  Failed - budget exceeded: {}", failedBudgetExceeded.get());

		long passengers = totalPassengers.get();
		if (passengers > 0) {
			double avgAccess = (totalAccessWalkDistance.get() / 100.0) / passengers;
			double avgEgress = (totalEgressWalkDistance.get() / 100.0) / passengers;
			log.info("  Average access walk: {:.1f}m", avgAccess);
			log.info("  Average egress walk: {:.1f}m", avgEgress);
			log.info("  Average total walk: {:.1f}m", avgAccess + avgEgress);
		}
	}


	/**
	 * Intermediate candidate holding conversion data before index assignment.
	 */
	private record ConversionCandidate(
			Ride sourceRide,
			StopLocation pickupStop,
			StopLocation dropoffStop,
			double[] accessWalkDistances,
			double[] egressWalkDistances,
			double[] passengerTravelTimes,
			double[] passengerDistances,
			double[] passengerNetworkUtilities,
			double[] delays,
			double[] detours,
			double[] remainingBudgets,
			double[] connectionTravelTimes,
			double[] connectionDistances,
			double[] connectionNetworkUtilities,
			double startTime) {

		static final Comparator<ConversionCandidate> COMPARATOR =
				Comparator.comparingInt(c -> c.sourceRide.getIndex());

		Ride toRide(int index) {
			return Ride.builder()
					.index(index)
					.degree(sourceRide.getDegree())
					.kind(sourceRide.getKind())
					.requests(sourceRide.getRequests())
					.originsOrderedRequests(sourceRide.getOriginsOrderedRequests())
					.destinationsOrderedRequests(sourceRide.getDestinationsOrderedRequests())
					.passengerTravelTimes(passengerTravelTimes)
					.passengerDistances(passengerDistances)
					.passengerNetworkUtilities(passengerNetworkUtilities)
					.delays(delays)
					.detours(detours)
					.remainingBudgets(remainingBudgets)
					.connectionTravelTimes(connectionTravelTimes)
					.connectionDistances(connectionDistances)
					.connectionNetworkUtilities(connectionNetworkUtilities)
					.startTime(startTime)
					.variant(RideVariant.STOP_TO_STOP)
					.pickupStop(pickupStop)
					.dropoffStop(dropoffStop)
					.accessWalkDistances(accessWalkDistances)
					.egressWalkDistances(egressWalkDistances)
					.build();
		}
	}
}
