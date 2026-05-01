package org.matsim.contrib.demand_extraction.algorithm.profiling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceProgressCheckpointWriterTest {

	@TempDir
	Path tempDir;

	@Test
	void writesHeaderOnceAndAppendsCheckpointRows() throws Exception {
		Path csv = tempDir.resolve("r1-progress.csv");

		try (ReferenceProgressCheckpointWriter writer = new ReferenceProgressCheckpointWriter(csv)) {
			writer.append(new ReferenceProgressCheckpoint(
					"r1",
					5,
					"running",
					"checkpoint",
					32,
					512,
					2048,
					2048,
					1.25,
					2.50,
					64.0,
					120_000,
					0,
					"power_of_two"));
			writer.append(new ReferenceProgressCheckpoint(
					"r1",
					5,
					"oom",
					"terminal",
					64,
					512,
					4096,
					4096,
					1.50,
					2.50,
					64.0,
					240_000,
					0,
					"java.lang.OutOfMemoryError"));
		}

		assertTrue(Files.exists(csv));

		List<String> lines = Files.readAllLines(csv);
		assertEquals(3, lines.size());
		assertEquals(
				"run,degree,status,sample_kind,sets_processed,sets_total,rides_retained,candidates_added,heap_used_gb,heap_committed_gb,heap_max_gb,elapsed_ms,gc_ms,note",
				lines.get(0));
		assertEquals(
				"r1,5,running,checkpoint,32,512,2048,2048,1.250,2.500,64.000,120000,0,power_of_two",
				lines.get(1));
			assertEquals(
					"r1,5,oom,terminal,64,512,4096,4096,1.500,2.500,64.000,240000,0,java.lang.OutOfMemoryError",
					lines.get(2));
	}
}