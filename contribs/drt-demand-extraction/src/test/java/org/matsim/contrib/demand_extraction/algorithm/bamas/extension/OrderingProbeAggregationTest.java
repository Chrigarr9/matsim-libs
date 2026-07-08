package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coverage for the per-degree AVERAGED ordering probe (one row per degree, not one row per set).
 * The probe folds each finished set into a per-degree running total and emits a single averaged
 * row per degree at {@link EnumerationStats#writeProbeSummary()}. Sets that never produced a
 * budget-valid ordering (firstValid/best == -1) are counted in {@code sets} but excluded from the
 * first-valid/best means (denominator {@code validSets}); degrees below the min-degree are ignored.
 *
 * <p>The probe uses static state, so each test re-arms it via {@code setProbePath} (which clears the
 * buckets) and {@link #resetProbe()} disables it afterwards so no state leaks between tests.
 */
class OrderingProbeAggregationTest {

	@AfterEach
	void resetProbe() {
		EnumerationStats.setProbePath(null);
		EnumerationStats.setProbeMinDegree(6);
	}

	/** Fold one finished set of the given degree into the probe via the public scratch fields. */
	private static void feed(int degree, long nodes, long firstValid, long best) {
		EnumerationStats s = new EnumerationStats();
		s.curSetNodes = nodes;
		s.curSetNodesFirstValid = firstValid;
		s.curSetNodesBest = best;
		s.probeSetEnd(degree);
	}

	@Test
	void aggregatesOneAveragedRowPerDegreeAndExcludesInvalidSetsFromValidMeans(@TempDir Path tmp) throws Exception {
		Path csv = tmp.resolve("ordering_probe.csv");
		EnumerationStats.setProbeMinDegree(3);
		EnumerationStats.setProbePath(csv.toString()); // arms + clears buckets

		// degree 3: two valid sets + one set that never found a valid ordering (-1)
		feed(3, 100, 10, 10);
		feed(3, 200, 30, 50);
		feed(3, 300, -1, -1);
		// degree 5: a single valid set
		feed(5, 80, 8, 8);
		// degree 2: below the min-degree -> must be dropped
		feed(2, 999, 1, 1);

		EnumerationStats.writeProbeSummary();

		List<String> lines = Files.readAllLines(csv);
		assertEquals(3, lines.size(), "header + one row per recorded degree (degree 2 filtered out)");
		assertEquals("degree,sets,validSets,meanNodesTotal,meanNodesToFirstValid,meanNodesToBest,"
				+ "maxNodesTotal,maxNodesToFirstValid,maxNodesToBest", lines.get(0));

		List<String> cols = List.of(lines.get(0).split(",", -1));
		Map<String, String[]> byDeg = new HashMap<>();
		for (int i = 1; i < lines.size(); i++) {
			String[] v = lines.get(i).split(",", -1);
			byDeg.put(v[0], v);
		}

		String[] d3 = byDeg.get("3");
		assertNotNull(d3, "degree 3 row must exist");
		assertEquals("3", d3[cols.indexOf("sets")]);                                     // 3 sets total
		assertEquals("2", d3[cols.indexOf("validSets")]);                                // only 2 valid
		assertEquals(200.0, Double.parseDouble(d3[cols.indexOf("meanNodesTotal")]));     // (100+200+300)/3
		assertEquals(20.0, Double.parseDouble(d3[cols.indexOf("meanNodesToFirstValid")])); // (10+30)/2, -1 excluded
		assertEquals(30.0, Double.parseDouble(d3[cols.indexOf("meanNodesToBest")]));     // (10+50)/2
		assertEquals("300", d3[cols.indexOf("maxNodesTotal")]);
		assertEquals("30", d3[cols.indexOf("maxNodesToFirstValid")]);
		assertEquals("50", d3[cols.indexOf("maxNodesToBest")]);

		assertNotNull(byDeg.get("5"), "degree 5 row must exist");
		assertNull(byDeg.get("2"), "degree below min-degree must not be recorded");
	}

	@Test
	void degreeWithNoValidOrderingsReportsMinusOneMeansButRealNodeTotal(@TempDir Path tmp) throws Exception {
		Path csv = tmp.resolve("ordering_probe.csv");
		EnumerationStats.setProbeMinDegree(3);
		EnumerationStats.setProbePath(csv.toString());

		feed(4, 500, -1, -1);
		feed(4, 700, -1, -1);

		EnumerationStats.writeProbeSummary();

		List<String> lines = Files.readAllLines(csv);
		List<String> cols = List.of(lines.get(0).split(",", -1));
		String[] d4 = lines.get(1).split(",", -1);
		assertEquals("2", d4[cols.indexOf("sets")]);
		assertEquals("0", d4[cols.indexOf("validSets")]);
		assertEquals(600.0, Double.parseDouble(d4[cols.indexOf("meanNodesTotal")]));      // node totals still averaged
		assertEquals(-1.0, Double.parseDouble(d4[cols.indexOf("meanNodesToFirstValid")])); // no valid -> -1 sentinel
		assertEquals(-1.0, Double.parseDouble(d4[cols.indexOf("meanNodesToBest")]));
	}

	@Test
	void writeProbeSummaryIsIdempotent(@TempDir Path tmp) throws Exception {
		Path csv = tmp.resolve("ordering_probe.csv");
		EnumerationStats.setProbeMinDegree(3);
		EnumerationStats.setProbePath(csv.toString());
		feed(3, 100, 10, 10);

		EnumerationStats.writeProbeSummary();
		EnumerationStats.writeProbeSummary(); // shutdown hook would call again — must not append twice

		List<String> lines = Files.readAllLines(csv);
		assertEquals(2, lines.size(), "header + one row, not duplicated by a second flush");
	}
}
