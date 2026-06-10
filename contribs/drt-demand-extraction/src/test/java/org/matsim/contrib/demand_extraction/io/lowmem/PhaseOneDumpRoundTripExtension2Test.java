package org.matsim.contrib.demand_extraction.io.lowmem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Phase 1 Task 1.5 — round-trip the two Extension-2 columns ({@code requestTag},
 * {@code hubId}) across the low-memory two-phase dump.
 *
 * <p>The writer side is delegation-only: {@link PhaseOneDumpWriter} calls
 * {@link org.matsim.contrib.demand_extraction.io.ExMasCsvWriter#writeRequests(String, java.util.List)}
 * which already emits the two columns (Task 1.2). This test exercises the reader
 * side: it must parse the columns and populate them on the reconstructed
 * {@link DrtRequest}s via the Builder.
 *
 * <p>Also verifies backward-compatibility: an old dump CSV without the two columns
 * must still read cleanly and surface {@code null} on both fields.
 */
class PhaseOneDumpRoundTripExtension2Test {

	private static final double WALK_SPEED = 1.4;
	private static final double MIN_WALK = 100.0;

	@Test
	void roundTripsRequestTagAndHubId(@TempDir Path tmp) throws IOException {
		PhaseOneDumpLayout layout = new PhaseOneDumpLayout(tmp);

		// One request per Extension-2 tag value. Only the "connecting" virtual trip
		// carries a non-null hubId; the others have hubId == null.
		List<DrtRequest> originals = List.of(
				LowMemTestFixtures.buildRequest(0, "home", "work", 43200.0, 28800.0,
						"rural_intra", null),
				LowMemTestFixtures.buildRequest(1, "home", "work", 43200.0, 28800.0,
						"urban_intra", null),
				LowMemTestFixtures.buildRequest(2, "home", "work", 43200.0, 28800.0,
						"connecting", "hub_03"),
				LowMemTestFixtures.buildRequest(3, "home", "work", 43200.0, 28800.0,
						"external", null));

		PhaseOneDumpWriter.Meta meta = new PhaseOneDumpWriter.Meta(
				"drt", WALK_SPEED, "LOG", MIN_WALK,
				"test-run-id", 1, 0L, 0L, null);

		PhaseOneDumpWriter.write(layout, originals, meta);

		PhaseOneDumpReader.DumpData loaded = PhaseOneDumpReader.read(layout);
		assertEquals(4, loaded.requests().size());

		Map<Integer, DrtRequest> reloadedByIdx = loaded.requests().stream()
				.collect(Collectors.toMap(r -> r.index, r -> r));

		for (DrtRequest original : originals) {
			DrtRequest reloaded = reloadedByIdx.get(original.index);
			assertNotNull(reloaded, "reloaded request missing for idx " + original.index);
			assertEquals(original.requestTag, reloaded.requestTag,
					"requestTag mismatch for idx " + original.index);
			assertEquals(original.hubId, reloaded.hubId,
					"hubId mismatch for idx " + original.index);
		}
	}

	/**
	 * Paper-2 Task 7 added three trailing columns
	 * ({@code hubLegRole,transferWaitSeconds,marginalUtilityOfMoney}). Verify they
	 * survive the dump write+read with their exact values: enum identity for the
	 * leg role, and doubles within the write precision ({@code %.2f} for
	 * transferWaitSeconds, {@code %.6f} for marginalUtilityOfMoney).
	 */
	@Test
	void roundTripsHubLegRoleTransferWaitAndMum(@TempDir Path tmp) throws IOException {
		PhaseOneDumpLayout layout = new PhaseOneDumpLayout(tmp);

		// idx 0: plain rural request — defaults (NONE / 0 / 0) plus a non-default mum.
		// idx 1: access leg into a hub with a transfer wait.
		// idx 2: continuation leg out of a hub with a transfer wait.
		List<DrtRequest> originals = List.of(
				LowMemTestFixtures.buildRequest(0, "home", "work", 43200.0, 28800.0,
						"rural_intra", null,
						DrtRequest.HubLegRole.NONE, 0.0, 0.085),
				LowMemTestFixtures.buildRequest(1, "home", "work", 43200.0, 28800.0,
						"connecting", "hub_03",
						DrtRequest.HubLegRole.ACCESS_LEG, 300.0, 0.085),
				LowMemTestFixtures.buildRequest(2, "home", "work", 43200.0, 28800.0,
						"connecting", "hub_03",
						DrtRequest.HubLegRole.CONTINUATION_LEG, 180.0, 0.012345));

		PhaseOneDumpWriter.Meta meta = new PhaseOneDumpWriter.Meta(
				"drt", WALK_SPEED, "LOG", MIN_WALK,
				"test-run-id", 1, 0L, 0L, null);

		PhaseOneDumpWriter.write(layout, originals, meta);

		PhaseOneDumpReader.DumpData loaded = PhaseOneDumpReader.read(layout);
		assertEquals(3, loaded.requests().size());

		Map<Integer, DrtRequest> reloadedByIdx = loaded.requests().stream()
				.collect(Collectors.toMap(r -> r.index, r -> r));

		for (DrtRequest original : originals) {
			DrtRequest reloaded = reloadedByIdx.get(original.index);
			assertNotNull(reloaded, "reloaded request missing for idx " + original.index);
			assertEquals(original.hubLegRole, reloaded.hubLegRole,
					"hubLegRole mismatch for idx " + original.index);
			assertEquals(original.transferWaitSeconds, reloaded.transferWaitSeconds, 1e-6,
					"transferWaitSeconds mismatch for idx " + original.index);
			assertEquals(original.marginalUtilityOfMoney, reloaded.marginalUtilityOfMoney, 1e-6,
					"marginalUtilityOfMoney mismatch for idx " + original.index);
		}
	}

	@Test
	void backwardCompatibleReadOfOldDumpWithoutNewColumns(@TempDir Path tmp) throws IOException {
		// First, perform a real round-trip dump so the BIN + JSON + the other CSV
		// columns are consistent, then rewrite the CSV stripping the three trailing
		// Paper-2 Task-7 columns (hubLegRole,transferWaitSeconds,marginalUtilityOfMoney)
		// to simulate a legacy dump written before they existed.
		PhaseOneDumpLayout layout = new PhaseOneDumpLayout(tmp);

		List<DrtRequest> originals = List.of(
				LowMemTestFixtures.buildRequest(0, "home", "work", 43200.0, 28800.0),
				LowMemTestFixtures.buildRequest(1, "work", "home", 28800.0, 43200.0));

		PhaseOneDumpWriter.Meta meta = new PhaseOneDumpWriter.Meta(
				"drt", WALK_SPEED, "LOG", MIN_WALK,
				"test-run-id", 1, 0L, 0L, null);

		PhaseOneDumpWriter.write(layout, originals, meta);

		// Strip the three trailing Task-7 columns
		// ("hubLegRole,transferWaitSeconds,marginalUtilityOfMoney") from every line,
		// leaving requestTag,hubId as the new boundary — a pre-Task-7 legacy dump.
		Path csv = layout.requestsCsv();
		List<String> lines = Files.readAllLines(csv);
		Files.write(csv, lines.stream().map(line -> stripTrailingColumns(line, 3))
				.collect(Collectors.toList()));

		PhaseOneDumpReader.DumpData loaded = PhaseOneDumpReader.read(layout);
		assertEquals(2, loaded.requests().size());
		for (DrtRequest reloaded : loaded.requests()) {
			assertEquals(DrtRequest.HubLegRole.NONE, reloaded.hubLegRole,
					"legacy dump without hubLegRole column must default to NONE (idx " + reloaded.index + ")");
			assertEquals(0.0, reloaded.transferWaitSeconds, 1e-6,
					"legacy dump without transferWaitSeconds column must default to 0.0 (idx " + reloaded.index + ")");
			assertEquals(0.0, reloaded.marginalUtilityOfMoney, 1e-6,
					"legacy dump without marginalUtilityOfMoney column must default to 0.0 (idx " + reloaded.index + ")");
		}
	}

	/** Drop the {@code n} trailing comma-separated columns from one CSV line. */
	private static String stripTrailingColumns(String line, int n) {
		int cut = line.length();
		for (int k = 0; k < n; k++) {
			cut = line.lastIndexOf(',', cut - 1);
			if (cut < 0) {
				throw new IllegalArgumentException("line has fewer than " + n + " columns: " + line);
			}
		}
		return line.substring(0, cut);
	}
}
