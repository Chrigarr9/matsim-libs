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
 *
 * <p><b>Checkpoint fork (Plan B1):</b> the 6-arg {@link #compute(ExMasConfigGroup, Path, Path,
 * Path, String, int)} overload accepts a {@code resumeHighestDegree} parameter. When the checkpoint
 * being resumed sits strictly below {@link ExMasConfigGroup#getExtensionParentsTopKMinDegree()},
 * the {@link #FORKABLE_PARENT_PRUNING_PARAMS} are excluded from the hash. This is sound because
 * those knobs only shape stub/cache identity at degrees ≥ minDegree; a checkpoint produced entirely
 * below that threshold cannot have been influenced by them. The 5-arg overload always passes
 * {@code Integer.MAX_VALUE} (i.e. never below any valid minDegree), so its output is byte-identical
 * to the pre-fork implementation.
 */
public final class RunFingerprint {

	/**
	 * Bump to force all existing checkpoints to be treated as incompatible.
	 * <p>v2 (Plan B2): the manifest schema gained a {@code baseFingerprint=} line (the forkable-knob-free
	 * hash used for fork resume). A v1 manifest lacks that line and is refused by
	 * {@link CheckpointManager#readManifest()}. The constant is also folded into every fingerprint, so
	 * the bump independently invalidates any v1 checkpoint on the full-hash path.
	 */
	public static final int CHECKPOINT_VERSION = 2;

	/**
	 * Config params that do NOT affect stub/cache identity and are excluded from the hash.
	 * Package-private so {@code RunFingerprintTest} can pin this set exactly — adding a key is a
	 * silent under-refusal risk and must be a deliberate, reviewed edit (see the class javadoc on
	 * why {@code algorithmProcessCount} is excludable only while routing stays deterministic).
	 */
	static final Set<String> EXCLUDED_PARAMS = Set.of(
			"algorithmProcessCount",  // thread count — routing is deterministic via DeterministicTravelDisutility (CrossEngineRoutingDeterminismTest)
			"checkpointDir");         // the checkpoint location itself

	/**
	 * Parent-pruning knobs that can be varied across forks of the same checkpoint, provided the
	 * checkpoint was written strictly below {@link ExMasConfigGroup#getExtensionParentsTopKMinDegree()}.
	 * These knobs only shape stub/cache identity at degrees ≥ minDegree, so a checkpoint produced
	 * entirely below that threshold is unaffected by them.
	 * <p>Used by {@link #compute(ExMasConfigGroup, Path, Path, Path, String, int)}.
	 */
	public static final Set<String> FORKABLE_PARENT_PRUNING_PARAMS = Set.of(
			"extensionParentsTopK",
			"extensionParentsTopKMinDegree",
			"extensionParentsTopKMetric",
			"extensionParentsSelectionRule",
			"extensionParentsMmrLambda",
			"extensionParentsTier2NodeCap");

	/**
	 * Degree-bound knobs that can be varied across forks of the same checkpoint, under the same
	 * fork-below-minDegree condition as {@link #FORKABLE_PARENT_PRUNING_PARAMS}.
	 * <p>{@code maxPoolingDegree} is purely a STOPPING bound on the extension loop: it decides when
	 * the algorithm halts, never the byte-content of any committed stub at a lower degree. A checkpoint
	 * written entirely below {@code minDegree} therefore cannot have been influenced by it, so a resume
	 * that caps the degree (e.g. 7→2 to dump the degree-2 universe straight to CSV, reusing the pair-gen)
	 * is sound. Gated on {@code resumeHighestDegree < minDegree} like the parent-pruning set: it relaxes
	 * only for a strictly-below-minDegree checkpoint and stays strict (over-refuses) otherwise.
	 * <p>The full-hash 5-arg {@link #compute} path never skips these, so the existing
	 * "maxPoolingDegree changes the fingerprint" guard still holds for non-fork resumes.
	 */
	public static final Set<String> FORKABLE_DEGREE_BOUND_PARAMS = Set.of(
			"maxPoolingDegree");

	/**
	 * Post-processing-only knobs that can be varied across forks of the same checkpoint, under the same
	 * fork-below-minDegree condition. {@code calcPredecessors} and {@code calcShapleyValues} drive the
	 * {@code RidePostProcessor} passes that run AFTER ride generation, on the materialized rides — they
	 * never touch a stub, a cache value, or the routing journal at any degree. A checkpoint therefore
	 * cannot have been influenced by them, so a fork resume may toggle them (e.g. switch predecessors
	 * OFF for a degree-2 universe dump, whose 16.4M rides would otherwise drive the predecessor pass'
	 * boxed-Long window queue into the documented OOM). Gated on {@code resumeHighestDegree < minDegree}
	 * for consistency; the full-hash 5-arg path keeps hashing them, so non-fork resumes stay strict.
	 */
	public static final Set<String> FORKABLE_POSTPROCESS_PARAMS = Set.of(
			"calcPredecessors",
			"calcShapleyValues");

	private RunFingerprint() {}

	/**
	 * Compute the fingerprint. File arguments may be {@code null} (e.g. in unit tests), in which
	 * case a fixed marker is hashed in their place so the structure stays stable.
	 *
	 * <p>Equivalent to {@link #compute(ExMasConfigGroup, Path, Path, Path, String, int)} with
	 * {@code resumeHighestDegree = Integer.MAX_VALUE}: all params are hashed, output is byte-identical
	 * to the pre-fork implementation.
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
		return compute(config, requestsPath, travelTimesPath, networkPath, algorithmVersion,
				Integer.MAX_VALUE);
	}

	/**
	 * Compute the fingerprint for a checkpoint resume. When {@code resumeHighestDegree} is strictly
	 * less than {@link ExMasConfigGroup#getExtensionParentsTopKMinDegree()}, the
	 * {@link #FORKABLE_PARENT_PRUNING_PARAMS} are excluded from the hash — this allows one
	 * pair-gen/degree-3 checkpoint to feed a sweep of different parent-pruning configs without
	 * triggering a fingerprint mismatch. At or above {@code minDegree} the full param set is hashed,
	 * preserving the original refusal posture.
	 *
	 * @param config               the algorithm config
	 * @param requestsPath         DRT requests file, or {@code null}
	 * @param travelTimesPath      travel-times TSV, or {@code null}
	 * @param networkPath          network file, or {@code null}
	 * @param algorithmVersion     algorithm/version tag (e.g. {@code "bamas"})
	 * @param resumeHighestDegree  the highest degree already checkpointed (use {@code Integer.MAX_VALUE}
	 *                             to hash all params, equivalent to the 5-arg overload)
	 * @return lowercase hex SHA-256
	 */
	public static String compute(ExMasConfigGroup config, Path requestsPath, Path travelTimesPath,
			Path networkPath, String algorithmVersion, int resumeHighestDegree) {
		MessageDigest md = sha256();

		update(md, "CHECKPOINT_VERSION=" + CHECKPOINT_VERSION);
		update(md, "algorithmVersion=" + (algorithmVersion == null ? "null" : algorithmVersion));

		// Config params, sorted for stable ordering, minus the excluded keys.
		// When resuming strictly below minDegree, also skip the forkable parent-pruning knobs.
		boolean forkableSkipActive = resumeHighestDegree < config.getExtensionParentsTopKMinDegree();
		Map<String, String> params = new TreeMap<>(config.getParams());
		update(md, "config{");
		for (Map.Entry<String, String> e : params.entrySet()) {
			if (EXCLUDED_PARAMS.contains(e.getKey())) {
				continue;
			}
			if (forkableSkipActive && (FORKABLE_PARENT_PRUNING_PARAMS.contains(e.getKey())
					|| FORKABLE_DEGREE_BOUND_PARAMS.contains(e.getKey())
					|| FORKABLE_POSTPROCESS_PARAMS.contains(e.getKey()))) {
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

	/**
	 * Decide whether a stored manifest may be resumed under {@code config} (Plan B2). Testable in
	 * isolation (no algorithm run): drive it with a {@link CheckpointManager.Manifest} carrying the
	 * stored {@code fingerprint} + {@code baseFingerprint}.
	 *
	 * <ul>
	 *   <li><b>Fork ON and the checkpoint sits strictly below {@code minDegree}</b>
	 *       ({@code getExtensionParentsTopKMinDegree()}): compare the stored {@code baseFingerprint}
	 *       against {@code compute(config, …, m.highestDegree)}. Both sides are forkable-knob-free
	 *       (the resume-degree is below minDegree, and the base was hashed the same way at write time),
	 *       so they match iff the NON-forkable params agree — the parent-pruning knobs may differ.</li>
	 *   <li><b>Otherwise</b> (flag off, OR the checkpoint already reached minDegree, where those knobs
	 *       have shaped stubs): compare the stored full {@code fingerprint} against the full hash —
	 *       today's strict behaviour, byte-identical to the pre-fork guard.</li>
	 * </ul>
	 *
	 * @param m                 the stored manifest (its {@code fingerprint} + {@code baseFingerprint})
	 * @param config            the resuming run's config
	 * @param req               DRT requests file, or {@code null}
	 * @param tt                travel-times TSV, or {@code null}
	 * @param net               network file, or {@code null}
	 * @param algoVersion       algorithm/version tag (e.g. {@code "bamas"})
	 * @param forkBelowMinDegree opt-in fork flag ({@code config.isCheckpointForkBelowMinDegree()})
	 * @return true if the resume is compatible
	 */
	public static boolean matchesForResume(CheckpointManager.Manifest m, ExMasConfigGroup config,
			Path req, Path tt, Path net, String algoVersion, boolean forkBelowMinDegree) {
		int minDegree = config.getExtensionParentsTopKMinDegree();
		if (forkBelowMinDegree && m.highestDegree < minDegree) {
			// Both sides forkable-knob-free → match iff the non-forkable params agree.
			return matches(m.baseFingerprint, compute(config, req, tt, net, algoVersion, m.highestDegree));
		}
		// Flag off, or checkpoint already at/above minDegree: full hash (today's behaviour).
		return matches(m.fingerprint, compute(config, req, tt, net, algoVersion));
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
