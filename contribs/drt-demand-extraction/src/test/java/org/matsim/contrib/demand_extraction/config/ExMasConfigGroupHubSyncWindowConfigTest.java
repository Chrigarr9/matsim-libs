package org.matsim.contrib.demand_extraction.config;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ExMasConfigGroupHubSyncWindowConfigTest {

    @Test
    void defaultsAreLegacyInert() {
        ExMasConfigGroup c = new ExMasConfigGroup();
        assertFalse(c.isHubSyncWindowed());
        assertEquals(0, c.getHubTopK());
    }

    @Test
    void stringSettersRoundTrip() {
        ExMasConfigGroup c = new ExMasConfigGroup();
        c.addParam("hubSyncWindowed", "true");
        c.addParam("hubTopK", "3");
        assertTrue(c.isHubSyncWindowed());
        assertEquals(3, c.getHubTopK());
    }
}
