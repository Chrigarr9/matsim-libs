package org.matsim.contrib.demand_extraction.io;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideStore;
import org.matsim.contrib.demand_extraction.algorithm.domain.HyperPooledRide;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideVariant;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.core.utils.io.IOUtils;

/**
 * Utility class for writing ExMAS data to CSV files.
 *
 * Uses ' | ' as separator for array values within CSV fields and keeps brackets
 * for clarity during parsing: [1, 2, 3] -> [1 | 2 | 3]
 *
 * For rides, request attributes are flattened into arrays, matching the
 * structure of other ride attributes (delays, travel times, etc.).
 */
public final class ExMasCsvWriter {

	private static final Logger log = LogManager.getLogger(ExMasCsvWriter.class);

	// Separator for array values within CSV fields: ' | '
	private static final String ARRAY_SEPARATOR = " | ";

	private ExMasCsvWriter() {
		// Utility class - prevent instantiation
	}

	/**
	 * Write DRT requests to CSV file.
	 *
	 * @param filename output file path
	 * @param requests list of DRT requests
	 * @throws RuntimeException if writing fails
	 */
	public static void writeRequests(String filename, List<DrtRequest> requests) {
		try (BufferedWriter writer = IOUtils.getBufferedWriter(filename)) {
			// Header includes all request attributes
			// Note: baseMode/baseModeScore are the mode we compare DRT against (typically the best available mode)
			// Extension-2 schema additions (requestTag, hubId) are APPENDED at the
			// end of the header — never inserted in the middle — so existing
			// consumers that read by column name or by trailing position keep
			// working.
			writer.write("index,personId,groupId,tripIndex,isCommute,isEducation,budget,requestTime," +
					"originLinkId,destinationLinkId,originX,originY,destinationX,destinationY," +
					"originActivityType,destinationActivityType," +
					"directTravelTime,directDistance,earliestDeparture,latestArrival," +
					"maxTravelTime,maxPositiveDelay,maxNegativeDelay,baseModeScore,baseMode," +
					"carTravelTime,ptTravelTime,ptAccessibility,maxCostPerKm," +
					"originLinkCoordFromX,originLinkCoordFromY,originLinkCoordToX,originLinkCoordToY," +
					"destinationLinkCoordFromX,destinationLinkCoordFromY,destinationLinkCoordToX,destinationLinkCoordToY," +
					"maxWalkDistance,maxWaitTime," +
					"requestTag,hubId," +
					"hubLegRole,transferWaitSeconds,marginalUtilityOfMoney");
			writer.newLine();

			for (DrtRequest req : requests) {
				double maxCostPerKm = req.directDistance > 0
						? req.budget / (req.directDistance / 1000.0)
						: Double.MAX_VALUE;
				writer.write(String.format(java.util.Locale.US,
						"%d,%s,%s,%d,%b,%b,%.4f,%.2f,%s,%s,%.2f,%.2f,%.2f,%.2f,%s,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.4f,%s,%.2f,%.2f,%.4f,%.4f,"
								+ "%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.4f,%.4f,%s,%s,%s,%.2f,%.6f",
						req.index, req.personId, req.groupId, req.tripIndex, req.isCommute, req.isEducation,
						req.budget, req.requestTime,
						req.originLinkId, req.destinationLinkId,
						req.originX, req.originY, req.destinationX, req.destinationY,
						req.originActivityType != null ? req.originActivityType : "",
						req.destinationActivityType != null ? req.destinationActivityType : "",
						req.directTravelTime, req.directDistance,
						req.earliestDeparture, req.latestArrival,
						req.getMaxTravelTime(), req.getMaxPositiveDelay(), req.getMaxNegativeDelay(),
						req.bestModeScore, req.bestMode != null ? req.bestMode : "",
						req.carTravelTime, req.ptTravelTime, req.ptAccessibility, maxCostPerKm,
						req.originLinkCoordFromX, req.originLinkCoordFromY,
						req.originLinkCoordToX, req.originLinkCoordToY,
						req.destinationLinkCoordFromX, req.destinationLinkCoordFromY,
						req.destinationLinkCoordToX, req.destinationLinkCoordToY,
						req.maxWalkDistance, req.maxWaitTime,
						req.requestTag != null ? req.requestTag : "",
						req.hubId != null ? req.hubId : "",
						req.hubLegRole != null ? req.hubLegRole.name() : DrtRequest.HubLegRole.NONE.name(),
						req.transferWaitSeconds, req.marginalUtilityOfMoney));
				writer.newLine();
			}
		} catch (IOException e) {
			throw new RuntimeException("Could not write requests CSV: " + filename, e);
		}
	}

