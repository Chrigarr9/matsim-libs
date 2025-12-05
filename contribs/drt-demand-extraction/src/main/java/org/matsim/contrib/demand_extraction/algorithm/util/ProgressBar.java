package org.matsim.contrib.demand_extraction.algorithm.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Simple progress bar for console output.
 * Provides visual feedback during long-running operations.
 */
public class ProgressBar {
	private static final Logger log = LogManager.getLogger(ProgressBar.class);

	private final String taskName;
	private final long total;
	private final int barWidth;
	private long current;
	private long lastLoggedPercent;
	private final long startTime;

	public ProgressBar(String taskName, long total) {
		this(taskName, total, 50);
	}

	public ProgressBar(String taskName, long total, int barWidth) {
		this.taskName = taskName;
		this.total = total;
		this.barWidth = barWidth;
		this.current = 0;
		this.lastLoggedPercent = -1;
		this.startTime = System.currentTimeMillis();

		if (total > 0) {
			log.info("{}: Starting (total: {})", taskName, total);
		}
	}

	/**
	 * Update progress by 1 step.
	 */
	public void step() {
		step(1);
	}

	/**
	 * Update progress by specified number of steps.
	 */
	public void step(long n) {
		current += n;

		if (total <= 0)
			return;

		long percent = (current * 100) / total;

		// Log at 10% intervals or when complete
		if (percent >= lastLoggedPercent + 10 || current >= total) {
			lastLoggedPercent = percent;
			long elapsed = System.currentTimeMillis() - startTime;
			double rate = current / (elapsed / 1000.0);

			String bar = buildBar();
			String stats = String.format("%d/%d (%.1f%%) - %.1f items/s",
					current, total, percent * 1.0, rate);

			log.info("{}: {} {}", taskName, bar, stats);
		}
	}

	/**
	 * Set progress to specific value.
	 */
	public void update(long current) {
		this.current = current;
		step(0); // Trigger logging without incrementing
	}

	/**
	 * Mark progress as complete and log final statistics.
	 */
	public void finish() {
		current = total;
		long elapsed = System.currentTimeMillis() - startTime;
		double seconds = elapsed / 1000.0;
		double rate = total / seconds;

		String bar = buildBar();
		log.info("{}: {} Complete in {:.1f}s ({:.1f} items/s)",
				taskName, bar, seconds, rate);
	}

	/**
	 * Build visual progress bar string.
	 */
	private String buildBar() {
		if (total <= 0)
			return "[?]";

		int filled = (int) ((current * barWidth) / total);
		filled = Math.min(filled, barWidth);

		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < barWidth; i++) {
			sb.append(i < filled ? "=" : " ");
		}
		sb.append("]");

		return sb.toString();
	}

	public long getCurrent() {
		return current;
	}

	public long getTotal() {
		return total;
	}
}
