package org.matsim.contrib.demand_extraction.scenarios;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Directional loss report between two ExMAS rides CSVs: R1 (vanilla ExMAS
 * reference, no pruning) and R2 (BAMAS, no pruning). Unlike
 * {@link GoldenAsserter}, this does NOT assert equivalence — it measures, per
 * degree, how many ride sets are present in R1 but absent from R2
 * ("only-in-R1" = the closure loss) and vice-versa ("only-in-R2").
 *
 * <p>Why a loss report and not an equivalence assertion: BAMAS deliberately
 * keeps the {@code DegreeGraph} downward-closure prune (a degree-(k+1) set is
 * generated only when all k-subfaces are feasible). ExMAS reachability is NOT
 * downward-closed — a valid (k+1)-ride can have an infeasible k-subface — so
 * BAMAS systematically under-generates relative to vanilla ExMAS. That is a
 * known, accepted methodology limitation (tractability vs the combinatorial
 * explosion of pairwise candidate generation at every degree), documented at
 * {@code DegreeGraph.findExtensions} and
 * {@code .project-memory/r1r2-parity-degreegraph-downward-closure-2026-06-16.md}.
 * This report quantifies the loss on a realistic scenario.
 *
 * <p>Ride identity = (degree, sorted requestIndices). Multiple orderings of the
 * same request set collapse to one set (matching {@link GoldenAsserter}).
 */
public final class RideSetLossReport {

	/** Per-degree comparison row. */
	public record DegreeRow(int degree, int r1Count, int r2Count, int onlyR1, int onlyR2) {
		/** Fraction of R1 sets at this degree that BAMAS fails to generate (closure loss). */
		public double lossPct() {
			return r1Count == 0 ? 0.0 : 100.0 * onlyR1 / r1Count;
		}
	}

	private final List<DegreeRow> rows;
	private final List<String> onlyR1Samples;
	private final List<String> onlyR2Samples;

	private RideSetLossReport(List<DegreeRow> rows, List<String> onlyR1Samples,
			List<String> onlyR2Samples) {
		this.rows = rows;
		this.onlyR1Samples = onlyR1Samples;
		this.onlyR2Samples = onlyR2Samples;
	}

	public static RideSetLossReport compare(Path r1Rides, Path r2Rides) throws IOException {
		Map<Integer, Set<RequestSet>> r1 = groupByDegree(parseSets(r1Rides));
		Map<Integer, Set<RequestSet>> r2 = groupByDegree(parseSets(r2Rides));

		Set<Integer> degrees = new TreeSet<>();
		degrees.addAll(r1.keySet());
		degrees.addAll(r2.keySet());

		List<DegreeRow> rows = new ArrayList<>();
		List<String> onlyR1Samples = new ArrayList<>();
		List<String> onlyR2Samples = new ArrayList<>();
		for (int d : degrees) {
			Set<RequestSet> a = r1.getOrDefault(d, Set.of());
			Set<RequestSet> b = r2.getOrDefault(d, Set.of());

			Set<RequestSet> onlyR1 = new TreeSet<>(a);
			onlyR1.removeAll(b);
			Set<RequestSet> onlyR2 = new TreeSet<>(b);
			onlyR2.removeAll(a);

			rows.add(new DegreeRow(d, a.size(), b.size(), onlyR1.size(), onlyR2.size()));
			collectSamples(onlyR1Samples, d, "R1-only", onlyR1);
			collectSamples(onlyR2Samples, d, "R2-only", onlyR2);
		}
		return new RideSetLossReport(rows, onlyR1Samples, onlyR2Samples);
	}

	public List<DegreeRow> rows() {
		return rows;
	}

	public int totalR1() {
		return rows.stream().mapToInt(DegreeRow::r1Count).sum();
	}

	public int totalR2() {
		return rows.stream().mapToInt(DegreeRow::r2Count).sum();
	}

	/** Total ride sets BAMAS loses relative to vanilla ExMAS (downward-closure loss). */
	public int totalOnlyR1() {
		return rows.stream().mapToInt(DegreeRow::onlyR1).sum();
	}

	/**
	 * Total ride sets BAMAS finds that vanilla ExMAS does NOT. Expected 0 with
	 * closure ON (BAMAS ⊆ ExMAS); a non-zero value means BAMAS's fuller ordering
	 * enumeration surfaced a set the reference's restricted pickup-last insertion
	 * frame misses — a separate finding, not a closure-loss.
	 */
	public int totalOnlyR2() {
		return rows.stream().mapToInt(DegreeRow::onlyR2).sum();
	}