	/**
	 * Write ExMAS rides to CSV file.
	 *
	 * Array fields are formatted as: [val1 | val2 | val3]
	 * This keeps brackets for clarity while using ' | ' as internal separator.
	 *
	 * Request attributes are flattened into arrays with the same order as the
	 * requests array in the ride:
	 * - requestIndices: indices of all passengers
	 * - personIds: person IDs of all passengers
	 * - groupIds: group IDs of all passengers
	 * - requestTimes: request times of all passengers
	 *
	 * @param filename output file path
	 * @param rides    list of ExMAS rides
	 * @throws RuntimeException if writing fails
	 */
	public static void writeRides(String filename, List<Ride> rides) {
		writeRideBatches(filename, rides);
	}

	/**
	 * Write ExMAS rides to CSV file, consuming from a {@link RideStore}.
	 *
	 * <p>Materializes the store into a {@code List<Ride>} at the top and
	 * delegates to {@link #writeRideBatches}. The full list is needed because
	 * {@link #writeRideRows} sorts rows by index — streaming without
	 * materializing is deferred to the stub-backing phase.
	 *
	 * @param filename output file path
	 * @param store    RideStore to read from
	 * @throws RuntimeException if writing fails
	 */
	public static void writeRides(String filename, RideStore store) {
		List<Ride> rides = new ArrayList<>(store.size());
		store.forEachMaterialized(rides::add);
		writeRideBatches(filename, rides);
	}

	/**
	 * Stream a {@link RideStore} to {@code exmas_rides.csv}, materializing and writing one ride at a
	 * time — the full fat list is never held. {@code enrich} is applied to each freshly materialized
	 * ride before it is written (per-ride maxCosts, Shapley-from-map, attached successors); pass
	 * {@link UnaryOperator#identity()} for none.
	 *
	 * <p>NO sort is applied: the caller guarantees the store emits rows in {@link Ride#getIndex()}
	 * order. {@link org.matsim.contrib.demand_extraction.algorithm.bamas.ride.ColumnarRideStore} and
	 * {@code HyperPoolRideStore} both assign {@code index = row position} during
	 * {@link RideStore#forEachMaterialized}, so their output is already index-ordered. Do not use this
	 * for a store whose emission order differs from index order — use {@link #writeRides(String,
	 * RideStore)} (which sorts) instead.
	 *
	 * @param filename output file path
	 * @param store    RideStore to stream from (must emit in index order)
	 * @param enrich   applied to each ride before writing; {@link UnaryOperator#identity()} for none
	 * @throws RuntimeException if writing fails
	 */
	public static void writeRidesStreaming(String filename, RideStore store, UnaryOperator<Ride> enrich) {
		try (BufferedWriter writer = IOUtils.getBufferedWriter(filename)) {
			writeRidesHeader(writer);
			store.forEachMaterialized(ride -> {
				try {
					writeRideRow(writer, enrich.apply(ride));
				} catch (IOException e) {
					throw new java.io.UncheckedIOException(e);
				}
			});
		} catch (IOException | java.io.UncheckedIOException e) {
			throw new RuntimeException("Could not write rides CSV: " + filename, e);
		}
	}

