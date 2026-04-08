package org.matsim.contrib.demand_extraction.algorithm.extension;

import java.util.concurrent.ConcurrentLinkedQueue;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/**
 * Stores proven-infeasible origin orderings for sub-sets of requests.
 *
 * <p>After degree-k enumeration, records which origin orderings are infeasible for each
 * sub-set of size 3 (and optionally 4, 5). At degree k+1, checks whether any sub-set's
 * implied origin ordering is known infeasible — if so, the full ordering can be pruned.
 *
 * <p>Coverage: 100% of sub-sets (every degree-7 set has C(7,3)=35 triples with complete
 * degree-3 data). Lookup: O(C(d,k-1)) per candidate, with O(k²) Lehmer computation each.
 *
 * <p>Infeasibility is recorded when ALL destination orderings fail due to absolute travel
 * time violations (Trigger 2) or when origin-phase Check A fires. Distance branch-and-bound
 * failures are NOT recorded (they depend on per-set bestValidDist, not absolute constraints).
 *
 * <p>Thread safety: record via {@link #recordPending} (lock-free queue), then merge via
 * {@link #commit} between degrees. Lookups read from committed maps — safe for concurrent reads.
 *
 * <h3>Ordering → Bit Index (Lehmer Code)</h3>
 * <p>For a sub-set of size k with elements sorted as [a₀, a₁, ..., a_{k-1}], an origin
 * ordering is a permutation. The bit index is the Lehmer code (factorial number system):
 * O(k²) per permutation, trivial for k ≤ 7.
 */
public final class SubSetOrderingFeasibility {

	private static final long HASH_PRIME = 1000003L;
	private static final int[] FACTORIAL = { 1, 1, 2, 6, 24, 120, 720, 5040 };

	/** Maximum sub-set size to track (3 = triples only, 4 = +quads, 5 = +quints). */
	private final int maxSubsetSize;

	// Triples: 3! = 6 orderings → 6 bits, stored in int (bits 0-5)
	private final Long2IntOpenHashMap tripleInfeasibility;

	// Quads: 4! = 24 orderings → 24 bits, stored in int (bits 0-23)
	private final Long2IntOpenHashMap quadInfeasibility;

	// Quints: 5! = 120 orderings → 120 bits, stored in two longs (lo: 0-63, hi: 64-119)
	private final Long2LongOpenHashMap quintInfeasibilityLo;
	private final Long2LongOpenHashMap quintInfeasibilityHi;

	// Thread-safe pending buffer: each entry is [subsetHash, lehmerIndex, subsetSize]
	private final ConcurrentLinkedQueue<long[]> pending = new ConcurrentLinkedQueue<>();

	public SubSetOrderingFeasibility(int maxSubsetSize) {
		if (maxSubsetSize < 3 || maxSubsetSize > 5) {
			throw new IllegalArgumentException("maxSubsetSize must be 3, 4, or 5, got " + maxSubsetSize);
		}
		this.maxSubsetSize = maxSubsetSize;

		this.tripleInfeasibility = new Long2IntOpenHashMap();
		this.tripleInfeasibility.defaultReturnValue(0);

		if (maxSubsetSize >= 4) {
			this.quadInfeasibility = new Long2IntOpenHashMap();
			this.quadInfeasibility.defaultReturnValue(0);
		} else {
			this.quadInfeasibility = null;
		}

		if (maxSubsetSize >= 5) {
			this.quintInfeasibilityLo = new Long2LongOpenHashMap();
			this.quintInfeasibilityLo.defaultReturnValue(0L);
			this.quintInfeasibilityHi = new Long2LongOpenHashMap();
			this.quintInfeasibilityHi.defaultReturnValue(0L);
		} else {
			this.quintInfeasibilityLo = null;
			this.quintInfeasibilityHi = null;
		}
	}

	// ---- Lehmer code ----

	/**
	 * Compute the Lehmer code (factorial number system index) of a permutation.
	 * The permutation is given as ranks: perm[i] is the rank (0-based) of the i-th
	 * element in sorted order.
	 *
	 * @param ranks permutation as rank array, length k
	 * @param k permutation size
	 * @return index in [0, k!)
	 */
	static int lehmerIndex(int[] ranks, int k) {
		int index = 0;
		for (int i = 0; i < k; i++) {
			int count = 0;
			for (int j = i + 1; j < k; j++) {
				if (ranks[j] < ranks[i]) count++;
			}
			index = index * (k - i) + count;
		}
		return index;
	}

