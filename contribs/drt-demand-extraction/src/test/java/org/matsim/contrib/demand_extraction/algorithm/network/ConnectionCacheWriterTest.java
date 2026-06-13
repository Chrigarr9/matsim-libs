package org.matsim.contrib.demand_extraction.algorithm.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.util.PackedKeyCodec;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Verifies the {@code "window"} connection-cache export domain (Task 9): only the OD/bin
 * segments the predecessor/successor pass actually evaluated (accepted AND rejected handoffs)
 * are written, while incidental pair-generation segments outside the window set are skipped.
 *
 * <p>Lives in package {@code network} (not the plan's {@code io} path) so it can use the
 * package-visible {@link MatsimNetworkCache#forTesting()} / {@code putForTesting} test helpers
 * without widening them to public; {@code exportWindow} is the only public surface it needs.
 */
class ConnectionCacheWriterTest {

	@TempDir
	Path tempDir;

	@Test
	void windowModeWritesEvaluatedRowsIncludingRejected() throws Exception {
		// cache with three entries: w1 (accepted handoff), w2 (rejected too-slow handoff),
		// x3 (random pair-gen segment, NOT in window set)
		MatsimNetworkCache cache = MatsimNetworkCache.forTesting();
		cache.putForTesting(Id.createLinkId("a"), Id.createLinkId("b"), new TravelSegment(10, 100, -1));
		cache.putForTesting(Id.createLinkId("a"), Id.createLinkId("c"), new TravelSegment(9999, 100, -1));
		cache.putForTesting(Id.createLinkId("x"), Id.createLinkId("y"), new TravelSegment(5, 50, -1));
		LongOpenHashSet window = new LongOpenHashSet();
		window.add(PackedKeyCodec.segmentKey(Id.createLinkId("a").index(), Id.createLinkId("b").index(), 0));
		window.add(PackedKeyCodec.segmentKey(Id.createLinkId("a").index(), Id.createLinkId("c").index(), 0));

		Path out = tempDir.resolve("cc.csv");
		cache.exportWindow(out.toString(), window);

		List<String> lines = Files.readAllLines(out);
		assertEquals(3, lines.size()); // header + 2 window rows; x->y excluded
		assertTrue(lines.stream().anyMatch(l -> l.startsWith("a,c"))); // rejected row present
	}
}
