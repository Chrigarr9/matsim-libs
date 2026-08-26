package org.matsim.contrib.demand_extraction.config;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ExMasConfigGroupBookingHorizonConfigTest {

    @Test
    void defaultsAreLegacyInert() {
        ExMasConfigGroup c = new ExMasConfigGroup();
        assertEquals(0.0, c.getSpontaneousBookingHorizon());
        assertFalse(c.isSpontaneousSingletonChains());
    }

    @Test
    void stringSettersRoundTrip() {
        ExMasConfigGroup c = new ExMasConfigGroup();
        c.addParam("spontaneousBookingHorizon", "600.0");
        c.addParam("spontaneousSingletonChains", "true");
        assertEquals(600.0, c.getSpontaneousBookingHorizon());
        assertTrue(c.isSpontaneousSingletonChains());
    }
}
