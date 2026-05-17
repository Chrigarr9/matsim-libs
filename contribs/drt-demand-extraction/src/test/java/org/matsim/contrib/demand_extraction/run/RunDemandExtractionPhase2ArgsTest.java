package org.matsim.contrib.demand_extraction.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class RunDemandExtractionPhase2ArgsTest {

	@Test
	void parsesAllRequiredFlags() {
		String[] args = {
				"--phase1-dir", "/tmp/dump",
				"--network", "/tmp/net.xml.gz",
				"--travel-times", "/tmp/tt.tsv",
				"--output-dir", "/tmp/out"
		};
		RunDemandExtractionPhase2.Phase2Args parsed = RunDemandExtractionPhase2.parseArgs(args);
		assertEquals(Path.of("/tmp/dump"), parsed.phase1Dir());
		assertEquals(Path.of("/tmp/net.xml.gz"), parsed.networkXml());
		assertEquals(Path.of("/tmp/tt.tsv"), parsed.travelTimesTsv());
		assertEquals(Path.of("/tmp/out"), parsed.outputDir());
	}

	@Test
	void rejectsMissingRequiredFlag() {
		String[] args = {
				"--phase1-dir", "/tmp/dump",
				"--network", "/tmp/net.xml.gz",
				"--travel-times", "/tmp/tt.tsv"
				// --output-dir missing
		};
		assertThrows(IllegalArgumentException.class,
				() -> RunDemandExtractionPhase2.parseArgs(args));
	}
}
