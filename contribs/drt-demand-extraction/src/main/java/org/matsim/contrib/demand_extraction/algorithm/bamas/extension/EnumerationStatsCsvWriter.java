package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Persists aggregated per-degree {@link EnumerationStats} as {@code <statsDir>/enumeration_stats.csv}.
 *
 * <p>One row is appended per degree at the extension-loop degree boundary — a single-threaded site
 * on the main thread after the parallel workers have joined (the sum+log site in
 * {@code BamasRideExtender}) — so this needs no locking of its own. The header is written once, when
 * the file is first created (or is empty). Directory creation is idempotent. IO failures are logged
 * and swallowed: analytics must never fail a multi-hour extraction run.
 *
 * <p>The column contract lives on {@link EnumerationStats#csvHeader()} / {@link EnumerationStats#toCsvRow}.
 */
public final class EnumerationStatsCsvWriter {

	private static final Logger log = LogManager.getLogger(EnumerationStatsCsvWriter.class);

	/** Default file name written under the stats directory. */
	public static final String FILE_NAME = "enumeration_stats.csv";

	private EnumerationStatsCsvWriter() {}

	/**
	 * Append one per-degree row to {@code <statsDir>/enumeration_stats.csv}, creating the directory
	 * and header on first use.
	 *
	 * @param statsDir      the {@code <outputDir>/drt_demand/stats} directory (created if absent)
	 * @param total         the cross-thread aggregate for this degree
	 * @param degree        the degree these stats were produced for
	 * @param threads       worker parallelism used
	 * @param ridesEmitted  rides kept/emitted for this degree (corpus histogram bin)
	 * @param wallClockMs   wall-clock milliseconds spent on this degree's extension
	 * @param heapUsedBytes used heap sampled at the degree boundary
	 */
	public static void append(Path statsDir, EnumerationStats total, int degree, int threads,
			long ridesEmitted, long wallClockMs, long heapUsedBytes) {
		try {
			Files.createDirectories(statsDir);
			Path csv = statsDir.resolve(FILE_NAME);
			boolean writeHeader = !Files.exists(csv) || Files.size(csv) == 0;
			try (BufferedWriter w = Files.newBufferedWriter(csv,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
				if (writeHeader) {
					w.write(EnumerationStats.csvHeader());
					w.write("\n");
				}
				w.write(total.toCsvRow(degree, threads, ridesEmitted, wallClockMs, heapUsedBytes));
				w.write("\n");
			}
		} catch (IOException e) {
			log.warn("Could not append enumeration stats row for degree {} to {}: {}",
					degree, statsDir, e.toString());
		}
	}
}
