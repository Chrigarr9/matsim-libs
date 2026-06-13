package org.matsim.contrib.demand_extraction.algorithm.bamas.checkpoint;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;

/**
 * Stable compatibility fingerprint for checkpoint/resume (Plan A3).
 *
 * <p>A resume must reproduce the exact stubs and cache values of the interrupted run, so it
 * refuses to continue if any identity-affecting input changed. The fingerprint is a SHA-256
 * over:
 * <ul>
 *   <li>a {@link #CHECKPOINT_VERSION} constant (bump to invalidate all old checkpoints),</li>
 *   <li>the caller-supplied algorithm-version tag (e.g. {@code "bamas"} vs {@code "exmas"}),</li>
 *   <li>every {@link ExMasConfigGroup} {@code @StringGetter} param EXCEPT {@link #EXCLUDED_PARAMS},
 *       read via {@code getParams()} so new knobs (e.g. Plan B's {@code extensionParentsTopK} —
 *       review addendum F4) are captured automatically and cannot silently drift,</li>
 *   <li>SHA-256 of the routing-relevant input files (requests, travel-times, network) — their
 *       contents determine cache values and feasibility.</li>
 * </ul>
 *
 * <p><b>Why hash the whole param map instead of a curated list?</b> A curated list is exactly
 * what let Plan B's pruning knobs slip past the original plan. Over-refusal (refusing a resume
 * that <i>could</i> have continued) is safe; under-refusal silently corrupts. The only params
 * excluded are those that provably do not change stub/cache identity.
 *
 * <p><b>{@code algorithmProcessCount} is excluded</b> on purpose: routing is wrapped in
 * {@link org.matsim.contrib.demand_extraction.algorithm.network.DeterministicTravelDisutility},
 * which makes the routed output engine-, thread-, and batch-order-independent (verified by
 * {@code CrossEngineRoutingDeterminismTest}), so a crash on one box may resume on another with a
 * different core count without changing stub/cache identity. <b>This exclusion is sound only while
 * that deterministic decorator is active on the fingerprint-bearing routing path</b>;
 * {@code RunFingerprintTest} pins {@link #EXCLUDED_PARAMS} to exactly the two keys below, so any
 * new exclusion must be a deliberate, reviewed edit rather than a silent under-refusal.
 * <p>(The earlier "Plan A made the output scheduling-independent" rationale was refuted by
 * {@code outputs/a3-killresume-gate-1pct/GATE_RESULTS.md}: BAMAS is NOT scheduling-independent in
 * general; determinism comes specifically from the disutility decorator above.)
 * <b>{@code checkpointDir} is excluded</b> because the checkpoint location is not part of the
 * algorithm identity (resuming a moved checkpoint dir is legitimate).
 */
public final class RunFingerprint {

	/** Bump to force all existing checkpoints to be treated as incompatible. */
	public static final int CHECKPOINT_VERSION = 1;

	/**
	 * Config params that do NOT affect stub/cache identity and are excluded from the hash.
	 * Package-private so {@code RunFingerprintTest} can pin this set exactly — adding a key is a
	 * silent under-refusal risk and must be a deliberate, reviewed edit (see the class javadoc on
	 * why {@code algorithmProcessCount} is excludable only while routing stays deterministic).
	 */
	static final Set<String> EXCLUDED_PARAMS = Set.of(
			"algorithmProcessCount",  // thread count — routing is deterministic via DeterministicTravelDisutility (CrossEngineRoutingDeterminismTest)
			"checkpointDir");         // the checkpoint location itself

	private RunFingerprint() {}

	/**
	 * Compute the fingerprint. File arguments may be {@code null} (e.g. in unit tests), in which
	 * case a fixed marker is hashed in their place so the structure stays stable.
	 *
	 * @param config           the algorithm config
	 * @param requestsPath     DRT requests file, or {@code null}
	 * @param travelTimesPath  travel-times TSV, or {@code null}
	 * @param networkPath      network file, or {@code null}
	 * @param algorithmVersion algorithm/version tag (e.g. {@code "bamas"})
	 * @return lowercase hex SHA-256
	 */
	public static String compute(ExMasConfigGroup config, Path requestsPath, Path travelTimesPath,
			Path networkPath, String algorithmVersion) {
		MessageDigest md = sha256();

		update(md, "CHECKPOINT_VERSION=" + CHECKPOINT_VERSION);
		update(md, "algorithmVersion=" + (algorithmVersion == null ? "null" : algorithmVersion));

		// Config params, sorted for stable ordering, minus the excluded keys.
		Map<String, String> params = new TreeMap<>(config.getParams());
		update(md, "config{");
		for (Map.Entry<String, String> e : params.entrySet()) {
			if (EXCLUDED_PARAMS.contains(e.getKey())) {
				continue;
			}
			update(md, e.getKey() + "=" + e.getValue() + ";");
		}
		update(md, "}");

		// Routing-relevant input files: content hash (or a marker when absent).
		update(md, "requests=" + fileDigest(requestsPath));
		update(md, "travelTimes=" + fileDigest(travelTimesPath));
		update(md, "network=" + fileDigest(networkPath));

		return toHex(md.digest());
	}

	/** True if two fingerprints are compatible (exact equality). */
	public static boolean matches(String stored, String current) {
		return stored != null && stored.equals(current);
	}

	// ------------------------------------------------------------------

	private static String fileDigest(Path path) {
		if (path == null) {
			return "null";
		}
		MessageDigest md = sha256();
		byte[] buf = new byte[1 << 16];
		try (InputStream in = Files.newInputStream(path)) {
			int n;
			while ((n = in.read(buf)) != -1) {
				md.update(buf, 0, n);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot hash fingerprint input file: " + path, e);
		}
		return toHex(md.digest());
	}

	private static void update(MessageDigest md, String s) {
		md.update(s.getBytes(StandardCharsets.UTF_8));
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	private static String toHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16));
			sb.append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString();
	}
}
