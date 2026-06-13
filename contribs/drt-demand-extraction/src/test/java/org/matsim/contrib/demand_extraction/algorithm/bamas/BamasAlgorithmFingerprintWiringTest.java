package org.matsim.contrib.demand_extraction.algorithm.bamas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.demand_extraction.algorithm.bamas.checkpoint.CheckpointManager;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCacheTestFixture;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.demand_extraction.scoring.DemandExtractionScoringAdapter;
import org.matsim.contrib.demand_extraction.scoring.TripScoreRequest;
import org.matsim.contrib.demand_extraction.scoring.TripScoreResult;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;

/**
 * C1 — regression for the checkpoint RunFingerprint wiring through the {@link BamasAlgorithm} adapter.
 *
 * <p>The bug: {@code BamasEngine.setFingerprintInputs(...)} was never called in production, so the
 * checkpoint fingerprint was config-only. A resume against a DIFFERENT population / travel-times /
 * network would falsely MATCH (config unchanged) and silently corrupt output by mixing incompatible
 * routing inputs. The fix forwards the real input paths: runner → {@code BamasAlgorithm} (new setter)
 * → {@code engine.setFingerprintInputs(...)} in {@link BamasAlgorithm#run}.
 *
 * <p>This test drives a real {@link BamasAlgorithm#run} on the streaming pair-stub D2D checkpoint
 * path (stub mode ON, stop-based OFF, maxDegree&gt;2, {@code --checkpoint-dir} set) so the engine
 * WRITES a checkpoint manifest carrying the fingerprint. It runs once with a travel-times fingerprint
 * file of content A (→ F_A) and once into a FRESH dir with content B (→ F_B), and asserts F_A != F_B.
 *
 * <p><b>Kill criterion:</b> the only difference between the two runs is the bytes of the travel-times
 * fingerprint file. If the forwarding line in {@link BamasAlgorithm#run} is removed, the engine
 * reverts to a config-only fingerprint, both runs produce the SAME hash, and {@code assertNotEquals}
 * fails. The fixture (network, requests, config, pass-through validator) is copied from
 * {@code BamasCheckpointResumeDeterminismTest}.
 *
 * <p>The travel-times file is fed only to {@code RunFingerprint.fileDigest()} (raw SHA-256 of bytes);
 * it is decoupled from the in-memory {@link MatsimNetworkCache} routing, so arbitrary differing bytes
 * suffice and the rides themselves are identical across both runs.
 */
@Tag("fast")
class BamasAlgorithmFingerprintWiringTest {

	private static final double LINK_LEN = 1000.0;
	private static final double FREESPEED = 10.0;

	private Network network;
	private BudgetValidator validator;

	@BeforeEach
	void setUp() {
		network = NetworkUtils.createNetwork();
		NetworkFactory f = network.getFactory();
		Node[] n = new Node[7];
		for (int i = 0; i < 7; i++) {
			n[i] = f.createNode(Id.createNodeId("n" + i), new Coord(i * LINK_LEN, 0.0));
			network.addNode(n[i]);
		}
		for (int i = 0; i < 6; i++) {
			addLink(f, "link" + i + (i + 1), n[i], n[i + 1]);
		}
		validator = new PassThroughBudgetValidator();
	}

	private void addLink(NetworkFactory f, String id, Node from, Node to) {
		Link lnk = f.createLink(Id.createLinkId(id), from, to);
		lnk.setLength(LINK_LEN);
		lnk.setFreespeed(FREESPEED);
		lnk.setCapacity(1000.0);
		lnk.setNumberOfLanes(1.0);
		network.addLink(lnk);
	}

