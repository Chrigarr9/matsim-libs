package org.matsim.contrib.demand_extraction.algorithm.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.MaterializedRideStore;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.network.TravelSegmentLookup;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.testutil.RideFixtures;

/**
 * Stage-1 streaming post-processing: the per-ride enricher must reproduce the batch
 * {@link RidePostProcessor#process}'s maxCosts, and must be available only when no cross-ride pass
 * (Shapley / predecessors) is enabled.
 */
class RidePostProcessorStreamingTest {

    /** A lookup stub; never invoked on the maxCosts-only path (no predecessor routing). */
    private static final TravelSegmentLookup NO_ROUTING =
            (o, d, t) -> TravelSegment.unreachable();

    private RidePostProcessor proc(boolean shapley, boolean preds) {
        ExMasConfigGroup cfg = new ExMasConfigGroup();
        cfg.setCalcShapleyValues(shapley);
        cfg.setCalcPredecessors(preds);
        RidePostProcessor.MaxCostResolver r = (budget, req, tt, dist) -> budget + 1.0;
        return new RidePostProcessor(cfg, NO_ROUTING, r);
    }

    @Test
    void streamingSupportedOnlyWhenCrossRidePassesOff() {
        assertTrue(proc(false, false).isStreamingPostProcessSupported());
        assertFalse(proc(true, false).isStreamingPostProcessSupported());
        assertFalse(proc(false, true).isStreamingPostProcessSupported());
    }

    @Test
    void perRideEnricherMatchesBatchMaxCosts() {
        RidePostProcessor p = proc(false, false);
        List<Ride> rides = RideFixtures.singleAndPair();
        List<Ride> batch = p.process(new MaterializedRideStore(rides));
        UnaryOperator<Ride> enrich = p.streamingPerRideEnricher();
        for (int i = 0; i < rides.size(); i++) {
            Ride e = enrich.apply(rides.get(i));
            assertArrayEquals(batch.get(i).getMaxCosts(), e.getMaxCosts(), 1e-9,
                    "maxCosts mismatch at ride " + i);
            assertArrayEquals(batch.get(i).getMaxCostsPerKm(), e.getMaxCostsPerKm(), 1e-9,
                    "maxCostsPerKm mismatch at ride " + i);
        }
    }

    @Test
    void enricherRejectedWhenCrossRideOn() {
        assertThrows(IllegalStateException.class, () -> proc(true, false).streamingPerRideEnricher());
    }
}