	// ---- Hashing ----

	/**
	 * Hash a sorted sub-set of request indices. Elements must be in ascending order.
	 */
	static long hashSorted(int[] elements, int from, int to) {
		long h = 0;
		for (int i = from; i < to; i++) {
			h = h * HASH_PRIME + elements[i];
		}
		return h;
	}

	// ---- Recording ----

	/**
	 * Record an infeasible origin ordering for all sub-sets of size k (3..maxSubsetSize).
	 *
	 * <p>Called from any thread during parallel enumeration. The full origin ordering
	 * {@code perm[0..permLength-1]} (local indices into the request set) is known to be
	 * infeasible. For each sub-set of size k, extract the sub-ordering, compute its
	 * Lehmer index relative to the sorted sub-set, and buffer for commit.
	 *
	 * @param requestIndices the sorted request indices for the current set
	 * @param perm the infeasible origin ordering (local indices 0..n-1)
	 * @param permLength number of elements placed (may be < n for Check A at intermediate depth)
	 */
	public void recordInfeasibleOrdering(int[] requestIndices, int[] perm, int permLength) {
		recordInfeasibleOrdering(requestIndices, perm, 0, permLength);
	}

	/**
	 * Record the EXACT ordering as infeasible at its native degree.
	 * Does NOT decompose into sub-triples — only valid for Trigger 2 where
	 * the full set was tested. Subset infeasibility does not follow from
	 * superset infeasibility (fewer stops = less travel time = might be feasible).
	 *
	 * <p>For degree 3 (permLength=3): records 1 triple. Same as recordInfeasibleOrdering.
	 * For degree 4 (permLength=4): records 1 quad (if maxSubsetSize≥4), no triples.
	 * For degree 5+: records 1 quint (if maxSubsetSize≥5), no triples/quads.
	 */
	/** Record exact ordering from a sub-range perm[fromPos..toPos-1]. */
	public void recordExactOrdering(int[] requestIndices, int[] perm, int fromPos, int toPos) {
		int len = toPos - fromPos;
		if (len < 3 || len > maxSubsetSize) return;

		int[] subPerm = new int[len];
		System.arraycopy(perm, fromPos, subPerm, 0, len);
		recordExactOrdering(requestIndices, subPerm, len);
	}

	public void recordExactOrdering(int[] requestIndices, int[] perm, int permLength) {
		if (permLength < 3 || permLength > maxSubsetSize) return;

		int k = permLength;
		int[] sorted = new int[k];
		int[] ranks = new int[k];

		// Fill with request indices in positional order
		for (int i = 0; i < k; i++) {
			sorted[i] = requestIndices[perm[i]];
		}

		// Compute ranks
		for (int i = 0; i < k; i++) {
			int rank = 0;
			for (int j = 0; j < k; j++) {
				if (sorted[j] < sorted[i]) rank++;
			}
			ranks[i] = rank;
		}

		// Sort for hashing
		int[] sortedCopy = new int[k];
		for (int i = 0; i < k; i++) sortedCopy[ranks[i]] = sorted[i];

		long hash = hashSorted(sortedCopy, 0, k);
		int lehmer = lehmerIndex(ranks, k);

		pending.add(new long[]{ hash, lehmer, k });
	}

	/**
	 * Record an infeasible origin sub-ordering for sub-sets within perm[fromPos..toPos-1].
	 *
	 * <p>Used by Check A when the infeasible portion starts at the victim's position
	 * (not necessarily position 0). The sub-ordering perm[fromPos..toPos-1] is known
	 * infeasible: these origins in this order cause a travel time violation.
	 *
	 * @param requestIndices the sorted request indices for the current set
	 * @param perm the origin ordering (local indices 0..n-1)
	 * @param fromPos start position (inclusive) of the infeasible sub-ordering
	 * @param toPos end position (exclusive) of the infeasible sub-ordering
	 */
	public void recordInfeasibleOrdering(int[] requestIndices, int[] perm, int fromPos, int toPos) {
		int subLen = toPos - fromPos;
		if (subLen < 3) return;

		// Scratch arrays
		int[] sorted = new int[maxSubsetSize];
		int[] ranks = new int[maxSubsetSize];

		for (int k = 3; k <= Math.min(maxSubsetSize, subLen); k++) {
			recordSubsetsRange(requestIndices, perm, fromPos, toPos, k, sorted, ranks);
		}
	}

