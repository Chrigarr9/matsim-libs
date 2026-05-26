package org.matsim.contrib.demand_extraction.demand;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class DrtRequestExtension2FieldsTest {
    @Test
    void connecting_request_carries_tag_and_hubId() {
        DrtRequest req = TestRequestBuilder.connectingFixture("hub_03");
        assertEquals("connecting", req.requestTag);
        assertEquals("hub_03", req.hubId);
    }

    @Test
    void rural_intra_request_has_null_hubId() {
        DrtRequest req = TestRequestBuilder.ruralIntraFixture();
        assertEquals("rural_intra", req.requestTag);
        assertNull(req.hubId);
    }
}
