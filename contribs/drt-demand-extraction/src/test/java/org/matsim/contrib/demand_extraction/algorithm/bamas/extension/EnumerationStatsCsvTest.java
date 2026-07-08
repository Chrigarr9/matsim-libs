package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit coverage for the enumeration-stats CSV surface: {@link EnumerationStats#csvHeader()},
 * {@link EnumerationStats#toCsvRow}, and {@link EnumerationStatsCsvWriter}.
 */
class EnumerationStatsCsvTest {

	@Test
	void headerAndRowHaveEqualColumnCounts() {
		String header = EnumerationStats.csvHeader();
		String row = new EnumerationStats().toCsvRow(3, 8, 0L, 0L, 0L);
		int headerCols = header.split(",", -1).length;
		int rowCols = row.split(",", -1).length;
		assertEquals(headerCols, rowCols,
				"header and data row must have the same number of columns");
	}

	@Test
	void rowRendersKnownCounterAndDegreeLevelValues() {
		EnumerationStats s = new EnumerationStats();
		s.setsProcessed = 100;
		s.orderingsEvaluated = 250;
		s.ridesBuilt = 200;
		s.orderingBudgetHits = 7;
		s.timeTotal = 123456789L;

		String header = EnumerationStats.csvHeader();
		String row = s.toCsvRow(/* degree */ 5, /* threads */ 16,
				/* ridesEmitted */ 42, /* wallClockMs */ 999, /* heapUsedBytes */ 1234567);

		List<String> cols = List.of(header.split(",", -1));
		String[] vals = row.split(",", -1);

		// Degree-level leading columns.
		assertEquals("5", vals[cols.indexOf("degree")]);
		assertEquals("16", vals[cols.indexOf("threads")]);
		assertEquals("42", vals[cols.indexOf("ridesEmitted")]);
		assertEquals("999", vals[cols.indexOf("wallClockMs")]);
		assertEquals("1234567", vals[cols.indexOf("heapUsedBytes")]);
		// Counters.
		assertEquals("100", vals[cols.indexOf("setsProcessed")]);
		assertEquals("250", vals[cols.indexOf("orderingsEvaluated")]);
		assertEquals("200", vals[cols.indexOf("ridesBuilt")]);
		assertEquals("7", vals[cols.indexOf("orderingBudgetHits")]);
		assertEquals("123456789", vals[cols.indexOf("timeTotalNs")]);
	}

	@Test
	void writerCreatesHeaderThenAppendsRowsWithoutDuplicatingHeader(@TempDir Path tmp) throws Exception {
		Path statsDir = tmp.resolve("drt_demand").resolve("stats");

		EnumerationStats d3 = new EnumerationStats();
		d3.setsProcessed = 10;
		EnumerationStatsCsvWriter.append(statsDir, d3, 3, 4, 5, 100, 2048);

		EnumerationStats d4 = new EnumerationStats();
		d4.setsProcessed = 20;
		EnumerationStatsCsvWriter.append(statsDir, d4, 4, 4, 6, 200, 4096);

		Path csv = statsDir.resolve(EnumerationStatsCsvWriter.FILE_NAME);
		assertTrue(Files.exists(csv), "enumeration_stats.csv must be created");
		List<String> lines = Files.readAllLines(csv);
		assertEquals(3, lines.size(), "one header line + two data rows");
		assertEquals(EnumerationStats.csvHeader(), lines.get(0));

		List<String> cols = List.of(lines.get(0).split(",", -1));
		String[] r3 = lines.get(1).split(",", -1);
		String[] r4 = lines.get(2).split(",", -1);
		assertEquals("3", r3[cols.indexOf("degree")]);
		assertEquals("10", r3[cols.indexOf("setsProcessed")]);
		assertEquals("4", r4[cols.indexOf("degree")]);
		assertEquals("20", r4[cols.indexOf("setsProcessed")]);
	}
}
