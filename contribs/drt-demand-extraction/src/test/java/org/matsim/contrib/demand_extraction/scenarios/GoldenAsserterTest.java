package org.matsim.contrib.demand_extraction.scenarios;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoldenAsserterTest {

	@TempDir
	Path tempDir;

	@Test
	void defaultAssertEquivalentStillFailsOnUnexpectedHigherDegrees() throws IOException {
		Path golden = writeCsv("golden.csv",
				"degree,requestIndices,rideDistance",
				"1,[1],10.0",
				"2,[1 | 2],20.0");
		Path actual = writeCsv("actual.csv",
				"degree,requestIndices,rideDistance",
				"1,[1],10.0",
				"2,[1 | 2],20.0",
				"3,[1 | 2 | 3],30.0");

		assertThrows(AssertionError.class,
				() -> GoldenAsserter.assertEquivalent(golden, actual, 1e-9));
	}

	@Test
	void boundedAssertEquivalentIgnoresHigherActualDegrees() throws Exception {
		Path golden = writeCsv("golden.csv",
				"degree,requestIndices,rideDistance",
				"1,[1],10.0",
				"2,[1 | 2],20.0");
		Path actual = writeCsv("actual.csv",
				"degree,requestIndices,rideDistance",
				"1,[1],10.0",
				"2,[1 | 2],20.0",
				"3,[1 | 2 | 3],30.0");

		Method boundedComparison;
		try {
			boundedComparison = GoldenAsserter.class.getMethod(
					"assertEquivalent", Path.class, Path.class, double.class, int.class);
		} catch (NoSuchMethodException e) {
			fail("Expected GoldenAsserter max-degree overload is missing", e);
			return;
		}

		assertDoesNotThrow(() -> invokeBoundedComparison(boundedComparison, golden, actual, 1e-9, 2));
	}

	private Path writeCsv(String fileName, String... lines) throws IOException {
		Path csv = tempDir.resolve(fileName);
		Files.write(csv, List.of(lines));
		return csv;
	}

	private static void invokeBoundedComparison(Method method, Path golden, Path actual,
			double relTol, int maxDegreeInclusive) throws Throwable {
		try {
			method.invoke(null, golden, actual, relTol, maxDegreeInclusive);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}
}