package org.matsim.contrib.demand_extraction.algorithm.bamas.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;

/**
 * Task 20 (spec 6.2, risk row 1): {@link RunRecord} writes {@code _extraction_run.json}
 * UNCONDITIONALLY -- checkpointing on or off -- so Python can always read the run fingerprint
 * instead of recomputing it. None of these tests turn checkpointing on.
 */
class RunRecordTest {

	private static ExMasConfigGroup config() {
		ExMasConfigGroup c = new ExMasConfigGroup();
		c.setMaxPoolingDegree(6);
		return c;
	}

	@Test
	void writesTheFile(@TempDir Path outDir) {
		RunRecord.write(outDir, config(), null, null, null, "bamas");
		assertTrue(Files.isRegularFile(outDir.resolve(RunRecord.FILE_NAME)));
	}

	@Test
	void fingerprintEqualsRunFingerprintComputeForTheSameInputs(@TempDir Path outDir)
			throws IOException {
		ExMasConfigGroup cfg = config();
		RunRecord.write(outDir, cfg, null, null, null, "bamas");
		String expected = RunFingerprint.compute(cfg, null, null, null, "bamas");
		assertEquals(expected, readStringField(outDir, "fingerprint"));
	}

	@Test
	void fingerprintHashesRealInputFilesJustLikeRunFingerprintCompute(@TempDir Path outDir)
			throws IOException {
		Path requests = Files.writeString(outDir.resolve("requests.csv"), "a,b,c\n");
		Path travelTimes = Files.writeString(outDir.resolve("tt.tsv"), "o\td\tt\n1\t2\t3\n");
		ExMasConfigGroup cfg = config();
		RunRecord.write(outDir, cfg, requests, travelTimes, null, "bamas");
		String expected = RunFingerprint.compute(cfg, requests, travelTimes, null, "bamas");
		assertEquals(expected, readStringField(outDir, "fingerprint"));
	}

	@Test
	void paramsHasSameSizeAsConfigGetParams(@TempDir Path outDir) throws IOException {
		ExMasConfigGroup cfg = config();
		RunRecord.write(outDir, cfg, null, null, null, "bamas");
		String json = Files.readString(outDir.resolve(RunRecord.FILE_NAME));
		assertEquals(cfg.getParams().size(), countKeysInObject(json, "\"params\""));
	}

	@Test
	void writingTwiceIsIdempotent(@TempDir Path outDir) throws IOException {
		ExMasConfigGroup cfg = config();

		RunRecord.write(outDir, cfg, null, null, null, "bamas");
		String firstFingerprint = readStringField(outDir, "fingerprint");
		int firstParamCount = countKeysInObject(
				Files.readString(outDir.resolve(RunRecord.FILE_NAME)), "\"params\"");

		RunRecord.write(outDir, cfg, null, null, null, "bamas");
		String secondFingerprint = readStringField(outDir, "fingerprint");
		int secondParamCount = countKeysInObject(
				Files.readString(outDir.resolve(RunRecord.FILE_NAME)), "\"params\"");

		assertEquals(firstFingerprint, secondFingerprint);
		assertEquals(firstParamCount, secondParamCount);
		assertTrue(Files.isRegularFile(outDir.resolve(RunRecord.FILE_NAME)));
	}

	// ---- tiny hand-rolled JSON readers for the test, mirroring RunRecord's hand-rolled writer
	// (no JSON library dependency on this module's compile classpath) ----

	private static String readStringField(Path outDir, String key) throws IOException {
		String json = Files.readString(outDir.resolve(RunRecord.FILE_NAME));
		String needle = "\"" + key + "\": \"";
		int start = json.indexOf(needle);
		if (start < 0) {
			throw new AssertionError("key '" + key + "' not found in " + json);
		}
		start += needle.length();
		int end = json.indexOf('"', start);
		return json.substring(start, end);
	}

	/** Counts top-level entries inside the {@code { ... }} object that follows {@code keyMarker}. */
	private static int countKeysInObject(String json, String keyMarker) {
		int objStart = json.indexOf(keyMarker);
		if (objStart < 0) {
			throw new AssertionError("key marker '" + keyMarker + "' not found in " + json);
		}
		int braceStart = json.indexOf('{', objStart);
		int braceEnd = matchingBrace(json, braceStart);
		String body = json.substring(braceStart + 1, braceEnd).trim();
		if (body.isEmpty()) {
			return 0;
		}
		int count = 1;
		boolean inQuotes = false;
		boolean escaped = false;
		for (int i = 0; i < body.length(); i++) {
			char c = body.charAt(i);
			if (escaped) {
				escaped = false;
				continue;
			}
			if (c == '\\') {
				escaped = true;
				continue;
			}
			if (c == '"') {
				inQuotes = !inQuotes;
				continue;
			}
			if (c == ',' && !inQuotes) {
				count++;
			}
		}
		return count;
	}

	private static int matchingBrace(String json, int openIdx) {
		int depth = 0;
		boolean inQuotes = false;
		boolean escaped = false;
		for (int i = openIdx; i < json.length(); i++) {
			char c = json.charAt(i);
			if (escaped) {
				escaped = false;
				continue;
			}
			if (c == '\\') {
				escaped = true;
				continue;
			}
			if (c == '"') {
				inQuotes = !inQuotes;
				continue;
			}
			if (inQuotes) {
				continue;
			}
			if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		throw new AssertionError("no matching closing brace found from index " + openIdx);
	}
}
