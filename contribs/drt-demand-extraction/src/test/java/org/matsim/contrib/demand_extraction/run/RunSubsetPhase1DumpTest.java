package org.matsim.contrib.demand_extraction.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for the CLI kept-index sources and arg validation. */
class RunSubsetPhase1DumpTest {

	@Test
	void readsIndexColumnByHeaderNameNotPosition(@TempDir Path tmp) throws IOException {
		// 'index' is NOT the first column — must be located by name.
		Path csv = tmp.resolve("cut.csv");
		Files.writeString(csv, "personId,index,budget\np1,42,1.0\np2,7,2.0\n");
		Set<Integer> idx = RunSubsetPhase1Dump.readIndexColumn(csv);
		assertEquals(Set.of(42, 7), idx);
	}

	@Test
	void readsIndexLinesIgnoringBlanksAndComments(@TempDir Path tmp) throws IOException {
		Path f = tmp.resolve("keep.txt");
		Files.writeString(f, "# header comment\n10\n\n11\n  13 \n");
		Set<Integer> idx = RunSubsetPhase1Dump.readIndexLines(f);
		assertEquals(Set.of(10, 11, 13), idx);
	}

	@Test
	void unionsBothSourcesWhenSupplied(@TempDir Path tmp) throws IOException {
		Path csv = tmp.resolve("cut.csv");
		Files.writeString(csv, "index\n1\n2\n");
		Path lines = tmp.resolve("keep.txt");
		Files.writeString(lines, "2\n3\n");
		RunSubsetPhase1Dump.Args args = new RunSubsetPhase1Dump.Args(
				tmp.resolve("in"), tmp.resolve("out"), csv, lines);
		assertEquals(Set.of(1, 2, 3), RunSubsetPhase1Dump.resolveKeepIndices(args));
	}

	@Test
	void rejectsMissingKeptIndexSource() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> RunSubsetPhase1Dump.parseArgs(new String[] {"--in-dump", "a", "--out-dump", "b"}));
		assertTrue(ex.getMessage().contains("kept-index source"));
	}
}
