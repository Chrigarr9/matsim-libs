package org.matsim.contrib.demand_extraction.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * {@code checkpointJournalCompactionBytes} — the bound on the connection-cache journal that grew to
 * 83.5 GB and killed the 100% extraction on a full disk (2026-08-31).
 */
class ExMasConfigGroupJournalCompactionTest {

	@Test
	void defaultsTo20GiB() {
		assertEquals(20L * 1024 * 1024 * 1024,
				new ExMasConfigGroup().getCheckpointJournalCompactionBytes());
	}

	@Test
	void setterRoundTrips() {
		ExMasConfigGroup cfg = new ExMasConfigGroup();
		cfg.setCheckpointJournalCompactionBytes(5L * 1024 * 1024 * 1024);
		assertEquals(5L * 1024 * 1024 * 1024, cfg.getCheckpointJournalCompactionBytes());
	}

	/** 0 is the documented "never compact" value (pre-2026-09-01 append-only behaviour). */
	@Test
	void zeroIsAllowedAndMeansNeverCompact() {
		ExMasConfigGroup cfg = new ExMasConfigGroup();
		cfg.setCheckpointJournalCompactionBytes(0L);
		assertEquals(0L, cfg.getCheckpointJournalCompactionBytes());
	}

	@Test
	void negativeIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new ExMasConfigGroup().setCheckpointJournalCompactionBytes(-1L));
	}

	/**
	 * The knob must survive the XML round trip: it is written into {@code phase1_config.xml} and
	 * re-read by Phase 2, whose config group rejects unknown params outright.
	 */
	@Test
	void roundTripsThroughTheStringParamRegistry() {
		ExMasConfigGroup cfg = new ExMasConfigGroup();
		cfg.setCheckpointJournalCompactionBytes(123456789L);
		assertEquals("123456789", cfg.getParams().get("checkpointJournalCompactionBytes"));

		ExMasConfigGroup reread = new ExMasConfigGroup();
		reread.addParam("checkpointJournalCompactionBytes", "123456789");
		assertEquals(123456789L, reread.getCheckpointJournalCompactionBytes());

		assertNotNull(cfg.getComments().get("checkpointJournalCompactionBytes"),
				"every param needs a comment for the generated config reference");
	}
}