	/**
	 * Parallel variant of {@link #writeRidesStreaming}: splits the store's index range into
	 * {@code parallelism} contiguous chunks, materializes + renders each chunk on its own worker
	 * thread into a per-chunk shard file, then concatenates the shards in chunk order into
	 * {@code filename}. Output is byte-identical to {@link #writeRidesStreaming} — same rows in the
	 * same {@link Ride#getIndex()} order, same writer encoding — only the work is fanned out.
	 *
	 * <p><b>Why this is safe.</b> {@link RideStore#materialize(int)} stamps the post-sort sequential
	 * index on each row independently (no shared write state), and the per-row work it performs —
	 * the cache re-route plus {@code BudgetValidator.validateAndPopulateBudgets} — is the SAME call
	 * sequence {@code BamasRideExtender} already runs across a fixed thread pool, with the byte-golden
	 * determinism gate green. The routing cache is never watermark-evicted during export, so it fills
	 * monotonically and repeated ODs converge to cache hits; the residual cost is re-routing the
	 * segments a resumed journal does not contain (those evicted before pair-chain promotion).
	 *
	 * <p>Shards are written with the same {@link IOUtils#getBufferedWriter} encoding as the
	 * single-threaded path and concatenated by raw byte copy, so the bytes are identical to writing
	 * the whole file on one thread. Chunk 0 emits the header; the rest emit rows only.
	 *
	 * @param filename     output file path (plain CSV; must NOT be gzip — shards are byte-concatenated)
	 * @param store        RideStore providing random-access {@link RideStore#materialize(int)}
	 * @param enrich       applied to each ride before writing; MUST be thread-safe / stateless
	 * @param parallelism  worker thread count; clamped to {@code [1, size]}. {@code <= 1} (or an empty
	 *                     store) falls back to {@link #writeRidesStreaming}.
	 * @throws RuntimeException if any worker fails or the concatenation fails
	 */
	public static void writeRidesStreamingParallel(String filename, RideStore store,
			UnaryOperator<Ride> enrich, int parallelism) {
		int total = store.size();
		int p = Math.max(1, Math.min(parallelism, Math.max(1, total)));
		if (p <= 1 || total == 0) {
			writeRidesStreaming(filename, store, enrich);
			return;
		}

		Path finalPath = Paths.get(filename);
		List<Path> shards = new ArrayList<>(p);
		for (int c = 0; c < p; c++) {
			shards.add(finalPath.resolveSibling(finalPath.getFileName() + ".shard" + c));
		}

		log.info("Parallel rides export: {} rows across {} worker threads", total, p);
		AtomicLong written = new AtomicLong();
		ExecutorService pool = Executors.newFixedThreadPool(p);
		try {
			List<Future<?>> futures = new ArrayList<>(p);
			for (int c = 0; c < p; c++) {
				final int chunk = c;
				final int lo = (int) ((long) total * chunk / p);
				final int hi = (int) ((long) total * (chunk + 1) / p);
				final Path shard = shards.get(c);
				futures.add(pool.submit(() -> {
					try (BufferedWriter w = IOUtils.getBufferedWriter(shard.toString())) {
						if (chunk == 0) {
							writeRidesHeader(w);
						}
						for (int row = lo; row < hi; row++) {
							writeRideRow(w, enrich.apply(store.materialize(row)));
							long n = written.incrementAndGet();
							if (n % 1_000_000 == 0) {
								log.info("  parallel rides export: {} / {} rows written", n, total);
							}
						}
					} catch (IOException e) {
						throw new java.io.UncheckedIOException(e);
					}
				}));
			}
			for (Future<?> f : futures) {
				f.get(); // surface any worker exception (materialize self-check, IO, etc.)
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Parallel rides export interrupted: " + filename, e);
		} catch (ExecutionException e) {
			throw new RuntimeException("Parallel rides export failed: " + filename,
					e.getCause() != null ? e.getCause() : e);
		} finally {
			pool.shutdownNow();
		}

		// Concatenate shards in chunk order via raw byte copy (header-bearing chunk 0 first), then
		// delete them. Raw copy preserves the exact shard bytes, so the result equals a one-thread write.
		try (OutputStream out = Files.newOutputStream(finalPath)) {
			for (Path shard : shards) {
				Files.copy(shard, out);
			}
		} catch (IOException e) {
			throw new RuntimeException("Could not concatenate ride shards into: " + filename, e);
		}
		for (Path shard : shards) {
			try {
				Files.deleteIfExists(shard);
			} catch (IOException ignored) {
				// best-effort cleanup; a leftover shard is harmless
			}
		}
		log.info("Parallel rides export complete: {} rows -> {}", total, filename);
	}

	@SafeVarargs
	public static void writeRideBatches(String filename, List<Ride>... rideBatches) {
		try (BufferedWriter writer = IOUtils.getBufferedWriter(filename)) {
			writeRidesHeader(writer);
			for (List<Ride> rideBatch : rideBatches) {
				if (rideBatch == null || rideBatch.isEmpty()) {
					continue;
				}
				writeRideRows(writer, rideBatch);
			}
		} catch (IOException e) {
			throw new RuntimeException("Could not write rides CSV: " + filename, e);
		}
	}

	private static void writeRidesHeader(BufferedWriter writer) throws IOException {
		// Header with all ride attributes and flattened request attributes
		// Note: predecessors removed (not needed for optimization), successors kept for path cover
		// Stop-based columns added at the end for backward compatibility
		// Extension-2 per-pax columns (requestTags, hubIds) are APPENDED at the
		// END of the header — never inserted in the middle — so existing
		// consumers that read by column name or by trailing position keep
		// working. They mirror the iteration order and `[a | b | c]` format of
		// the existing per-pax `personIds` field.
		// Extension-2 Task 7.2: `peak_pax` (max simultaneous in-vehicle
		// occupancy) is appended AFTER requestTags/hubIds — required by the
		// class-aware path cover (rev-3 §7.4b).
		// chained_timebin Task 4: `reposTimeMeanOutgoing` (mean outgoing repos
		// time across successors) is appended AFTER peak_pax — required by the
		// chained time-bin repos-time resolver.
		// Task 3 (DuckDB tagging): `hubLegRoles` is appended AFTER hubIds and
		// BEFORE peak_pax so downstream DuckDB can tag each pax leg role.
		writer.write("rideIndex,degree,kind,variant," +
				"requestIndices,personIds,groupIds,requestTimes,isCommutes,isEducations," +
				"originsOrdered,destinationsOrdered," +
				"passengerTravelTimes,passengerDistances,passengerDirectDistances,delays,detours,remainingBudgets,maxCosts,maxCostsPerKm,shapleyValues,successors," +
				"startTime,endTime,rideTravelTime,rideDistance," +
				"pickupStopLinkId,pickupStopX,pickupStopY,pickupSnappingPenalty," +
				"dropoffStopLinkId,dropoffStopX,dropoffStopY,dropoffSnappingPenalty," +
				"accessWalkDistances,egressWalkDistances," +
				"requestTags,hubIds,hubLegRoles," +
				"peak_pax,reposTimeMeanOutgoing");
		writer.newLine();
	}

	private static void writeRideRows(BufferedWriter writer, List<Ride> rides) throws IOException {
		List<Ride> sortedRides = rides.stream()
				.sorted(Comparator.comparingInt(Ride::getIndex))
				.toList();

		for (Ride ride : sortedRides) {
			writeRideRow(writer, ride);
		}
	}

	/** Write exactly one ride as one CSV line. Caller controls ordering and buffering. */
	static void writeRideRow(BufferedWriter writer, Ride ride) throws IOException {
				// Flatten request attributes using direct object references
				DrtRequest[] requests = ride.getRequests();

				String reqIndices = formatIntArray(ride.getRequestIndices());
				String personIds = formatStringArray(Arrays.stream(requests)
						.map(r -> r.personId.toString())
						.toArray(String[]::new));
				String groupIds = formatStringArray(Arrays.stream(requests)
						.map(r -> r.groupId)
						.toArray(String[]::new));
				String requestTimes = formatDoubleArray(Arrays.stream(requests)
						.mapToDouble(r -> r.requestTime)
						.toArray());
				String isCommutes = formatBooleanArray(Arrays.stream(requests)
						.map(r -> r.isCommute)
						.toArray(Boolean[]::new));
				String isEducations = formatBooleanArray(Arrays.stream(requests)
						.map(r -> r.isEducation)
						.toArray(Boolean[]::new));

				// Extension-2 per-pax columns: same iteration order as personIds,
				// same `[a | b | c]` format. Null values render as empty inside the list.
				String requestTags = formatStringArray(Arrays.stream(requests)
						.map(r -> r.requestTag != null ? r.requestTag : "")
						.toArray(String[]::new));
				String hubIds = formatStringArray(Arrays.stream(requests)
						.map(r -> r.hubId != null ? r.hubId : "")
						.toArray(String[]::new));
				// Task 3 (DuckDB tagging): per-pax hub leg role, mirrors hubIds exactly.
				String hubLegRoles = formatStringArray(Arrays.stream(requests)
						.map(r -> r.hubLegRole != null ? r.hubLegRole.name() : DrtRequest.HubLegRole.NONE.name())
						.toArray(String[]::new));

				// Format origin/destination sequences
				String origins = formatLinkIdArray(ride.getOriginsOrdered());
				String destinations = formatLinkIdArray(ride.getDestinationsOrdered());

				// Format other arrays
				String pttimes = formatDoubleArray(ride.getPassengerTravelTimes());
				String pdists = formatDoubleArray(ride.getPassengerDistances());
				// Per-passenger DIRECT (requested-trip) distance, in the same passenger
				// order as personIds/passengerDistances. Income + person-km bill on this;
				// passengerDistances (in-vehicle, incl. detour) drives fleet-km / occupancy.
				String pdirects = formatDoubleArray(Arrays.stream(requests)
						.mapToDouble(r -> r.directDistance)
						.toArray());
				String delays = formatDoubleArray(ride.getDelays());
				String detours = formatDoubleArray(ride.getDetours());
				String budgets = ride.getRemainingBudgets() != null
						? formatDoubleArray(ride.getRemainingBudgets())
						: "[]";
				String maxCosts = ride.getMaxCosts() != null ? formatDoubleArray(ride.getMaxCosts()) : "[]";
				String maxCostsPerKm = ride.getMaxCostsPerKm() != null ? formatDoubleArray(ride.getMaxCostsPerKm()) : "[]";
				String shapleyValues = ride.getShapleyValues() != null ? formatDoubleArray(ride.getShapleyValues()) : "[]";
				String successors;
				if (ride.getSuccessors() != null) {
					int[] sortedSucc = ride.getSuccessors().clone();
					Arrays.sort(sortedSucc);
					successors = formatIntArray(sortedSucc);
				} else {
					successors = "[]";
				}

				// Format stop-based columns
				String variant = ride.getVariant().name();
				String pickupLinkId = "", dropoffLinkId = "";
				double pickupX = 0, pickupY = 0, dropoffX = 0, dropoffY = 0;
				double pickupPenalty = 0, dropoffPenalty = 0;
				String accessWalks = "[]", egressWalks = "[]";

				if (ride.getVariant() == RideVariant.STOP_TO_STOP || ride.getVariant() == RideVariant.HYPER_POOLED) {
					StopLocation pickup = ride.getPickupStop();
					StopLocation dropoff = ride.getDropoffStop();

					if (pickup != null) {
						pickupLinkId = pickup.getLinkId().toString();
						pickupX = pickup.getCoord().getX();
						pickupY = pickup.getCoord().getY();
						pickupPenalty = pickup.getSnappingPenalty();
					}
					if (dropoff != null) {
						dropoffLinkId = dropoff.getLinkId().toString();
						dropoffX = dropoff.getCoord().getX();
						dropoffY = dropoff.getCoord().getY();
						dropoffPenalty = dropoff.getSnappingPenalty();
					}

					double[] accessArr = ride.getAccessWalkDistances();
					double[] egressArr = ride.getEgressWalkDistances();
					accessWalks = accessArr != null ? formatDoubleArray(accessArr) : "[]";
					egressWalks = egressArr != null ? formatDoubleArray(egressArr) : "[]";
				}

				writer.write(String.format(java.util.Locale.US,
						"%d,%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%.2f,%.2f,%.2f,%.2f,%s,%.2f,%.2f,%.2f,%s,%.2f,%.2f,%.2f,%s,%s,%s,%s,%s,%d,%.2f",
						ride.getIndex(), ride.getDegree(), ride.getKind(), variant,
						reqIndices, personIds, groupIds, requestTimes, isCommutes, isEducations,
						origins, destinations,
						pttimes, pdists, pdirects, delays, detours, budgets, maxCosts, maxCostsPerKm, shapleyValues, successors,
						ride.getStartTime(), ride.getEndTime(),
						ride.getRideTravelTime(), ride.getRideDistance(),
						pickupLinkId, pickupX, pickupY, pickupPenalty,
						dropoffLinkId, dropoffX, dropoffY, dropoffPenalty,
						accessWalks, egressWalks,
						requestTags, hubIds, hubLegRoles,
						ride.getPeakPax(), ride.getReposTimeMeanOutgoing()));
				writer.newLine();
	}

	/**
	 * Format integer array for CSV output: [1, 2, 3] -> [1 | 2 | 3]
	 */
	private static String formatIntArray(int[] array) {
		if (array == null || array.length == 0) {
			return "[]";
		}

		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < array.length; i++) {
			if (i > 0) {
				sb.append(ARRAY_SEPARATOR);
			}
			sb.append(array[i]);
		}
		sb.append("]");
		return sb.toString();
	}

