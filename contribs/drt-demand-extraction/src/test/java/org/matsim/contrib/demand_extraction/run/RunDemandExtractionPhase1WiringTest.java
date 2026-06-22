package org.matsim.contrib.demand_extraction.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.io.lowmem.PhaseOneDumpLayout;

/**
 * Lightweight checks for the Phase-1 runner CLI surface: parsing of the new
 * {@code --phase1-dump-dir} flag plus dump-root resolution against
 * {@link PhaseOneDumpLayout#SUBDIR}.
 *
 * <p>The Guice override binding (DemandExtractionListener ↔ Phase1DumpListener)
 * is exercised end-to-end by the Phase-1 Lyon smoke and the Task-11 hard gate;
 * unit-level Guice introspection adds little because Guice eagerly validates the
 * listener's full {@code @Inject} dep graph and would require the entire
 * MATSim/eqasim Controler to stand up.
 */
class RunDemandExtractionPhase1WiringTest {

	@Test
	void parsePhase1DumpDirExtractsValue() {
		String[] args = {
				"--sample", "1",
				"--phase1-dump-dir", "/tmp/foo",
				"--scenario-dir", "/tmp/x"
		};
		assertEquals("/tmp/foo", RunDemandExtractionPhase1.parsePhase1DumpDir(args));
	}

	@Test
	void parsePhase1DumpDirReturnsNullWhenAbsent() {
		String[] args = {"--sample", "1", "--scenario-dir", "/tmp/x"};
		assertNull(RunDemandExtractionPhase1.parsePhase1DumpDir(args));
	}

	@Test
	void resolveDumpRootPrefersExplicitFlag() {
		Path explicit = Path.of("/tmp/explicit/dump");
		Path resolved = RunDemandExtractionPhase1.resolveDumpRoot(explicit.toString(),
				Path.of("/tmp/output"));
		assertEquals(explicit, resolved);
	}

	@Test
	void resolveDumpRootFallsBackToOutputSubdir() {
		Path outDir = Path.of("/tmp/output");
		Path resolved = RunDemandExtractionPhase1.resolveDumpRoot(null, outDir);
		assertEquals(outDir.resolve(PhaseOneDumpLayout.SUBDIR), resolved);
	}

	// ------------------------------------------------------------------ //
	// Gap 1: applyParsedArgs mirror — HyperPool / stop-based knobs         //
	// ------------------------------------------------------------------ //

	@Test
	void applyParsedArgsMirrorsEnableStopBased() {
		ExMasConfigGroup cfg = new ExMasConfigGroup();
		RunLyonEqasimDemandExtraction.ParsedArgs p = buildArgs(b -> b.enableStopBased = true);
		RunDemandExtractionPhase1.applyParsedArgs(cfg, p);
		assertTrue(cfg.isEnableStopBased(), "enableStopBased should be mirrored");
	}

	@Test
	void applyParsedArgsMirrorsEnableHyperPooling() {
		ExMasConfigGroup cfg = new ExMasConfigGroup();
		RunLyonEqasimDemandExtraction.ParsedArgs p = buildArgs(b -> b.enableHyperPooling = true);
		RunDemandExtractionPhase1.applyParsedArgs(cfg, p);
		assertTrue(cfg.isEnableHyperPooling(), "enableHyperPooling should be mirrored");
	}

	@Test
	void applyParsedArgsMirrorsEnableBudgetAwareConstraints() {
		ExMasConfigGroup cfg = new ExMasConfigGroup();
		RunLyonEqasimDemandExtraction.ParsedArgs p = buildArgs(b -> b.enableBudgetAwareConstraints = true);
		RunDemandExtractionPhase1.applyParsedArgs(cfg, p);
		assertTrue(cfg.isEnableBudgetAwareConstraints(), "enableBudgetAwareConstraints should be mirrored");
	}

	@Test
	void applyParsedArgsMirrorsMaxWalkDistanceMeters() {
		ExMasConfigGroup cfg = new ExMasConfigGroup();
		RunLyonEqasimDemandExtraction.ParsedArgs p = buildArgs(b -> b.maxWalkDistanceMeters = 750.0);
		RunDemandExtractionPhase1.applyParsedArgs(cfg, p);
		assertEquals(750.0, cfg.getMaxWalkDistanceMeters(), 1e-12);
	}

	@Test
	void applyParsedArgsMirrorsMaxOrderingNodes() {
		ExMasConfigGroup cfg = new ExMasConfigGroup();
		RunLyonEqasimDemandExtraction.ParsedArgs p = buildArgs(b -> b.maxOrderingNodes = 5000L);
		RunDemandExtractionPhase1.applyParsedArgs(cfg, p);
		assertEquals(5000L, cfg.getMaxOrderingNodesAfterFirstValid());
	}

