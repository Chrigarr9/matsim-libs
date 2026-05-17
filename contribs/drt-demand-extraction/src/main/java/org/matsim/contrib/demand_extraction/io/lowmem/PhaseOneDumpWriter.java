package org.matsim.contrib.demand_extraction.io.lowmem;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.matsim.api.core.v01.population.Activity;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.demand_extraction.io.ExMasCsvWriter;
import org.matsim.core.scoring.functions.ActivityUtilityParameters;
import org.matsim.core.scoring.functions.ScoringParameters;

/**
 * Glues the three Phase-1 dump artifacts (CSV + BIN + JSON) so callers run one
 * method after STEP 3 of {@code DemandExtractionListener}.
 *
 * <p>Pure I/O: no Guice, no Controler. Each request must carry a populated
 * {@link DrtRequest.ScoringContext}; activity-type metadata is harvested from
 * the first context that mentions a given type.
 */
public final class PhaseOneDumpWriter {

	/**
	 * Process-level metadata the Phase-2 runner consumes verbatim. Algorithm/pruning
	 * knobs do <b>not</b> live here — Phase 2 reloads the full Phase-1 output config
	 * XML via its own runner argument (kept out of this writer so it stays I/O-only).
	 */
	public record Meta(
			String drtMode,
			double walkSpeed,
			String opportunityCostModel,
			double minDrtAccessEgressDistance,
			String runId,
			int sampleSize,
			long phase1WallTimeMs,
			long phase1PeakHeapBytes) {}

	private PhaseOneDumpWriter() {}

	public static void write(PhaseOneDumpLayout layout, List<DrtRequest> requests, Meta meta) throws IOException {
		Files.createDirectories(layout.root());

		// 1. CSV via existing writer (already extended in Task 5).
		ExMasCsvWriter.writeRequests(layout.requestsCsv().toString(), requests);

		// 2. BIN: build activity-type table from the scoring contexts, then write rows.
		LinkedHashMap<String, Integer> typeIdx = new LinkedHashMap<>();
		List<ScoringContextsBinWriter.ActivityTypeRow> typeRows = new ArrayList<>();
		for (DrtRequest req : requests) {
			DrtRequest.ScoringContext ctx = requireContext(req);
			collectType(typeIdx, typeRows, ctx.originActivity(), ctx.scoringParams());
			collectType(typeIdx, typeRows, ctx.destActivity(), ctx.scoringParams());
		}
		try (ScoringContextsBinWriter w = new ScoringContextsBinWriter(layout.scoringContextsBin())) {
			w.writeHeader(requests.size(), typeRows);
			for (DrtRequest req : requests) {
				DrtRequest.ScoringContext ctx = requireContext(req);
				w.writeRow(toRow(req, ctx, typeIdx));
			}
		}

		// 3. JSON meta.
		writeMetaJson(layout, meta, requests.size());
	}

	private static DrtRequest.ScoringContext requireContext(DrtRequest req) {
		DrtRequest.ScoringContext ctx = req.getScoringContext();
		if (ctx == null) {
			throw new IllegalStateException(
					"DrtRequest " + req.index + " has no ScoringContext - cannot dump for Phase 2.");
		}
		return ctx;
	}

	private static void collectType(Map<String, Integer> typeIdx,
			List<ScoringContextsBinWriter.ActivityTypeRow> rows,
			Activity act, ScoringParameters params) {
		if (act == null) return;
		String type = act.getType();
		if (type == null) return;
		// Synthetic activities are not persisted - they are rebuilt on the Phase-2 side
		// from the request's link IDs and coords.
		if ("drt_interaction".equals(type) || "unknown".equals(type)) return;
		if (typeIdx.containsKey(type)) return;
		int idx = typeIdx.size();
		if (idx > 126) {
			throw new IllegalStateException(
					"too many activity types for byte index (>127): " + idx + " types so far");
		}
		ActivityUtilityParameters ap = params != null ? params.utilParams.get(type) : null;
		double td = ap != null ? ap.getTypicalDuration() : 0.0;
		boolean sa = ap != null && ap.isScoreAtAll();
		typeIdx.put(type, idx);
		rows.add(new ScoringContextsBinWriter.ActivityTypeRow(type, td, sa));
	}

	private static ScoringContextsBinWriter.RequestRow toRow(DrtRequest req,
			DrtRequest.ScoringContext ctx, Map<String, Integer> typeIdx) {
		byte origIdx = activityIdx(ctx.originActivity(), typeIdx);
		byte destIdx = activityIdx(ctx.destActivity(), typeIdx);
		ScoringParameters sp = ctx.scoringParams();
		return new ScoringContextsBinWriter.RequestRow(
				req.index, origIdx, destIdx,
				ctx.originDuration(), ctx.destDuration(),
				sp.marginalUtilityOfPerforming_s,
				sp.marginalUtilityOfWaitingPt_s);
	}

	private static byte activityIdx(Activity act, Map<String, Integer> typeIdx) {
		if (act == null) return (byte) -1;
		Integer i = typeIdx.get(act.getType());
		return i == null ? (byte) -1 : i.byteValue();
	}

	private static void writeMetaJson(PhaseOneDumpLayout layout, Meta meta, int numRequests) throws IOException {
		try (BufferedWriter bw = Files.newBufferedWriter(layout.metaJson())) {
			bw.write("{\n");
			writeStringField(bw, "drtMode", meta.drtMode, true);
			writeNumberField(bw, "walkSpeed", meta.walkSpeed, true);
			writeStringField(bw, "opportunityCostModel", meta.opportunityCostModel, true);
			writeNumberField(bw, "minDrtAccessEgressDistance", meta.minDrtAccessEgressDistance, true);
			writeStringField(bw, "runId", meta.runId, true);
			writeNumberField(bw, "sampleSize", meta.sampleSize, true);
			writeNumberField(bw, "numRequests", numRequests, true);
			writeNumberField(bw, "phase1WallTimeMs", meta.phase1WallTimeMs, true);
			writeNumberField(bw, "phase1PeakHeapBytes", meta.phase1PeakHeapBytes, false);
			bw.write("}\n");
		}
	}

	private static void writeStringField(BufferedWriter bw, String key, String value, boolean trailingComma) throws IOException {
		bw.write("  \"");
		bw.write(key);
		bw.write("\": ");
		bw.write(jsonStringLiteral(value));
		bw.write(trailingComma ? ",\n" : "\n");
	}

	private static void writeNumberField(BufferedWriter bw, String key, double value, boolean trailingComma) throws IOException {
		bw.write("  \"");
		bw.write(key);
		bw.write("\": ");
		if (value == Math.floor(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15) {
			bw.write(Long.toString((long) value));
		} else {
			bw.write(Double.toString(value));
		}
		bw.write(trailingComma ? ",\n" : "\n");
	}

	private static void writeNumberField(BufferedWriter bw, String key, long value, boolean trailingComma) throws IOException {
		bw.write("  \"");
		bw.write(key);
		bw.write("\": ");
		bw.write(Long.toString(value));
		bw.write(trailingComma ? ",\n" : "\n");
	}

	private static String jsonStringLiteral(String s) {
		if (s == null) return "null";
		StringBuilder sb = new StringBuilder(s.length() + 2);
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		sb.append('"');
		return sb.toString();
	}
}
