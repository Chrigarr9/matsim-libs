package org.matsim.contrib.demand_extraction.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.MaterializedRideStore;
import org.matsim.contrib.demand_extraction.algorithm.domain.HyperPooledRide;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.demand_extraction.demand.ModeAttributes;
import org.matsim.contrib.demand_extraction.io.lowmem.PhaseOneDumpLayout;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Single owner of the extraction output-file wiring: the {@code <demandDir>/<runId>.<name>.csv}
 * filename convention, the static {@link ExMasCsvWriter}/{@link ConnectionCacheWriter}/
 * {@link PersonAttributesWriter} delegations, and the config-driven knobs/guards (the
 * {@code isCalcPredecessors()} gate and the {@code networkTimeBinSize}/{@code connectionCacheExportMode}
 * arguments for the connection cache).
 *
 * <p>This is a <b>relocation, not a unification</b>. The single-process flow
 * ({@code DemandExtractionListener}) and the two-phase flow ({@code RunDemandExtractionPhase2})
 * have genuinely divergent export sequences — the former re-serialises requests in memory and
 * emits hyper-pooled rides; the latter republishes the canonical Phase-1 dump and writes no
 * hyper-pool CSV. Each caller still composes these per-file methods in its own order; the manager
 * only removes the duplicated path-building and writer wiring. Merging the two sequences would
 * change {@code drt_requests.csv} bytes (copy vs re-serialise) and add/drop the hyper-pool file,
 * so it is deliberately avoided.
 *
 * <p>Logging stays at the call sites (their messages differ — {@code "Wrote N rides"} vs
 * {@code "PHASE 2 STEP 5: wrote N rides"}); each writer method returns the {@link Path} it wrote
 * so the caller can log it. The connection-cache writer is the one exception that owns its own
 * try/catch + {@code log.error} because both call sites handled its {@link IOException}
 * identically.
 */
public final class ExtractionDataManager {
	private static final Logger log = LogManager.getLogger(ExtractionDataManager.class);

	private final Path demandDir;
	private final String runId;
	private final ExMasConfigGroup config;

	public ExtractionDataManager(Path demandDir, String runId, ExMasConfigGroup config) {
		this.demandDir = demandDir;
		this.runId = runId;
		this.config = config;
	}

	/**
	 * Build a manager for {@code <outputDir>/drt_demand}, creating that directory. Mirrors the
	 * {@code ensureDemandOutputDir()} / {@code outputDir.resolve("drt_demand")} idioms the two
	 * callers previously inlined.
	 */
	public static ExtractionDataManager forOutputDir(Path outputDir, String runId,
			ExMasConfigGroup config) throws IOException {
		Path demandDir = outputDir.resolve("drt_demand");
		Files.createDirectories(demandDir);
		return new ExtractionDataManager(demandDir, runId, config);
	}

	/** The {@code <outputDir>/drt_demand} directory this manager writes into. */
	public Path demandDir() {
		return demandDir;
	}

	/** Resolve {@code <demandDir>/<runId>.<suffix>} (the single filename convention). */
	public Path path(String suffix) {
		return demandDir.resolve(runId + "." + suffix);
	}

	/** Re-serialise the in-memory requests to {@code <runId>.drt_requests.csv} (single-process flow). */
	public Path writeRequests(List<DrtRequest> requests) {
		Path out = path("drt_requests.csv");
		ExMasCsvWriter.writeRequests(out.toString(), requests);
		return out;
	}

	/**
	 * Publish the canonical Phase-1 requests dump as {@code <runId>.drt_requests.csv} (two-phase
	 * flow): hard-link when possible, else copy. Phase 2 never re-serialises requests — the dump
	 * is the source of truth.
	 */
	public Path publishCanonicalRequests(Path phase1Dir) throws IOException {
		Path src = phase1Dir.resolve(PhaseOneDumpLayout.REQUESTS_CSV);
		Path dst = path("drt_requests.csv");
		if (!Files.exists(src)) {
			throw new IOException("Phase-1 requests CSV missing at " + src
					+ "; Phase 1 did not complete normally");
		}
		Files.deleteIfExists(dst);
		try {
			Files.createLink(dst, src);
		} catch (UnsupportedOperationException | java.nio.file.FileSystemException ex) {
			Files.copy(src, dst);
		}
		return dst;
	}

