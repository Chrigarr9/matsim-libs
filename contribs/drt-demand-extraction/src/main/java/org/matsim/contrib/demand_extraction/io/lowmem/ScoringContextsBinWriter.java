package org.matsim.contrib.demand_extraction.io.lowmem;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes the per-request scoring context dump used by Phase 2.
 *
 * <p>Format (single file, little-endian via {@link DataOutputStream}):
 * <pre>
 * int32 magic = 0xDE5C0DE1
 * int32 version = 1
 * int32 numRequests
 * int32 numActivityTypes
 * // Activity-type table (repeated numActivityTypes times)
 * utf   activityType
 * f64   typicalDuration_s
 * bool  scoreAtAll
 * // Request records (repeated numRequests times, 1 + 1 + 1 + 8*4 = 35 bytes/row)
 * int32 requestIndex
 * int8  originActivityTypeIdx   (-1 = synthetic activity, not in the table)
 * int8  destActivityTypeIdx     (-1 = synthetic activity, not in the table)
 * f64   originDuration_s
 * f64   destDuration_s
 * f64   marginalUtilityOfPerforming_s
 * f64   marginalUtilityOfWaitingPt_s
 * </pre>
 */
public final class ScoringContextsBinWriter implements Closeable {

	public record ActivityTypeRow(String type, double typicalDuration, boolean scoreAtAll) {}

	public record RequestRow(
			int requestIndex,
			byte originActivityTypeIdx,
			byte destActivityTypeIdx,
			double originDuration,
			double destDuration,
			double marginalUtilityOfPerforming_s,
			double marginalUtilityOfWaitingPt_s) {}

	private final DataOutputStream out;
	private boolean headerWritten = false;

	public ScoringContextsBinWriter(Path path) throws IOException {
		this.out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)));
	}

	public void writeHeader(int numRequests, List<ActivityTypeRow> activityTypes) throws IOException {
		if (headerWritten) throw new IllegalStateException("header already written");
		out.writeInt(PhaseOneDumpLayout.SCORING_CONTEXTS_MAGIC);
		out.writeInt(PhaseOneDumpLayout.SCORING_CONTEXTS_VERSION);
		out.writeInt(numRequests);
		out.writeInt(activityTypes.size());
		for (ActivityTypeRow t : activityTypes) {
			out.writeUTF(t.type);
			out.writeDouble(t.typicalDuration);
			out.writeBoolean(t.scoreAtAll);
		}
		headerWritten = true;
	}

	public void writeRow(RequestRow r) throws IOException {
		if (!headerWritten) throw new IllegalStateException("write header first");
		out.writeInt(r.requestIndex);
		out.writeByte(r.originActivityTypeIdx);
		out.writeByte(r.destActivityTypeIdx);
		out.writeDouble(r.originDuration);
		out.writeDouble(r.destDuration);
		out.writeDouble(r.marginalUtilityOfPerforming_s);
		out.writeDouble(r.marginalUtilityOfWaitingPt_s);
	}

	@Override
	public void close() throws IOException {
		out.close();
	}
}