	/**
	 * Record infeasible sub-orderings of exactly size k from perm[fromPos..toPos-1].
	 */
	private void recordSubsetsRange(int[] requestIndices, int[] perm, int fromPos, int toPos,
									 int k, int[] sorted, int[] ranks) {
		int[] positions = new int[k];
		enumerateSubsetPositions(requestIndices, perm, fromPos, toPos, k, sorted, ranks, positions, 0, fromPos);
	}

	/**
	 * Recursive enumeration of C(rangeLength, k) position combinations within perm[fromPos..toPos-1].
	 */
	private void enumerateSubsetPositions(int[] requestIndices, int[] perm, int fromPos, int toPos,
										   int k, int[] sorted, int[] ranks,
										   int[] positions, int depth, int startPos) {
		if (depth == k) {
			// positions[0..k-1] are the selected positions in perm (in increasing order)
			// The sub-ordering is: perm[positions[0]], perm[positions[1]], ..., perm[positions[k-1]]

			// Fill with request indices in positional order
			for (int i = 0; i < k; i++) {
				sorted[i] = requestIndices[perm[positions[i]]];
			}

			// Compute ranks: for each element, how many of the k elements have smaller request index?
			for (int i = 0; i < k; i++) {
				int rank = 0;
				for (int j = 0; j < k; j++) {
					if (sorted[j] < sorted[i]) rank++;
				}
				ranks[i] = rank;
			}

			// Sort the subset for hashing
			int[] sortedCopy = new int[k];
			for (int i = 0; i < k; i++) sortedCopy[ranks[i]] = sorted[i];

			long hash = hashSorted(sortedCopy, 0, k);
			int lehmer = lehmerIndex(ranks, k);

			pending.add(new long[]{ hash, lehmer, k });
			return;
		}

		for (int p = startPos; p <= toPos - (k - depth); p++) {
			positions[depth] = p;
			enumerateSubsetPositions(requestIndices, perm, fromPos, toPos, k, sorted, ranks,
					positions, depth + 1, p + 1);
		}
	}

	// ---- Commit ----

	/**
	 * Merge pending recordings into committed maps. Call between degrees.
	 * After commit, all recorded infeasibilities are visible to lookups.
	 */
	public void commit() {
		long[] entry;
		while ((entry = pending.poll()) != null) {
			long hash = entry[0];
			int lehmer = (int) entry[1];
			int k = (int) entry[2];

			switch (k) {
				case 3:
					tripleInfeasibility.mergeInt(hash, 1 << lehmer, (a, b) -> a | b);
					break;
				case 4:
					if (quadInfeasibility != null) {
						quadInfeasibility.mergeInt(hash, 1 << lehmer, (a, b) -> a | b);
					}
					break;
				case 5:
					if (quintInfeasibilityLo != null) {
						if (lehmer < 64) {
							quintInfeasibilityLo.mergeLong(hash, 1L << lehmer, (a, b) -> a | b);
						} else {
							quintInfeasibilityHi.mergeLong(hash, 1L << (lehmer - 64), (a, b) -> a | b);
						}
					}
					break;
			}
		}
	}

	// ---- Lookup ----

	// ---- DAG tightening ----

	/**
	 * Bitmask of Lehmer indices where rank {@code r1} appears before rank {@code r2}
	 * in a permutation of 3 elements (3! = 6 orderings, bits 0-5).
	 *
	 * <pre>
	 * Lehmer 0: (0,1,2)  Lehmer 1: (0,2,1)  Lehmer 2: (1,0,2)
	 * Lehmer 3: (1,2,0)  Lehmer 4: (2,0,1)  Lehmer 5: (2,1,0)
	 * </pre>
	 *
	 * PAIR_BEFORE_MASK[r1][r2] = bitmask of orderings where r1 comes before r2.
	 */
	private static final int[][] PAIR_BEFORE_MASK = {
		// r1=0: 0 before 1 = L{0,1,4}=0x13, 0 before 2 = L{0,1,2}=0x07
		{ 0, 0b010011, 0b000111 },
		// r1=1: 1 before 0 = L{2,3,5}=0x2C, 1 before 2 = L{0,2,3}=0x0D
		{ 0b101100, 0, 0b001101 },
		// r1=2: 2 before 0 = L{3,4,5}=0x38, 2 before 1 = L{1,4,5}=0x32
		{ 0b111000, 0b110010, 0 },
	};

