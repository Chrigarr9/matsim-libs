package org.matsim.contrib.demand_extraction.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.io.lowmem.PhaseOneDumpLayout;

/**
 * Lightweight checks for the Phase-1 runner CLI surface: parsing of the
 * {@code --phase1-dump-dir} flag plus dump-root resolution against
 * {@link PhaseOneDumpLayout#SUBDIR}.
 *
 * <p>Every result-affecting ExMAS knob is applied via {@link ExMasConfigOverlay} now
 * (spec D4, 2026-08-19) -- the old {@code applyParsedArgs} mirror this test used to
 * exercise is gone; overlay application is covered by {@link ExMasConfigOverlayTest}.
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
}
