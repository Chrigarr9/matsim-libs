package org.matsim.contrib.demand_extraction.io.lowmem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Round-trip: write a small List&lt;DrtRequest&gt; via PhaseOneDumpWriter, read it back via
 * PhaseOneDumpReader, assert all fields used by Phase-2 scoring are restored.
 *
 * <p>Phase-2 invariant: {@code ctx.person() == null}. The scoring path
 * (EqasimScoringAdapter.scoreDrtDirectly + OpportunityCostCalculator) never consults Person.
 */
class PhaseOneDumpRoundTripTest {

	private static final double WALK_SPEED = 1.4;
	private static final double MIN_WALK = 100.0;

	@Test
	void roundTripsAllRequestFieldsAndScoringContext(@TempDir Path tmp) throws IOException {
		PhaseOneDumpLayout layout = new PhaseOneDumpLayout(tmp);

		List<DrtRequest> originals = List.of(
				LowMemTestFixtures.buildRequest(0, "home", "work", 43200.0, 28800.0),
				LowMemTestFixtures.buildRequest(1, "work", "home", 28800.0, 43200.0));

		PhaseOneDumpWriter.Meta meta = new PhaseOneDumpWriter.Meta(
				"drt", WALK_SPEED, "LOG", MIN_WALK,
				"test-run-id", 1, 12345L, 67890L);

		PhaseOneDumpWriter.write(layout, originals, meta);

		PhaseOneDumpReader.DumpData loaded = PhaseOneDumpReader.read(layout);

		// Meta round-trip
		PhaseOneDumpReader.Meta loadedMeta = loaded.meta();
		assertEquals("drt", loadedMeta.drtMode());
		assertEquals(WALK_SPEED, loadedMeta.walkSpeed(), 1e-12);
		assertEquals("LOG", loadedMeta.opportunityCostModel());
		assertEquals(MIN_WALK, loadedMeta.minDrtAccessEgressDistance(), 1e-12);
		assertEquals("test-run-id", loadedMeta.runId());
		assertEquals(1, loadedMeta.sampleSize());
		assertEquals(2, loadedMeta.numRequests());
		assertEquals(12345L, loadedMeta.phase1WallTimeMs());
		assertEquals(67890L, loadedMeta.phase1PeakHeapBytes());

		// Requests round-trip
		List<DrtRequest> loadedRequests = loaded.requests();
		assertEquals(2, loadedRequests.size());

		Map<Integer, DrtRequest> originalsByIdx = originals.stream()
				.collect(Collectors.toMap(r -> r.index, r -> r));

		for (DrtRequest reloaded : loadedRequests) {
			DrtRequest original = originalsByIdx.get(reloaded.index);
			assertNotNull(original, "loaded request idx=" + reloaded.index + " has no original");
			assertScalarFieldsMatch(original, reloaded);
			assertScoringContextReconstructed(original, reloaded);
		}
	}

	private static void assertScalarFieldsMatch(DrtRequest original, DrtRequest reloaded) {
		assertEquals(original.index, reloaded.index);
		assertEquals(original.personId, reloaded.personId);
		assertEquals(original.groupId, reloaded.groupId);
		assertEquals(original.tripIndex, reloaded.tripIndex);
		assertEquals(original.isCommute, reloaded.isCommute);
		assertEquals(original.isEducation, reloaded.isEducation);
		// CSV uses %.4f for budget and %.2f for time/coord fields.
		assertEquals(original.budget, reloaded.budget, 1e-3);
		assertEquals(original.bestModeScore, reloaded.bestModeScore, 1e-3);
		assertEquals(original.bestMode, reloaded.bestMode);
		assertEquals(original.originLinkId, reloaded.originLinkId);
		assertEquals(original.destinationLinkId, reloaded.destinationLinkId);
		assertEquals(original.originX, reloaded.originX, 1e-2);
		assertEquals(original.originY, reloaded.originY, 1e-2);
		assertEquals(original.destinationX, reloaded.destinationX, 1e-2);
		assertEquals(original.destinationY, reloaded.destinationY, 1e-2);
		assertEquals(original.originLinkCoordFromX, reloaded.originLinkCoordFromX, 1e-2);
		assertEquals(original.originLinkCoordFromY, reloaded.originLinkCoordFromY, 1e-2);
		assertEquals(original.originLinkCoordToX, reloaded.originLinkCoordToX, 1e-2);
		assertEquals(original.originLinkCoordToY, reloaded.originLinkCoordToY, 1e-2);
		assertEquals(original.destinationLinkCoordFromX, reloaded.destinationLinkCoordFromX, 1e-2);
		assertEquals(original.destinationLinkCoordFromY, reloaded.destinationLinkCoordFromY, 1e-2);
		assertEquals(original.destinationLinkCoordToX, reloaded.destinationLinkCoordToX, 1e-2);
		assertEquals(original.destinationLinkCoordToY, reloaded.destinationLinkCoordToY, 1e-2);
		assertEquals(original.requestTime, reloaded.requestTime, 1e-2);
		assertEquals(original.earliestDeparture, reloaded.earliestDeparture, 1e-2);
		assertEquals(original.latestArrival, reloaded.latestArrival, 1e-2);
		assertEquals(original.directTravelTime, reloaded.directTravelTime, 1e-2);
		assertEquals(original.directDistance, reloaded.directDistance, 1e-2);
		// maxDetourFactor derived from maxTravelTime/directTravelTime in writer
		assertEquals(original.maxDetourFactor, reloaded.maxDetourFactor, 1e-3);
		assertEquals(original.originActivityType, reloaded.originActivityType);
		assertEquals(original.destinationActivityType, reloaded.destinationActivityType);
		assertEquals(original.carTravelTime, reloaded.carTravelTime, 1e-2);
		assertEquals(original.ptTravelTime, reloaded.ptTravelTime, 1e-2);
		assertEquals(original.ptAccessibility, reloaded.ptAccessibility, 1e-3);
		assertEquals(original.getMaxTravelTime(), reloaded.getMaxTravelTime(), 1e-2);
	}

