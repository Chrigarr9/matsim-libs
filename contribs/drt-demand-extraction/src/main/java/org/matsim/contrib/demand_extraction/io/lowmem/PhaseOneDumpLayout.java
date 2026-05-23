package org.matsim.contrib.demand_extraction.io.lowmem;

import java.nio.file.Path;

/**
 * On-disk layout for the Phase-1 dump consumed by the Phase-2 runner.
 *
 * <p>Phase 1 writes three files under {@code <root>/}:
 * <ul>
 *   <li>{@link #REQUESTS_CSV} — DRT requests (CSV, additive-only schema; readable by Python too)</li>
 *   <li>{@link #SCORING_CONTEXTS_BIN} — per-request scalars and per-type activity table (binary)</li>
 *   <li>{@link #META_JSON} — config knobs Phase 2 must re-apply (small JSON)</li>
 * </ul>
 *
 * <p>The single-process flow also writes its own {@code <runId>.drt_requests.csv} under
 * {@code <outputDir>/drt_demand/}. That artifact is preserved by Phase 1 alongside this
 * dump; the dump is an additional, Phase-2-only payload.
 */
public final class PhaseOneDumpLayout {

	public static final String SUBDIR = "phase1_dump";
	public static final String REQUESTS_CSV = "drt_requests_phase1.csv";
	public static final String SCORING_CONTEXTS_BIN = "scoring_contexts.bin";
	public static final String META_JSON = "phase1_meta.json";
	public static final String CONFIG_XML = "phase1_config.xml";

	public static final int SCORING_CONTEXTS_MAGIC = 0xDE5C0DE1;
	/**
	 * On-disk version of {@code scoring_contexts.bin}.
	 *
	 * <p>v1 (legacy): per-request 35-byte row (index + 2 byte actType + 4 doubles).
	 * <p>v2 (current): v1 + two additional f64 fields per row — {@code maxWalkDistance}
	 * and {@code maxWaitTime} — so the dump fully captures budget-derived caps for
	 * HyperPool / stop-based replay in Phase 2.
	 *
	 * <p>The reader honours both. v1 dumps surface {@code maxWalkDistance=0},
	 * {@code maxWaitTime=0}; Phase 2 must reject a v1 dump if HyperPool or stop-based
	 * generation is enabled, because the walks would silently be wrong.
	 */
	public static final int SCORING_CONTEXTS_VERSION = 2;

	private final Path root;

	public PhaseOneDumpLayout(Path root) {
		this.root = root;
	}

	public Path root() { return root; }
	public Path requestsCsv() { return root.resolve(REQUESTS_CSV); }
	public Path scoringContextsBin() { return root.resolve(SCORING_CONTEXTS_BIN); }
	public Path metaJson() { return root.resolve(META_JSON); }
	public Path configXml() { return root.resolve(CONFIG_XML); }
}
