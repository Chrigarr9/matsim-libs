package org.matsim.contrib.demand_extraction.scenarios;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Compares an actual ExMAS rides CSV against a golden CSV produced by main's
 * {@code ExMasKelheimE2ETest}. Used by
 * {@code ExMasReferencePortRegressionTest} (Phase 6.3) to verify the ported
 * algorithm/exmas/ implementation matches main's binary.
 *
 * <p>Compares only ride identity + best ride distance per request set:
 *
 * <ol>
 *   <li>Parse rideIndex,degree,requestIndices,rideDistance from each CSV (by
 *       header name, so column-order differences are tolerated — the fork's
 *       CSV adds an {@code isEducations} column not present in main's).</li>
 *   <li>Group rows by sorted {@code requestIndices} (the canonical request set);
 *       per set, keep the minimum {@code rideDistance} (vanilla ExMAS may emit
 *       multiple orderings per set with different distances).</li>
 *   <li>Per degree: assert Jaccard(actual, golden) = 1.0 over request sets.</li>
 *   <li>Per common set: assert relative distance match within {@code relTol}.</li>
 * </ol>
 */
public final class GoldenAsserter {

	private GoldenAsserter() {}

	public static void assertEquivalent(Path golden, Path actual, double relTol)
			throws IOException {
		assertEquivalent(golden, actual, relTol, Integer.MAX_VALUE);
	}

	public static void assertEquivalent(Path golden, Path actual, double relTol,
			int maxDegreeInclusive)
			throws IOException {
		Map<RequestSet, Double> goldenBest = bestDistancePerSet(golden);
		Map<RequestSet, Double> actualBest = bestDistancePerSet(actual);

		StringBuilder errors = new StringBuilder();

		Map<Integer, Set<RequestSet>> goldenByDegree = groupByDegree(goldenBest.keySet());
		Map<Integer, Set<RequestSet>> actualByDegree = groupByDegree(actualBest.keySet());

		Set<Integer> allDegrees = new TreeSet<>();
		allDegrees.addAll(goldenByDegree.keySet());
		allDegrees.addAll(actualByDegree.keySet());
		allDegrees.removeIf(d -> d > maxDegreeInclusive);

		for (int d : allDegrees) {
			Set<RequestSet> g = goldenByDegree.getOrDefault(d, Set.of());
			Set<RequestSet> a = actualByDegree.getOrDefault(d, Set.of());

			Set<RequestSet> onlyGolden = new TreeSet<>(g);
			onlyGolden.removeAll(a);
			Set<RequestSet> onlyActual = new TreeSet<>(a);
			onlyActual.removeAll(g);

			if (!onlyGolden.isEmpty() || !onlyActual.isEmpty()) {
				errors.append(String.format(
						"Degree %d: %d golden sets, %d actual sets, %d only-golden, %d only-actual%n",
						d, g.size(), a.size(), onlyGolden.size(), onlyActual.size()));
				appendSamples(errors, "    only-golden", onlyGolden);
				appendSamples(errors, "    only-actual", onlyActual);
			}
		}

		// Distance comparison on common sets
		int distMismatches = 0;
		for (RequestSet set : goldenBest.keySet()) {
			if (set.degree > maxDegreeInclusive) continue;
			if (!actualBest.containsKey(set)) continue;
			double gd = goldenBest.get(set);
			double ad = actualBest.get(set);
			double rel = Math.abs(gd - ad) / Math.max(Math.abs(gd), 1e-12);
			if (rel > relTol) {
				if (distMismatches < 10) {
					errors.append(String.format(
							"Distance mismatch on set %s: golden=%.4f actual=%.4f rel=%.6e%n",
							set, gd, ad, rel));
				}
				distMismatches++;
			}
		}
		if (distMismatches >= 10) {
			errors.append(String.format("    ... and %d more distance mismatches%n",
					distMismatches - 10));
		}

		if (errors.length() > 0) {
			throw new AssertionError("Port regression failed:\n" + errors);
		}
	}

	private static void appendSamples(StringBuilder out, String label, Set<RequestSet> sets) {
		if (sets.isEmpty()) return;
		List<RequestSet> sample = sets.stream().limit(5).collect(Collectors.toList());
		out.append(label).append(": ").append(sample);
		if (sets.size() > 5) out.append(" (+").append(sets.size() - 5).append(" more)");
		out.append("\n");
	}

	private static Map<Integer, Set<RequestSet>> groupByDegree(Set<RequestSet> sets) {
		Map<Integer, Set<RequestSet>> out = new TreeMap<>();
		for (RequestSet s : sets) {
			out.computeIfAbsent(s.degree, k -> new TreeSet<>()).add(s);
		}
		return out;
	}

	private static Map<RequestSet, Double> bestDistancePerSet(Path csv) throws IOException {
		Map<RequestSet, Double> best = new HashMap<>();
		try (BufferedReader r = Files.newBufferedReader(csv)) {
			String headerLine = r.readLine();
			if (headerLine == null) {
				throw new IllegalStateException("Empty CSV: " + csv);
			}
			String[] header = headerLine.split(",", -1);
			int degIdx = indexOf(header, "degree");
			int reqIdx = indexOf(header, "requestIndices");
			int distIdx = indexOf(header, "rideDistance");

			String line;
			while ((line = r.readLine()) != null) {
				String[] parts = line.split(",", -1);
				int degree = Integer.parseInt(parts[degIdx]);
				String reqIndicesRaw = stripBrackets(parts[reqIdx]);
				// The CSV writer uses " | " between elements inside an array cell.
				int[] sortedReqs = Arrays.stream(reqIndicesRaw.split("\\s*\\|\\s*"))
						.map(String::trim)
						.filter(s -> !s.isEmpty())
						.mapToInt(Integer::parseInt)
						.sorted()
						.toArray();
				double distance = Double.parseDouble(parts[distIdx]);

				RequestSet key = new RequestSet(degree, sortedReqs);
				best.merge(key, distance, Math::min);
			}
		}
		return best;
	}

	private static int indexOf(String[] header, String name) {
		for (int i = 0; i < header.length; i++) {
			if (header[i].equals(name)) return i;
		}
		throw new IllegalStateException("Column not found: " + name
				+ " in " + Arrays.toString(header));
	}

	/** Strip surrounding [...] or "[...]" if the formatter wrapped the array. */
	private static String stripBrackets(String s) {
		String t = s.trim();
		if (t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1);
		if (t.startsWith("[") && t.endsWith("]")) t = t.substring(1, t.length() - 1);
		return t;
	}

	/** Identity = (degree, sorted request indices). Comparable for stable error messages. */
	private record RequestSet(int degree, int[] sortedRequests)
			implements Comparable<RequestSet> {

		@Override
		public boolean equals(Object o) {
			return o instanceof RequestSet other
					&& degree == other.degree
					&& Arrays.equals(sortedRequests, other.sortedRequests);
		}

		@Override
		public int hashCode() {
			return 31 * degree + Arrays.hashCode(sortedRequests);
		}

		@Override
		public String toString() {
			return "[d=" + degree + " " + Arrays.toString(sortedRequests) + "]";
		}

		@Override
		public int compareTo(RequestSet other) {
			return Comparator.comparingInt((RequestSet s) -> s.degree)
					.thenComparing((s1, s2) -> Arrays.compare(s1.sortedRequests, s2.sortedRequests))
					.compare(this, other);
		}
	}
}