	@Test
	void applyParsedArgsDoesNotSetStopBasedWhenFalse() {
		ExMasConfigGroup cfg = new ExMasConfigGroup();
		cfg.setEnableStopBased(false);
		RunLyonEqasimDemandExtraction.ParsedArgs p = buildArgs(b -> {}); // all defaults
		RunDemandExtractionPhase1.applyParsedArgs(cfg, p);
		// should stay false
		org.junit.jupiter.api.Assertions.assertFalse(cfg.isEnableStopBased());
	}

	@Test
	void applyParsedArgsMirrorsCheckpointForkBelowMinDegree() {
		ExMasConfigGroup cfg = new ExMasConfigGroup();
		RunLyonEqasimDemandExtraction.ParsedArgs p = buildArgs(b -> b.checkpointForkBelowMinDegree = true);
		RunDemandExtractionPhase1.applyParsedArgs(cfg, p);
		assertTrue(cfg.isCheckpointForkBelowMinDegree(), "checkpointForkBelowMinDegree should be mirrored");
	}

	@Test
	void applyParsedArgsDoesNotSetCheckpointForkWhenFalse() {
		ExMasConfigGroup cfg = new ExMasConfigGroup();
		RunLyonEqasimDemandExtraction.ParsedArgs p = buildArgs(b -> {}); // all defaults
		RunDemandExtractionPhase1.applyParsedArgs(cfg, p);
		org.junit.jupiter.api.Assertions.assertFalse(cfg.isCheckpointForkBelowMinDegree(),
				"checkpointForkBelowMinDegree should stay false when flag is absent");
	}

	// Helper to build a ParsedArgs with a customizer lambda without requiring
	// all 26 constructor parameters to be repeated in each test.
	private static RunLyonEqasimDemandExtraction.ParsedArgs buildArgs(
			java.util.function.Consumer<ArgsBuilder> customizer) {
		ArgsBuilder b = new ArgsBuilder();
		customizer.accept(b);
		return new RunLyonEqasimDemandExtraction.ParsedArgs(
				b.sample, b.scenarioDir, b.prefix, b.travelTimesPath, b.outputDir,
				b.searchHorizon, b.maxDetourFactor, b.minDrtCostPerKm, b.pruningCoverageK,
				b.algorithm, b.tripFilterRadiusKm, b.noExclusionZone, b.noPredecessors,
				b.noShapley, b.maxPoolingDegree, b.predecessorsFilterTime,
				b.enableStopBased, b.enableHyperPooling, b.enableBudgetAwareConstraints,
				b.maxWalkDistanceMeters, b.hubSetGeoJsonPath, b.hubTransferBufferSeconds,
				b.requestClassificationsPath, b.fleetSide, b.metropolePolygonPath,
				b.maxOrderingNodes,
				b.extensionParentsTopK, b.extensionParentsTopKMinDegree,
				b.extensionParentsTopKMetric, b.extensionParentsSelectionRule,
				b.extensionParentsMmrLambda, b.extensionParentsTier2NodeCap,
				b.checkpointForkBelowMinDegree,
				b.expandConnectingBothSides, b.maxDetourFactorByClass);
	}

	/** Mutable builder so tests can override individual fields in a lambda. */
	private static class ArgsBuilder {
		int sample = 1;
		String scenarioDir = "/tmp/s";
		String prefix = "p_";
		String travelTimesPath = "/tmp/tt.tsv";
		String outputDir = null;
		double searchHorizon = Double.NaN;
		double maxDetourFactor = Double.NaN;
		double minDrtCostPerKm = Double.NaN;
		int pruningCoverageK = -1;
		ExMasConfigGroup.Algorithm algorithm = ExMasConfigGroup.Algorithm.BAMAS;
		double tripFilterRadiusKm = Double.NaN;
		boolean noExclusionZone = false;
		boolean noPredecessors = false;
		boolean noShapley = false;
		int maxPoolingDegree = -1;
		double predecessorsFilterTime = Double.NaN;
		boolean enableStopBased = false;
		boolean enableHyperPooling = false;
		boolean enableBudgetAwareConstraints = false;
		double maxWalkDistanceMeters = Double.NaN;
		String hubSetGeoJsonPath = null;
		double hubTransferBufferSeconds = Double.NaN;
		String requestClassificationsPath = null;
		ExMasConfigGroup.FleetSide fleetSide = null;
		String metropolePolygonPath = null;
		long maxOrderingNodes = -1;
		int extensionParentsTopK = 0;
		int extensionParentsTopKMinDegree = 4;
		ExMasConfigGroup.PruningQualityMetric extensionParentsTopKMetric =
				ExMasConfigGroup.PruningQualityMetric.ABS_SAVINGS;
		ExMasConfigGroup.ExtensionParentsSelectionRule extensionParentsSelectionRule =
				ExMasConfigGroup.ExtensionParentsSelectionRule.TOP_K;
		double extensionParentsMmrLambda = 0.0;
		long extensionParentsTier2NodeCap = 0L;
		boolean checkpointForkBelowMinDegree = false;
		boolean expandConnectingBothSides = false;
		java.util.Map<String, Double> maxDetourFactorByClass = new java.util.HashMap<>();
	}

}