	/** Overall closure loss as a percentage of all R1 ride sets. */
	public double overallLossPct() {
		int t = totalR1();
		return t == 0 ? 0.0 : 100.0 * totalOnlyR1() / t;
	}

	/** Closure loss restricted to degree >= minDegree, as a percentage of R1 sets at those degrees. */
	public double lossPctAtDegreeAtLeast(int minDegree) {
		int r1 = rows.stream().filter(r -> r.degree() >= minDegree).mapToInt(DegreeRow::r1Count).sum();
		int loss = rows.stream().filter(r -> r.degree() >= minDegree).mapToInt(DegreeRow::onlyR1).sum();
		return r1 == 0 ? 0.0 : 100.0 * loss / r1;
	}

	/** Human-readable table for test logs / CI output. */
	public String format() {
		StringBuilder sb = new StringBuilder();
		sb.append("ExMAS R1 (vanilla, no-prune) vs BAMAS R2 (no-prune) ride-set loss report\n");
		sb.append(String.format("  %-7s %8s %8s %10s %10s %9s%n",
				"degree", "R1", "R2", "R1-only", "R2-only", "loss%"));
		for (DegreeRow r : rows) {
			sb.append(String.format("  %-7d %8d %8d %10d %10d %8.2f%%%n",
					r.degree(), r.r1Count(), r.r2Count(), r.onlyR1(), r.onlyR2(), r.lossPct()));
		}
		sb.append(String.format("  TOTAL   %8d %8d %10d %10d %8.2f%%%n",
				totalR1(), totalR2(), totalOnlyR1(), totalOnlyR2(), overallLossPct()));
		sb.append(String.format("  loss at degree>=4: %.2f%%%n", lossPctAtDegreeAtLeast(4)));
		if (!onlyR1Samples.isEmpty()) {
			sb.append("  sample R1-only (BAMAS-lost) sets: ")
					.append(String.join(", ", onlyR1Samples)).append("\n");
		}
		if (!onlyR2Samples.isEmpty()) {
			sb.append("  sample R2-only (BAMAS-extra) sets: ")
					.append(String.join(", ", onlyR2Samples)).append("\n");
		}
		return sb.toString();
	}

	private static void collectSamples(List<String> out, int degree, String label,
			Set<RequestSet> sets) {
		int taken = 0;
		for (RequestSet s : sets) {
			if (taken == 3) break;
			out.add(label + " " + s);
			taken++;
		}
	}

	private static Map<Integer, Set<RequestSet>> groupByDegree(Set<RequestSet> sets) {
		Map<Integer, Set<RequestSet>> out = new TreeMap<>();
		for (RequestSet s : sets) {
			out.computeIfAbsent(s.degree(), k -> new TreeSet<>()).add(s);
		}
		return out;
	}

	/** Parse a rides CSV into the distinct (degree, sorted requestIndices) sets. */
	private static Set<RequestSet> parseSets(Path csv) throws IOException {
		Set<RequestSet> sets = new HashSet<>();
		try (BufferedReader r = Files.newBufferedReader(csv)) {
			String headerLine = r.readLine();
			if (headerLine == null) {
				throw new IllegalStateException("Empty CSV: " + csv);
			}
			String[] header = headerLine.split(",", -1);
			int degIdx = indexOf(header, "degree");
			int reqIdx = indexOf(header, "requestIndices");

			String line;
			while ((line = r.readLine()) != null) {
				String[] parts = line.split(",", -1);
				int degree = Integer.parseInt(parts[degIdx].trim());
				String reqIndicesRaw = stripBrackets(parts[reqIdx]);
				int[] sortedReqs = Arrays.stream(reqIndicesRaw.split("\\s*\\|\\s*"))
						.map(String::trim)
						.filter(s -> !s.isEmpty())
						.mapToInt(Integer::parseInt)
						.sorted()
						.toArray();
				sets.add(new RequestSet(degree, sortedReqs));
			}
		}
		return sets;
	}

	private static int indexOf(String[] header, String name) {
		for (int i = 0; i < header.length; i++) {
			if (header[i].equals(name)) return i;
		}
		throw new IllegalStateException("Column not found: " + name
				+ " in " + Arrays.toString(header));
	}

	private static String stripBrackets(String s) {
		String t = s.trim();
		if (t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1);
		if (t.startsWith("[") && t.endsWith("]")) t = t.substring(1, t.length() - 1);
		return t;
	}

	/** Identity = (degree, sorted request indices). */
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
			if (degree != other.degree) return Integer.compare(degree, other.degree);
			return Arrays.compare(sortedRequests, other.sortedRequests);
		}
	}
}