	private MatsimNetworkCache freshCache() {
		FreeSpeedTravelTime tt = new FreeSpeedTravelTime();
		OnlyTimeDependentTravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);
		return MatsimNetworkCacheTestFixture.createWithRouting(network, tt, td, 900);
	}

	/** A checkpoint-writing config on the streaming pair-stub D2D path (stub ON, stop-based OFF, deg>2). */
	private ExMasConfigGroup config(Path checkpointDir) {
		ExMasConfigGroup c = new ExMasConfigGroup();
		c.setAlgorithm(ExMasConfigGroup.Algorithm.BAMAS);
		c.setEnableStopBased(false);         // streaming pair-stub D2D path
		c.setHeuristicPruningEnabled(false);
		c.setPruningDistanceSavingsLogScale(-1.0);
		c.setPruningMode(ExMasConfigGroup.PruningMode.RATIO_THRESHOLD);
		c.setInterDegreeKeepFraction(1.0);
		c.clearPruningCoverageKByDegree();
		c.setCalcPredecessors(false);
		c.setCalcShapleyValues(false);
		c.setMaxPoolingDegree(4);
		c.setAlgorithmProcessCount(1);
		c.setCheckpointDir(checkpointDir.toString());   // enable checkpoint writing
		return c;
	}

	/** Five mutually-shareable requests along the corridor — pool to degree 4. */
	private List<DrtRequest> requests() {
		List<DrtRequest> reqs = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			reqs.add(DrtRequest.builder()
					.index(i)
					.personId(Id.createPersonId("pax_" + i))
					.groupId("g" + i)
					.tripIndex(0)
					.budget(50.0)
					.bestModeScore(-50.0)
					.bestMode("car")
					.originLinkId(Id.createLinkId("link01"))
					.destinationLinkId(Id.createLinkId("link56"))
					.originX(LINK_LEN).originY(0.0)
					.destinationX(6.0 * LINK_LEN).destinationY(0.0)
					.requestTime(i * 30.0)
					.earliestDeparture(-3600.0)
					.latestArrival(36000.0)
					.directTravelTime(5 * LINK_LEN / FREESPEED)
					.directDistance(5 * LINK_LEN)
					.maxDetourFactor(10.0)
					.maxWaitTime(36000.0)
					.build());
		}
		return reqs;
	}

	/**
	 * The adapter under test. budgetToConstraints is null (unused on the pass-through / stop-based-off
	 * path), exactly as in the determinism test's 5-arg engine construction.
	 */
	private BamasAlgorithm algorithm(MatsimNetworkCache cache, ExMasConfigGroup cfg) {
		return new BamasAlgorithm(cache, validator, cfg, null);
	}

	/** Read the fingerprint persisted in the manifest. The ctor fingerprint arg is ignored by readManifest(). */
	private static String manifestFingerprint(Path checkpointDir) {
		CheckpointManager.Manifest m = new CheckpointManager(checkpointDir, "ignored", "ignored").readManifest();
		return m.fingerprint;
	}

	/**
	 * Different travel-times file CONTENT ⇒ different checkpoint fingerprint — PROVING the adapter
	 * forwards the fingerprint inputs into the engine. Holds everything else byte-identical (same
	 * config values, same requests, requests/network paths null in both) so the ONLY driver of a
	 * fingerprint difference is the travel-times bytes flowing through the wiring.
	 */
	@Test
	void differentTravelTimesContentYieldsDifferentFingerprint(
			@TempDir Path ckptA, @TempDir Path ckptB, @TempDir Path ttDir) throws IOException {
		Path ttA = ttDir.resolve("travel_times_A.tsv");
		Path ttB = ttDir.resolve("travel_times_B.tsv");
		Files.writeString(ttA, "from\tto\tbin\tseconds\n1\t2\t0\t100.0\n", StandardCharsets.UTF_8);
		Files.writeString(ttB, "from\tto\tbin\tseconds\n1\t2\t0\t999.0\n", StandardCharsets.UTF_8);

		// Run A — fingerprint inputs point at travel-times content A.
		BamasAlgorithm algA = algorithm(freshCache(), config(ckptA));
		algA.setFingerprintInputs(null, ttA, null);
		algA.run(requests());
		String fpA = manifestFingerprint(ckptA);

		// Run B — fresh checkpoint dir, travel-times content B, everything else identical.
		BamasAlgorithm algB = algorithm(freshCache(), config(ckptB));
		algB.setFingerprintInputs(null, ttB, null);
		algB.run(requests());
		String fpB = manifestFingerprint(ckptB);

		assertNotNull(fpA);
		assertNotNull(fpB);
		// Sanity: degree-4 checkpoint was actually written (manifest is a full one).
		assertTrue(Files.exists(ckptA.resolve("degree_4.stubs.bin")),
				"fixture must reach degree 4 so a full checkpoint manifest is written");
		// THE KILL ASSERTION: equal here ⇒ the adapter is NOT forwarding the travel-times path
		// (config-only fingerprint), i.e. the C1 wiring is broken.
		assertNotEquals(fpA, fpB,
				"travel-times file content must flow into the fingerprint via BamasAlgorithm.run() "
				+ "forwarding; equal fingerprints mean the setFingerprintInputs forwarding was removed");
	}

	/**
	 * Same travel-times content ⇒ same fingerprint ⇒ the second run is admitted as a RESUME (no
	 * fingerprint-mismatch {@link IllegalStateException}). Complements the kill assertion: it shows
	 * the forwarding is content-keyed (matching inputs match), not merely always-different.
	 */
	@Test
	void sameTravelTimesContentAdmitsResume(@TempDir Path ckpt, @TempDir Path ttDir) throws IOException {
		Path tt = ttDir.resolve("travel_times.tsv");
		Files.writeString(tt, "from\tto\tbin\tseconds\n1\t2\t0\t100.0\n", StandardCharsets.UTF_8);

		BamasAlgorithm write = algorithm(freshCache(), config(ckpt));
		write.setFingerprintInputs(null, tt, null);
		write.run(requests());
		String fpWrite = manifestFingerprint(ckpt);

		// Resume into the SAME dir with the SAME travel-times content — fingerprints match ⇒ admitted.
		BamasAlgorithm resume = algorithm(freshCache(), config(ckpt));
		resume.setFingerprintInputs(null, tt, null);
		resume.run(requests());   // must NOT throw fingerprint-mismatch
		assertEquals(fpWrite, manifestFingerprint(ckpt),
				"resume with identical travel-times content must keep the same fingerprint");
	}

	// -------------------------------------------------------------------------
	// Pass-through BudgetValidator — accepts every ride (geometry-only feasibility).
	// Copied from BamasCheckpointResumeDeterminismTest.
	// -------------------------------------------------------------------------

	private static final class PassThroughBudgetValidator extends BudgetValidator {
		private PassThroughBudgetValidator() {
			super(new NoOpScoringAdapter(), person -> null, new ExMasConfigGroup(), dummyConfig());
		}

		@Override
		public Ride validateAndPopulateBudgets(Ride ride) {
			return ride.toBuilder().remainingBudgets(new double[ride.getDegree()]).build();
		}

		private static Config dummyConfig() {
			Config config = ConfigUtils.createConfig();
			config.addModule(new ExMasConfigGroup());
			return config;
		}

		@SuppressWarnings("unused")
		private static ScoringParametersForPerson noOp() {
			return person -> null;
		}
	}

	private static final class NoOpScoringAdapter implements DemandExtractionScoringAdapter {
		@Override
		public String getName() {
			return "noop";
		}

		@Override
		public TripScoreResult scoreTrip(TripScoreRequest request) {
			throw new UnsupportedOperationException("Not used in pass-through validator");
		}

		@Override
		public double getMarginalUtilityOfMoney(org.matsim.api.core.v01.population.Person person, double euclidDist_km) {
			return 1.0;
		}

		@Override
		public boolean supportsDistanceSpecificMoneyUtility() {
			return false;
		}

		@Override
		public boolean includesOpportunityCost() {
			return false;
		}
	}
}