	/**
	 * Tighten the origin adjacency DAG using triple infeasibility data.
	 *
	 * <p>For each unconstrained pair (a, b) in the set, checks ALL triples containing
	 * both a and b. If every triple's data shows "a before b" is always infeasible
	 * (all 3 Lehmer indices with a&lt;b are set), adds edge b→a to the DAG.
	 *
	 * <p>This eliminates orderings at the topological sort level — they are never
	 * generated as candidates during enumeration.
	 *
	 * @param adj the origin adjacency matrix (modified in place). adj[i][j]=true means i before j.
	 * @param requestIndices sorted request indices for the current set
	 * @param n number of elements in the set
	 * @return number of edges added (for stats)
	 */
	public int tightenDAG(Boolean[][] adj, int[] requestIndices, int n) {
		if (tripleInfeasibility.isEmpty() || n < 3) return 0;

		int edgesAdded = 0;
		for (int a = 0; a < n; a++) {
			for (int b = a + 1; b < n; b++) {
				if (adj[a][b] != null) continue; // already constrained

				int reqA = requestIndices[a];
				int reqB = requestIndices[b];

				boolean anyAbeforeBfeasible = false;
				boolean anyBbeforeAfeasible = false;

				for (int k = 0; k < n; k++) {
					if (k == a || k == b) continue;
					int reqK = requestIndices[k];

					// Sort {reqA, reqB, reqK} and determine ranks of A and B
					int rankA, rankB;
					int s0, s1, s2; // sorted values
					if (reqA < reqB) {
						if (reqB < reqK) {
							s0 = reqA; s1 = reqB; s2 = reqK; rankA = 0; rankB = 1;
						} else if (reqA < reqK) {
							s0 = reqA; s1 = reqK; s2 = reqB; rankA = 0; rankB = 2;
						} else {
							s0 = reqK; s1 = reqA; s2 = reqB; rankA = 1; rankB = 2;
						}
					} else {
						if (reqA < reqK) {
							s0 = reqB; s1 = reqA; s2 = reqK; rankA = 1; rankB = 0;
						} else if (reqB < reqK) {
							s0 = reqB; s1 = reqK; s2 = reqA; rankA = 2; rankB = 0;
						} else {
							s0 = reqK; s1 = reqB; s2 = reqA; rankA = 2; rankB = 1;
						}
					}

					long hash = (s0 * HASH_PRIME + s1) * HASH_PRIME + s2;
					int bits = tripleInfeasibility.get(hash);
					if (bits == 0) {
						// No infeasibility data → both directions still possible
						anyAbeforeBfeasible = true;
						anyBbeforeAfeasible = true;
						break;
					}

					int maskAbeforeB = PAIR_BEFORE_MASK[rankA][rankB];
					int maskBbeforeA = PAIR_BEFORE_MASK[rankB][rankA];

					// If any ordering with A<B is NOT infeasible → A<B is feasible
					if ((bits & maskAbeforeB) != maskAbeforeB) {
						anyAbeforeBfeasible = true;
					}
					if ((bits & maskBbeforeA) != maskBbeforeA) {
						anyBbeforeAfeasible = true;
					}

					if (anyAbeforeBfeasible && anyBbeforeAfeasible) break;
				}

				if (!anyAbeforeBfeasible && anyBbeforeAfeasible) {
					adj[b][a] = true; adj[a][b] = false; // force b before a
					edgesAdded++;
				} else if (anyAbeforeBfeasible && !anyBbeforeAfeasible) {
					adj[a][b] = true; adj[b][a] = false; // force a before b
					edgesAdded++;
				}
				// If both infeasible → set is fully infeasible (DAG will produce 0 sorts)
				// If neither → no constraint derivable from triples alone
			}
		}
		return edgesAdded;
	}