	/** Write the Stage-1 rides to {@code <runId>.exmas_rides.csv}. */
	public Path writeRides(List<Ride> rides) {
		Path out = path("exmas_rides.csv");
		ExMasCsvWriter.writeRides(out.toString(), new MaterializedRideStore(rides));
		return out;
	}

	/**
	 * Stream the Stage-1 rides to {@code <runId>.exmas_rides.csv} one ride at a time (no full fat
	 * list), applying {@code enrich} per ride. Mirrors {@link #writeRides(List)} for the streaming
	 * path; the {@code store} must emit in {@link Ride#getIndex()} order (the streaming RideStores do).
	 */
	public Path writeRidesStreaming(
			org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideStore store,
			UnaryOperator<Ride> enrich) {
		Path out = path("exmas_rides.csv");
		ExMasCsvWriter.writeRidesStreaming(out.toString(), store, enrich);
		return out;
	}

	/**
	 * Parallel variant of {@link #writeRidesStreaming(org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideStore, UnaryOperator)}:
	 * fans the materialize+write over {@code parallelism} worker threads (byte-identical output). The
	 * dominant per-row cost on the resume path is re-routing pair-chain segments absent from the warm
	 * journal, so the fan-out turns a single-threaded re-route into an N-core one. {@code enrich} must
	 * be thread-safe.
	 */
	public Path writeRidesStreaming(
			org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideStore store,
			UnaryOperator<Ride> enrich, int parallelism) {
		Path out = path("exmas_rides.csv");
		ExMasCsvWriter.writeRidesStreamingParallel(out.toString(), store, enrich, parallelism);
		return out;
	}

	/**
	 * Write the HyperPool Stage-2 rides to {@code <runId>.hyperpool_rides.csv}, or do nothing and
	 * return {@code null} when there are none (the distinct multi-stop schema gets its own CSV).
	 */
	public Path writeHyperPooledRides(List<HyperPooledRide> hyperPooledRides) {
		if (hyperPooledRides.isEmpty()) {
			return null;
		}
		Path out = path("hyperpool_rides.csv");
		ExMasCsvWriter.writeHyperPooledRides(out.toString(), hyperPooledRides);
		return out;
	}

	/**
	 * Write the connection cache to {@code <runId>.connection_cache.csv} when
	 * {@code isCalcPredecessors()} is on, applying the configured time-bin size and export mode.
	 * Returns {@code null} when predecessors are off (no file written). An {@link IOException} is
	 * caught and logged exactly as both call sites previously did, so a cache-write failure never
	 * aborts the run.
	 */
	public Path writeConnectionCache(List<Ride> rides, MatsimNetworkCache networkCache,
			LongOpenHashSet windowKeys) {
		if (!config.isCalcPredecessors()) {
			return null;
		}
		Path out = path("connection_cache.csv");
		try {
			ConnectionCacheWriter.writeConnectionCache(out.toString(), networkCache,
					config.getConnectionCacheExportMode(), windowKeys);
		} catch (IOException e) {
			log.error("Failed to write connection cache", e);
			return null;
		}
		return out;
	}

	/** Write per-person attributes to {@code <runId>.person_attributes.csv} (Phase-1 owned). */
	public Path writePersonAttributes(Population population, List<DrtRequest> requests) {
		Path out = path("person_attributes.csv");
		PersonAttributesWriter.writePersonAttributes(out.toString(), population, requests);
		return out;
	}

	/** Write the routed mode cache to {@code <runId>.mode_cache.csv} (Phase-1 owned). */
	public Path writeModeCache(
			Map<Id<Person>, Map<Integer, Map<String, ModeAttributes>>> modeCache) {
		Path out = path("mode_cache.csv");
		ExMasCsvWriter.writeModeCache(out.toString(), modeCache);
		return out;
	}
}