	/**
	 * Format double array for CSV output: [1.5, 2.7, 3.9] -> [1.5 | 2.7 | 3.9]
	 */
	private static String formatDoubleArray(double[] array) {
		if (array == null || array.length == 0) {
			return "[]";
		}

		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < array.length; i++) {
			if (i > 0) {
				sb.append(ARRAY_SEPARATOR);
			}
			sb.append(String.format(java.util.Locale.US, "%.2f", array[i]));
		}
		sb.append("]");
		return sb.toString();
	}

	/**
	 * Format string array for CSV output: [a, b, c] -> [a | b | c]
	 */
	private static String formatStringArray(String[] array) {
		if (array == null || array.length == 0) {
			return "[]";
		}

		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < array.length; i++) {
			if (i > 0) {
				sb.append(ARRAY_SEPARATOR);
			}
			sb.append(array[i]);
		}
		sb.append("]");
		return sb.toString();
	}

	/**
	 * Format boolean array for CSV output: [true, false] -> [true | false]
	 */
	private static String formatBooleanArray(Boolean[] array) {
		if (array == null || array.length == 0) {
			return "[]";
		}

		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < array.length; i++) {
			if (i > 0) {
				sb.append(ARRAY_SEPARATOR);
			}
			sb.append(array[i]);
		}
		sb.append("]");
		return sb.toString();
	}

	/**
	 * Format Link ID array for CSV output: [link1, link2] -> [link1 | link2]
	 */
	private static String formatLinkIdArray(Id<Link>[] array) {
		if (array == null || array.length == 0) {
			return "[]";
		}

		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < array.length; i++) {
			if (i > 0) {
				sb.append(ARRAY_SEPARATOR);
			}
			sb.append(array[i].toString());
		}
		sb.append("]");
		return sb.toString();
	}

	/**
	 * Write stop-based ride statistics summary.
	 *
	 * @param filename output file path
	 * @param rides list of all rides
	 * @throws RuntimeException if writing fails
	 */
	public static void writeStopBasedStatistics(String filename, List<Ride> rides) {
		try (BufferedWriter writer = IOUtils.getBufferedWriter(filename)) {
			writer.write("variant,count,avgDegree,avgAccessWalk,avgEgressWalk,avgTotalWalk");
			writer.newLine();

			// Group by variant
			var variantGroups = rides.stream()
					.collect(java.util.stream.Collectors.groupingBy(Ride::getVariant));

			for (RideVariant variant : RideVariant.values()) {
				List<Ride> variantRides = variantGroups.getOrDefault(variant, List.of());
				int count = variantRides.size();

				if (count == 0) {
					writer.write(String.format(java.util.Locale.US,
							"%s,%d,0.0,0.0,0.0,0.0", variant.name(), count));
					writer.newLine();
					continue;
				}

				double avgDegree = variantRides.stream()
						.mapToInt(Ride::getDegree)
						.average()
						.orElse(0.0);

				// Calculate walk distances for stop-based variants
				double avgAccessWalk = 0.0;
				double avgEgressWalk = 0.0;

				if (variant == RideVariant.STOP_TO_STOP || variant == RideVariant.HYPER_POOLED) {
					int totalPassengers = 0;
					double totalAccess = 0;
					double totalEgress = 0;

					for (Ride ride : variantRides) {
						double[] accessWalks = ride.getAccessWalkDistances();
						double[] egressWalks = ride.getEgressWalkDistances();

						if (accessWalks != null && egressWalks != null) {
							for (int i = 0; i < accessWalks.length; i++) {
								totalAccess += accessWalks[i];
								totalEgress += egressWalks[i];
								totalPassengers++;
							}
						}
					}

					if (totalPassengers > 0) {
						avgAccessWalk = totalAccess / totalPassengers;
						avgEgressWalk = totalEgress / totalPassengers;
					}
				}

				writer.write(String.format(java.util.Locale.US,
						"%s,%d,%.2f,%.2f,%.2f,%.2f",
						variant.name(), count, avgDegree,
						avgAccessWalk, avgEgressWalk, avgAccessWalk + avgEgressWalk));
				writer.newLine();
			}

			// Add HYPER_POOLED summary if we have any (separate from individual Ride variants)
			List<Ride> hyperPooledRides = variantGroups.getOrDefault(RideVariant.HYPER_POOLED, List.of());
			if (!hyperPooledRides.isEmpty()) {
				double avgSourceRides = hyperPooledRides.stream()
						.mapToInt(Ride::getDegree)
						.average()
						.orElse(0.0);
				double avgRideTravelTime = hyperPooledRides.stream()
						.mapToDouble(Ride::getRideTravelTime)
						.average()
						.orElse(0.0);
				double avgRideDistance = hyperPooledRides.stream()
						.mapToDouble(Ride::getRideDistance)
						.average()
						.orElse(0.0);

				writer.newLine();
				writer.write("# HYPER_POOLED additional metrics:");
				writer.newLine();
				writer.write(String.format(java.util.Locale.US,
						"# avgSourceRidesPerBundle=%.2f, avgRideTravelTime=%.2f, avgRideDistance=%.2f",
						avgSourceRides, avgRideTravelTime, avgRideDistance));
				writer.newLine();
			}
		} catch (IOException e) {
			throw new RuntimeException("Could not write stop-based statistics CSV: " + filename, e);
		}
	}

	/**
	 * Write hyper-pooled rides to CSV file.
	 *
	 * @param filename output file path
	 * @param hyperPooledRides list of hyper-pooled rides
	 * @throws RuntimeException if writing fails
	 */
	public static void writeHyperPooledRides(String filename, List<HyperPooledRide> hyperPooledRides) {
		try (BufferedWriter writer = IOUtils.getBufferedWriter(filename)) {
			// Header with all hyper-pooled ride attributes
			// Extension-2 per-pax columns (requestTags, hubIds) are APPENDED at
			// the END of the header — never inserted in the middle — so existing
			// consumers that read by column name or by trailing position keep
			// working. They mirror the iteration order of HyperPooledRide.getRequests()
			// and the `[a | b | c]` format already used for other per-pax fields.
			// Extension-2 Task 7.2: `peak_pax` (max simultaneous in-vehicle
			// occupancy over the stop sequence) is appended AFTER
			// requestTags/hubIds — required by the class-aware path cover
			// (rev-3 §7.4b).
			// Task 3 (DuckDB tagging): `hubLegRoles` is appended AFTER hubIds
			// and BEFORE peak_pax so downstream DuckDB can tag each pax leg role.
			writer.write("rideIndex,degree," +
					"sourceRideIndices," +
					"stopSequence,stopSequenceX,stopSequenceY," +
					"passengerBoardingStopIndices,passengerAlightingStopIndices," +
					"passengerTotalWalkDistances,passengerInVehicleTimes," +
					"totalTravelTime,totalDistance,totalVKT," +
					"passengerDelays,remainingBudgets," +
					"requestTags,hubIds,hubLegRoles," +
					"peak_pax");
			writer.newLine();

			List<HyperPooledRide> sortedRides = hyperPooledRides.stream()
					.sorted(Comparator.comparingInt(HyperPooledRide::getIndex))
					.toList();

			for (HyperPooledRide ride : sortedRides) {
				// Format source ride indices
				int[] sourceIndices = ride.getSourceRides().stream()
						.mapToInt(Ride::getIndex)
						.toArray();
				String sourceRideIndicesStr = formatIntArray(sourceIndices);

				// Format stop sequence (link IDs, X coords, Y coords)
				StopLocation[] stops = ride.getStopSequence();
				String[] stopLinkIds = Arrays.stream(stops)
						.map(s -> s.getLinkId().toString())
						.toArray(String[]::new);
				double[] stopX = Arrays.stream(stops)
						.mapToDouble(s -> s.getCoord().getX())
						.toArray();
				double[] stopY = Arrays.stream(stops)
						.mapToDouble(s -> s.getCoord().getY())
						.toArray();

				String stopSequenceStr = formatStringArray(stopLinkIds);
				String stopSequenceXStr = formatDoubleArray(stopX);
				String stopSequenceYStr = formatDoubleArray(stopY);

				// Format passenger boarding/alighting stop indices
				String boardingIndices = formatIntArray(ride.getPassengerBoardingStopIndices());
				String alightingIndices = formatIntArray(ride.getPassengerAlightingStopIndices());

				// Format passenger walk distances and in-vehicle times
				String totalWalkDistances = formatDoubleArray(ride.getPassengerTotalWalkDistances());
				String inVehicleTimes = formatDoubleArray(ride.getPassengerInVehicleTimes());

				// Format aggregated metrics
				double totalTravelTime = ride.getTotalRideTime();
				double totalDistance = ride.getTotalRideDistance();
				double totalVKT = ride.getTotalVehicleKilometers();

				// Calculate passenger delays from source rides
				double[] passengerDelays = computePassengerDelaysFromSourceRides(ride);
				String delaysStr = formatDoubleArray(passengerDelays);

				// Format remaining budgets
				String remainingBudgetsStr = formatDoubleArray(ride.getRemainingBudgets());

				// Extension-2 per-pax columns: same iteration order as
				// HyperPooledRide.getRequests(), same `[a | b | c]` format used
				// elsewhere. Null values render as empty inside the list.
				DrtRequest[] paxRequests = ride.getRequests();
				String requestTagsStr = formatStringArray(Arrays.stream(paxRequests)
						.map(r -> r.requestTag != null ? r.requestTag : "")
						.toArray(String[]::new));
				String hubIdsStr = formatStringArray(Arrays.stream(paxRequests)
						.map(r -> r.hubId != null ? r.hubId : "")
						.toArray(String[]::new));
				// Task 3 (DuckDB tagging): per-pax hub leg role, mirrors hubIdsStr exactly.
				String hubLegRolesStr = formatStringArray(Arrays.stream(paxRequests)
						.map(r -> r.hubLegRole != null ? r.hubLegRole.name() : DrtRequest.HubLegRole.NONE.name())
						.toArray(String[]::new));

				writer.write(String.format(java.util.Locale.US,
						"%d,%d,%s,%s,%s,%s,%s,%s,%s,%s,%.2f,%.2f,%.4f,%s,%s,%s,%s,%s,%d",
						ride.getIndex(), ride.getDegree(),
						sourceRideIndicesStr,
						stopSequenceStr, stopSequenceXStr, stopSequenceYStr,
						boardingIndices, alightingIndices,
						totalWalkDistances, inVehicleTimes,
						totalTravelTime, totalDistance, totalVKT,
						delaysStr, remainingBudgetsStr,
						requestTagsStr, hubIdsStr, hubLegRolesStr,
						ride.getPeakPax()));
				writer.newLine();
			}
		} catch (IOException e) {
			throw new RuntimeException("Could not write hyper-pooled rides CSV: " + filename, e);
		}
	}

	/**
	 * Compute passenger delays from source rides bundled in a hyper-pooled ride.
	 * Aggregates delays from all passengers across all source rides.
	 */
	private static double[] computePassengerDelaysFromSourceRides(HyperPooledRide hyperRide) {
		List<Ride> sourceRides = hyperRide.getSourceRides();
		if (sourceRides == null || sourceRides.isEmpty()) {
			// Return zeros matching the degree if no source rides available
			return new double[hyperRide.getDegree()];
		}

		// Collect delays from all source rides
		java.util.List<Double> allDelays = new java.util.ArrayList<>();
		for (Ride source : sourceRides) {
			double[] sourceDelays = source.getDelays();
			if (sourceDelays != null) {
				for (double delay : sourceDelays) {
					allDelays.add(delay);
				}
			}
		}

		// Convert to array
		double[] result = new double[allDelays.size()];
		for (int i = 0; i < allDelays.size(); i++) {
			result[i] = allDelays.get(i);
		}
		return result;
	}

	/**
	 * Write mode cache to CSV file for debugging.
	 * Exports all mode alternatives routed for each person-trip combination,
	 * allowing analysis of why certain baseline modes were selected.
	 *
	 * @param filename output file path
	 * @param modeCache map: PersonId -> TripIndex -> ModeName -> ModeAttributes
	 * @throws RuntimeException if writing fails
	 */
	public static void writeModeCache(
			String filename,
			java.util.Map<org.matsim.api.core.v01.Id<org.matsim.api.core.v01.population.Person>,
					java.util.Map<Integer, java.util.Map<String, org.matsim.contrib.demand_extraction.demand.ModeAttributes>>> modeCache) {
		try (BufferedWriter writer = IOUtils.getBufferedWriter(filename)) {
			// Header
			writer.write("personId,tripIndex,mode,travelTime,distance,score");
			writer.newLine();

			// Sort by person ID for consistent output
			var sortedPersonIds = modeCache.keySet().stream()
					.sorted(java.util.Comparator.comparing(id -> id.toString()))
					.toList();

			for (var personId : sortedPersonIds) {
				var tripModes = modeCache.get(personId);
				if (tripModes == null) continue;

				// Sort by trip index
				var sortedTripIndices = tripModes.keySet().stream().sorted().toList();

				for (Integer tripIndex : sortedTripIndices) {
					var modes = tripModes.get(tripIndex);
					if (modes == null) continue;

					// Sort by mode name for consistent output
					var sortedModes = modes.keySet().stream().sorted().toList();

					for (String mode : sortedModes) {
						var attrs = modes.get(mode);
						writer.write(String.format(java.util.Locale.US,
								"%s,%d,%s,%.2f,%.2f,%.4f",
								personId, tripIndex, mode,
								attrs.travelTime(), attrs.distance(), attrs.score()));
						writer.newLine();
					}
				}
			}
		} catch (IOException e) {
			throw new RuntimeException("Could not write mode cache CSV: " + filename, e);
		}
	}
}