	// ---- Lookup (per-candidate) ----

	/**
	 * Check if the origin ordering implied by placing candidate c at position {@code depth}
	 * (after perm[0..depth-1]) contains any infeasible triple sub-ordering.
	 *
	 * <p>For each pair (i, j) of already-placed positions (i < j < depth), forms the
	 * triple {perm[i], perm[j], c} and checks if their implied sub-ordering is known
	 * infeasible.
	 *
	 * @param requestIndices sorted request indices for the current set
	 * @param perm current partial origin ordering (local indices)
	 * @param depth number of origins already placed
	 * @param candidate local index of the candidate to place at position depth
	 * @return true if any sub-ordering is known infeasible
	 */
	public boolean isInfeasible(int[] requestIndices, int[] perm, int depth, int candidate) {
		// Check triples: for each pair of placed origins + candidate
		if (checkTriples(requestIndices, perm, depth, candidate)) return true;

		// Check quads: for each triple of placed origins + candidate
		if (maxSubsetSize >= 4 && quadInfeasibility != null && depth >= 3) {
			if (checkQuads(requestIndices, perm, depth, candidate)) return true;
		}

		// Check quints: for each quad of placed origins + candidate
		if (maxSubsetSize >= 5 && quintInfeasibilityLo != null && depth >= 4) {
			if (checkQuints(requestIndices, perm, depth, candidate)) return true;
		}

		return false;
	}

	private boolean checkTriples(int[] requestIndices, int[] perm, int depth, int candidate) {
		if (tripleInfeasibility.isEmpty()) return false;

		int reqC = requestIndices[candidate];
		for (int i = 0; i < depth; i++) {
			int reqI = requestIndices[perm[i]];
			for (int j = i + 1; j < depth; j++) {
				int reqJ = requestIndices[perm[j]];

				// The three elements in positional order: reqI (pos i), reqJ (pos j), reqC (pos depth)
				// Sort to get sub-set key, compute ranks for Lehmer
				long hash;
				int lehmer;

				// Inline sort-3 + rank computation for speed
				if (reqI < reqJ) {
					if (reqJ < reqC) {
						// reqI < reqJ < reqC → positions map to ranks (0,1,2) → perm is identity
						hash = ((reqI * HASH_PRIME + reqJ) * HASH_PRIME + reqC);
						lehmer = 0; // (0,1,2)
					} else if (reqI < reqC) {
						// reqI < reqC < reqJ → ranks (0,2,1)
						hash = ((reqI * HASH_PRIME + reqC) * HASH_PRIME + reqJ);
						lehmer = lehmerOf3(0, 2, 1);
					} else {
						// reqC < reqI < reqJ → ranks (1,2,0)
						hash = ((reqC * HASH_PRIME + reqI) * HASH_PRIME + reqJ);
						lehmer = lehmerOf3(1, 2, 0);
					}
				} else {
					// reqJ < reqI (or equal, but request indices are unique)
					if (reqI < reqC) {
						// reqJ < reqI < reqC → ranks (1,0,2)
						hash = ((reqJ * HASH_PRIME + reqI) * HASH_PRIME + reqC);
						lehmer = lehmerOf3(1, 0, 2);
					} else if (reqJ < reqC) {
						// reqJ < reqC < reqI → ranks (2,0,1)
						hash = ((reqJ * HASH_PRIME + reqC) * HASH_PRIME + reqI);
						lehmer = lehmerOf3(2, 0, 1);
					} else {
						// reqC < reqJ < reqI → ranks (2,1,0)
						hash = ((reqC * HASH_PRIME + reqJ) * HASH_PRIME + reqI);
						lehmer = lehmerOf3(2, 1, 0);
					}
				}

				int bits = tripleInfeasibility.get(hash);
				if ((bits & (1 << lehmer)) != 0) {
					return true;
				}
			}
		}
		return false;
	}

	/** Lehmer index for a permutation of 3 elements. Precomputed for all 6 cases. */
	private static int lehmerOf3(int r0, int r1, int r2) {
		// Lehmer: count inversions at each position
		int c0 = 0; // elements after pos 0 that are smaller than r0
		if (r1 < r0) c0++;
		if (r2 < r0) c0++;
		int c1 = 0;
		if (r2 < r1) c1++;
		return c0 * 2 + c1;
	}

