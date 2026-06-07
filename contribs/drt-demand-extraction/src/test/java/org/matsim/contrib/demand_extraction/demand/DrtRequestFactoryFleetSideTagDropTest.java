package org.matsim.contrib.demand_extraction.demand;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.FleetSide;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paper-2 Extension 2: the fleetSide-aware off-fleet tag drop that scopes each
 * of the two fleet runs to its own request set. Covers the truth table of
 * {@link DrtRequestFactory#isOffFleetTag}: RURAL drops urban_intra, URBAN drops
 * rural_intra, connecting is never dropped here, and the null fleetSide
 * (Kelheim / Paper-1 path) drops nothing.
 */
class DrtRequestFactoryFleetSideTagDropTest {

    @Test
    void ruralFleetDropsUrbanIntraOnly() {
        assertTrue(DrtRequestFactory.isOffFleetTag("urban_intra", FleetSide.RURAL),
                "RURAL run must drop urban-internal trips");
        assertFalse(DrtRequestFactory.isOffFleetTag("rural_intra", FleetSide.RURAL),
                "RURAL run keeps its own rural-internal trips");
        assertFalse(DrtRequestFactory.isOffFleetTag("connecting", FleetSide.RURAL),
                "connecting is kept (expanded), never dropped by fleetSide");
    }

    @Test
    void urbanFleetDropsRuralIntraOnly() {
        assertTrue(DrtRequestFactory.isOffFleetTag("rural_intra", FleetSide.URBAN),
                "URBAN run must drop rural-internal trips");
        assertFalse(DrtRequestFactory.isOffFleetTag("urban_intra", FleetSide.URBAN),
                "URBAN run keeps its own urban-internal trips");
        assertFalse(DrtRequestFactory.isOffFleetTag("connecting", FleetSide.URBAN),
                "connecting is kept (expanded), never dropped by fleetSide");
    }

    @Test
    void nullFleetSideDropsNothing() {
        // Kelheim / Paper-1 path: no fleetSide -> no off-fleet dropping.
        assertFalse(DrtRequestFactory.isOffFleetTag("urban_intra", null));
        assertFalse(DrtRequestFactory.isOffFleetTag("rural_intra", null));
        assertFalse(DrtRequestFactory.isOffFleetTag("connecting", null));
    }

    @Test
    void nullTagDropsNothing() {
        assertFalse(DrtRequestFactory.isOffFleetTag(null, FleetSide.RURAL));
        assertFalse(DrtRequestFactory.isOffFleetTag(null, FleetSide.URBAN));
    }

    @Test
    void externalIsNotHandledHere() {
        // external has its own pre-drop; isOffFleetTag is only about the
        // off-fleet INTRA tags, so it returns false for external.
        assertFalse(DrtRequestFactory.isOffFleetTag("external", FleetSide.RURAL));
        assertFalse(DrtRequestFactory.isOffFleetTag("external", FleetSide.URBAN));
    }
}
