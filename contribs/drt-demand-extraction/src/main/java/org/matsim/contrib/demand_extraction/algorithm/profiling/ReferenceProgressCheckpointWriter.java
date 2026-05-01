package org.matsim.contrib.demand_extraction.algorithm.profiling;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

public final class ReferenceProgressCheckpointWriter implements AutoCloseable, ReferenceProgressSink {
	private static final String HEADER =
			"run,degree,status,sample_kind,sets_processed,sets_total,rides_retained,candidates_added,heap_used_gb,heap_committed_gb,heap_max_gb,elapsed_ms,gc_ms,note";

	private final BufferedWriter writer;

	public ReferenceProgressCheckpointWriter(Path path) throws IOException {
		Path parent = path.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}

		boolean writeHeader = !Files.exists(path) || Files.size(path) == 0;
		this.writer = Files.newBufferedWriter(
				path,
				StandardOpenOption.CREATE,
				StandardOpenOption.APPEND,
				StandardOpenOption.WRITE);

		if (writeHeader) {
			writer.write(HEADER);
			writer.newLine();
			writer.flush();
		}
	}

	public synchronized void append(ReferenceProgressCheckpoint checkpoint) throws IOException {
		writer.write(format(checkpoint));
		writer.newLine();
		writer.flush();
	}

	@Override
	public void record(ReferenceProgressCheckpoint checkpoint) {
		try {
			append(checkpoint);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to append reference progress checkpoint", e);
		}
	}

	private static String format(ReferenceProgressCheckpoint checkpoint) {
		return String.join(",",
				escape(checkpoint.run()),
				Integer.toString(checkpoint.degree()),
				escape(checkpoint.status()),
				escape(checkpoint.sampleKind()),
				Long.toString(checkpoint.setsProcessed()),
				Long.toString(checkpoint.setsTotal()),
				Long.toString(checkpoint.ridesRetained()),
				Long.toString(checkpoint.candidatesAdded()),
				formatDouble(checkpoint.heapUsedGb()),
				formatDouble(checkpoint.heapCommittedGb()),
				formatDouble(checkpoint.heapMaxGb()),
				Long.toString(checkpoint.elapsedMs()),
				Long.toString(checkpoint.gcMs()),
				escape(checkpoint.note()));
	}

	private static String formatDouble(double value) {
		return String.format(Locale.ROOT, "%.3f", value);
	}

	private static String escape(String value) {
		if (value == null) {
			return "";
		}

		boolean needsQuotes = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
		if (!needsQuotes) {
			return value;
		}

		return '"' + value.replace("\"", "\"\"") + '"';
	}

	@Override
	public void close() throws IOException {
		writer.close();
	}
}