	private static void assertScoringContextReconstructed(DrtRequest original, DrtRequest reloaded) {
		DrtRequest.ScoringContext origCtx = original.getScoringContext();
		DrtRequest.ScoringContext newCtx = reloaded.getScoringContext();

		assertNotNull(newCtx, "reloaded ctx must be non-null");
		assertNull(newCtx.person(), "Phase-2 invariant: ctx.person() must be null");

		// Real origin/dest activities — type and Phase-2-used fields
		assertNotNull(newCtx.originActivity());
		assertNotNull(newCtx.destActivity());
		assertEquals(origCtx.originActivity().getType(), newCtx.originActivity().getType());
		assertEquals(origCtx.destActivity().getType(), newCtx.destActivity().getType());

		// Durations used by LOG opportunity cost
		assertEquals(origCtx.originDuration(), newCtx.originDuration(), 1e-12);
		assertEquals(origCtx.destDuration(), newCtx.destDuration(), 1e-12);

		// Scoring params — only the fields Phase 2 actually reads
		assertNotNull(newCtx.scoringParams());
		assertEquals(origCtx.scoringParams().marginalUtilityOfPerforming_s,
				newCtx.scoringParams().marginalUtilityOfPerforming_s, 1e-12);
		assertEquals(origCtx.scoringParams().marginalUtilityOfWaitingPt_s,
				newCtx.scoringParams().marginalUtilityOfWaitingPt_s, 1e-12);

		// Activity-type table round-trip via utilParams
		String origType = origCtx.originActivity().getType();
		var origActParams = origCtx.scoringParams().utilParams.get(origType);
		var newActParams = newCtx.scoringParams().utilParams.get(origType);
		assertNotNull(newActParams, "reloaded utilParams missing type " + origType);
		assertEquals(origActParams.getTypicalDuration(), newActParams.getTypicalDuration(), 1e-12);
		assertEquals(origActParams.isScoreAtAll(), newActParams.isScoreAtAll());

		// Walk legs/routes — distance restored from meta, time recomputed as dist/walkSpeed
		assertNotNull(newCtx.accessWalkLeg());
		assertNotNull(newCtx.accessWalkRoute());
		assertEquals(MIN_WALK, newCtx.accessWalkRoute().getDistance(), 1e-12);
		assertEquals(MIN_WALK / WALK_SPEED, newCtx.accessWalkRoute().getTravelTime().seconds(), 1e-9);
		assertNotNull(newCtx.egressWalkLeg());
		assertNotNull(newCtx.egressWalkRoute());
		assertEquals(MIN_WALK, newCtx.egressWalkRoute().getDistance(), 1e-12);

		// DRT route template
		assertNotNull(newCtx.drtRouteTemplate());
		assertEquals(original.directTravelTime, newCtx.drtRouteTemplate().getDirectRideTime(), 1e-2);
		assertEquals(original.directDistance, newCtx.drtRouteTemplate().getDistance(), 1e-2);

		// Synthetic drt_interaction activities — always rebuilt from request coords
		assertNotNull(newCtx.syntheticOriginActivity());
		assertEquals("drt_interaction", newCtx.syntheticOriginActivity().getType());
		assertNotNull(newCtx.syntheticOriginActivity().getCoord());
		assertEquals(reloaded.originX, newCtx.syntheticOriginActivity().getCoord().getX(), 1e-12);
		assertEquals(reloaded.originY, newCtx.syntheticOriginActivity().getCoord().getY(), 1e-12);
		assertTrue(newCtx.syntheticOriginActivity().getEndTime().isDefined(),
				"synthetic origin activity must carry request time as end time");
		assertEquals(reloaded.requestTime,
				newCtx.syntheticOriginActivity().getEndTime().seconds(), 1e-2);

		assertNotNull(newCtx.syntheticDestActivity());
		assertEquals("drt_interaction", newCtx.syntheticDestActivity().getType());
		assertEquals(reloaded.destinationX, newCtx.syntheticDestActivity().getCoord().getX(), 1e-12);
		assertEquals(reloaded.destinationY, newCtx.syntheticDestActivity().getCoord().getY(), 1e-12);
	}
}