	private boolean checkQuads(int[] requestIndices, int[] perm, int depth, int candidate) {
		if (quadInfeasibility.isEmpty()) return false;

		int reqC = requestIndices[candidate];
		int[] four = new int[4]; // request indices in positional order
		int[] sorted = new int[4];
		int[] ranks = new int[4];

		for (int i = 0; i < depth; i++) {
			for (int j = i + 1; j < depth; j++) {
				for (int k = j + 1; k < depth; k++) {
					four[0] = requestIndices[perm[i]];
					four[1] = requestIndices[perm[j]];
					four[2] = requestIndices[perm[k]];
					four[3] = reqC;

					// Compute ranks
					for (int a = 0; a < 4; a++) {
						int rank = 0;
						for (int b = 0; b < 4; b++) {
							if (four[b] < four[a]) rank++;
						}
						ranks[a] = rank;
						sorted[rank] = four[a];
					}

					long hash = hashSorted(sorted, 0, 4);
					int lehmer = lehmerIndex(ranks, 4);
					int bits = quadInfeasibility.get(hash);
					if ((bits & (1 << lehmer)) != 0) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private boolean checkQuints(int[] requestIndices, int[] perm, int depth, int candidate) {
		if (quintInfeasibilityLo.isEmpty() && quintInfeasibilityHi.isEmpty()) return false;

		int reqC = requestIndices[candidate];
		int[] five = new int[5];
		int[] sorted = new int[5];
		int[] ranks = new int[5];

		for (int i = 0; i < depth; i++) {
			for (int j = i + 1; j < depth; j++) {
				for (int k = j + 1; k < depth; k++) {
					for (int l = k + 1; l < depth; l++) {
						five[0] = requestIndices[perm[i]];
						five[1] = requestIndices[perm[j]];
						five[2] = requestIndices[perm[k]];
						five[3] = requestIndices[perm[l]];
						five[4] = reqC;

						for (int a = 0; a < 5; a++) {
							int rank = 0;
							for (int b = 0; b < 5; b++) {
								if (five[b] < five[a]) rank++;
							}
							ranks[a] = rank;
							sorted[rank] = five[a];
						}

						long hash = hashSorted(sorted, 0, 5);
						int lehmer = lehmerIndex(ranks, 5);
						if (lehmer < 64) {
							long bits = quintInfeasibilityLo.get(hash);
							if ((bits & (1L << lehmer)) != 0) return true;
						} else {
							long bits = quintInfeasibilityHi.get(hash);
							if ((bits & (1L << (lehmer - 64))) != 0) return true;
						}
					}
				}
			}
		}
		return false;
	}

	// ---- Direct lookup for false positive detection ----

	/** Check if a specific triple (by hash) has a specific Lehmer index marked infeasible. */
	public boolean isTripleInfeasible(long hash, int lehmer) {
		int bits = tripleInfeasibility.get(hash);
		return (bits & (1 << lehmer)) != 0;
	}

	// ---- Stats ----

	public int getTripleCount() { return tripleInfeasibility.size(); }

	public int getQuadCount() { return quadInfeasibility != null ? quadInfeasibility.size() : 0; }

	public int getQuintCount() {
		return quintInfeasibilityLo != null ? quintInfeasibilityLo.size() : 0;
	}

	/** Average number of infeasible orderings per triple that has any. */
	public double getAvgInfeasiblePerTriple() {
		if (tripleInfeasibility.isEmpty()) return 0;
		long totalBits = 0;
		for (int v : tripleInfeasibility.values()) {
			totalBits += Integer.bitCount(v);
		}
		return (double) totalBits / tripleInfeasibility.size();
	}

	/** Total number of infeasible ordering bits set across all triples. */
	public long getTotalInfeasibleTripleBits() {
		long total = 0;
		for (int v : tripleInfeasibility.values()) {
			total += Integer.bitCount(v);
		}
		return total;
	}

	public int getMaxSubsetSize() { return maxSubsetSize; }

	public int getPendingCount() { return pending.size(); }
}
