package org.matsim.contrib.demand_extraction.io.lowmem;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the {@link ScoringContextsBinWriter} format. Pair the header read with sequential row reads.
 *
 * <p>Supports both v1 (legacy, no walk/wait caps — fields surface as 0) and v2 (current).
 * Callers needing the version after reading the header can read {@link Header#version()}.
 */
public final class ScoringContextsBinReader implements Closeable {

	public record Header(int version, int numRequests, List<ScoringContextsBinWriter.ActivityTypeRow> activityTypes) {}

	private final DataInputStream in;
	private Header header;

	public ScoringContextsBinReader(Path path) throws IOException {
		this.in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)));
	}

	public Header readHeader() throws IOException {
		if (header != null) return header;
		int magic;
		try {
			magic = in.readInt();
		} catch (EOFException e) {
			throw new IOException("scoring_contexts.bin: empty or truncated header", e);
		}
		if (magic != PhaseOneDumpLayout.SCORING_CONTEXTS_MAGIC) {
			throw new IOException(String.format("scoring_contexts.bin: bad magic 0x%08x", magic));
		}
		int version = in.readInt();
		if (version != 1 && version != PhaseOneDumpLayout.SCORING_CONTEXTS_VERSION) {
			throw new IOException("scoring_contexts.bin: unsupported version " + version);
		}
		int numRequests = in.readInt();
		int numTypes = in.readInt();
		List<ScoringContextsBinWriter.ActivityTypeRow> types = new ArrayList<>(numTypes);
		for (int i = 0; i < numTypes; i++) {
			String type = in.readUTF();
			double dur = in.readDouble();
			boolean score = in.readBoolean();
			types.add(new ScoringContextsBinWriter.ActivityTypeRow(type, dur, score));
		}
		header = new Header(version, numRequests, types);
		return header;
	}

	public ScoringContextsBinWriter.RequestRow readRow() throws IOException {
		if (header == null) readHeader();
		int requestIndex = in.readInt();
		byte originActivityTypeIdx = in.readByte();
		byte destActivityTypeIdx = in.readByte();
		double originDuration = in.readDouble();
		double destDuration = in.readDouble();
		double marginalUtilityOfPerforming_s = in.readDouble();
		double marginalUtilityOfWaitingPt_s = in.readDouble();
		double maxWalkDistance = 0.0;
		double maxWaitTime = 0.0;
		if (header.version() >= 2) {
			maxWalkDistance = in.readDouble();
			maxWaitTime = in.readDouble();
		}
		return new ScoringContextsBinWriter.RequestRow(
				requestIndex, originActivityTypeIdx, destActivityTypeIdx,
				originDuration, destDuration,
				marginalUtilityOfPerforming_s, marginalUtilityOfWaitingPt_s,
				maxWalkDistance, maxWaitTime);
	}

	@Override
	public void close() throws IOException {
		in.close();
	}
}
