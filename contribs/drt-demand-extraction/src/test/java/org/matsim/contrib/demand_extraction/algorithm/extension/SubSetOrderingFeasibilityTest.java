package org.matsim.contrib.demand_extraction.algorithm.extension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SubSetOrderingFeasibilityTest {

	// ---- Lehmer index tests ----

	@Test
	void lehmerIndex_identity() {
		// (0,1,2) → Lehmer 0
		assertEquals(0, SubSetOrderingFeasibility.lehmerIndex(new int[]{0, 1, 2}, 3));
	}

	@Test
	void lehmerIndex_reversal() {
		// (2,1,0) → Lehmer 5 (last of 3! = 6)
		assertEquals(5, SubSetOrderingFeasibility.lehmerIndex(new int[]{2, 1, 0}, 3));
	}

	@Test
	void lehmerIndex_allPermutationsOf3() {
		// All 6 permutations of (0,1,2) should map to unique indices 0-5
		int[][] perms = {
			{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}
		};
		boolean[] seen = new boolean[6];
		for (int[] p : perms) {
			int idx = SubSetOrderingFeasibility.lehmerIndex(p, 3);
			assertTrue(idx >= 0 && idx < 6, "Lehmer index out of range: " + idx);
			assertFalse(seen[idx], "Duplicate Lehmer index: " + idx);
			seen[idx] = true;
		}
	}

	@Test
	void lehmerIndex_size4() {
		// (0,1,2,3) → 0, (3,2,1,0) → 23
		assertEquals(0, SubSetOrderingFeasibility.lehmerIndex(new int[]{0, 1, 2, 3}, 4));
		assertEquals(23, SubSetOrderingFeasibility.lehmerIndex(new int[]{3, 2, 1, 0}, 4));
	}

	@Test
	void lehmerIndex_allPermutationsOf4_unique() {
		boolean[] seen = new boolean[24];
		int[] perm = new int[4];
		int count = 0;
		for (int a = 0; a < 4; a++)
			for (int b = 0; b < 4; b++) {
				if (b == a) continue;
				for (int c = 0; c < 4; c++) {
					if (c == a || c == b) continue;
					for (int d = 0; d < 4; d++) {
						if (d == a || d == b || d == c) continue;
						perm[0] = a; perm[1] = b; perm[2] = c; perm[3] = d;
						int idx = SubSetOrderingFeasibility.lehmerIndex(perm, 4);
						assertTrue(idx >= 0 && idx < 24);
						assertFalse(seen[idx], "Duplicate index " + idx);
						seen[idx] = true;
						count++;
					}
				}
			}
		assertEquals(24, count);
	}

	// ---- Hash tests ----

	@Test
	void hashSorted_deterministic() {
		long h1 = SubSetOrderingFeasibility.hashSorted(new int[]{10, 20, 30}, 0, 3);
		long h2 = SubSetOrderingFeasibility.hashSorted(new int[]{10, 20, 30}, 0, 3);
		assertEquals(h1, h2);
	}

	@Test
	void hashSorted_orderMatters() {
		long h1 = SubSetOrderingFeasibility.hashSorted(new int[]{10, 20, 30}, 0, 3);
		long h2 = SubSetOrderingFeasibility.hashSorted(new int[]{10, 30, 20}, 0, 3);
		assertNotEquals(h1, h2, "Different orderings should hash differently");
	}

	// ---- Record + Commit + Lookup integration ----

	@Test
	void recordAndLookup_singleTriple() {
		SubSetOrderingFeasibility sf = new SubSetOrderingFeasibility(3);

		// Request indices: {100, 200, 300}
		// Infeasible ordering: perm = [0, 1, 2] → request order (100, 200, 300)
		int[] requestIndices = {100, 200, 300};
		int[] perm = {0, 1, 2};
		sf.recordInfeasibleOrdering(requestIndices, perm, 3);
		sf.commit();

		assertEquals(1, sf.getTripleCount());

		// Lookup: at depth 2, placed perm[0]=0, perm[1]=1, candidate=2
		// This is the same ordering → should be infeasible
		int[] partialPerm = {0, 1};
		assertTrue(sf.isInfeasible(requestIndices, partialPerm, 2, 2));
	}

	@Test
	void recordAndLookup_differentOrderingNotInfeasible() {
		SubSetOrderingFeasibility sf = new SubSetOrderingFeasibility(3);

		// Record ordering (100, 200, 300) = perm (0,1,2) as infeasible
		int[] requestIndices = {100, 200, 300};
		sf.recordInfeasibleOrdering(requestIndices, new int[]{0, 1, 2}, 3);
		sf.commit();

		// Lookup the reverse ordering: perm[0]=2, perm[1]=1, candidate=0
		// → request order (300, 200, 100) → different Lehmer index
		int[] partialPerm = {2, 1};
		assertFalse(sf.isInfeasible(requestIndices, partialPerm, 2, 0));
	}

	@Test
	void recordAndLookup_transferToHigherDegree() {
		SubSetOrderingFeasibility sf = new SubSetOrderingFeasibility(3);

		// Record at degree 3: ordering (100, 200, 300) infeasible
		int[] deg3Requests = {100, 200, 300};
		sf.recordInfeasibleOrdering(deg3Requests, new int[]{0, 1, 2}, 3);
		sf.commit();

		// At degree 4: set {50, 100, 200, 300} = requestIndices [50, 100, 200, 300]
		// Local indices: 50→0, 100→1, 200→2, 300→3
		// Origin ordering so far: perm[0]=0(50), perm[1]=1(100), perm[2]=2(200), candidate=3(300)
		// The triple {100, 200, 300} with order (100→200→300) should still be infeasible
		int[] deg4Requests = {50, 100, 200, 300};
		int[] partialPerm = {0, 1, 2}; // 50, 100, 200 placed
		// candidate=3 (300). Triple check: {perm[1], perm[2], candidate} = {100, 200, 300}
		// in order (100, 200, 300) → same infeasible ordering → should detect
		assertTrue(sf.isInfeasible(deg4Requests, partialPerm, 3, 3));
	}

	@Test
	void recordAndLookup_partialRange_checkA() {
		SubSetOrderingFeasibility sf = new SubSetOrderingFeasibility(3);

		// Check A at depth 4 for degree 5: victim at position 1, current depth 4
		// Infeasible sub-ordering: perm[1..3] = local indices [2, 3, 4]
		// Request indices: {10, 20, 30, 40, 50}
		int[] requestIndices = {10, 20, 30, 40, 50};
		int[] perm = {0, 2, 3, 4, 1}; // full perm (only [1..3] matters)
		sf.recordInfeasibleOrdering(requestIndices, perm, 1, 4); // fromPos=1, toPos=4
		sf.commit();

		assertEquals(1, sf.getTripleCount());

		// The recorded triple is {30, 40, 50} with ordering (30, 40, 50) → Lehmer 0
		// Lookup in a different set containing {30, 40, 50}: should find it
		int[] otherRequests = {5, 30, 40, 50};
		// perm so far: [0(5), 1(30), 2(40)], candidate = 3(50)
		int[] partialPerm = {0, 1, 2};
		assertTrue(sf.isInfeasible(otherRequests, partialPerm, 3, 3));
	}

	@Test
	void noFalsePositivesForUnrecorded() {
		SubSetOrderingFeasibility sf = new SubSetOrderingFeasibility(3);

		// Record nothing, lookup should always be false
		int[] requestIndices = {10, 20, 30, 40};
		int[] perm = {0, 1};
		assertFalse(sf.isInfeasible(requestIndices, perm, 2, 2));
		assertFalse(sf.isInfeasible(requestIndices, perm, 2, 3));
	}

	@Test
	void multipleOrderingsPerTriple() {
		SubSetOrderingFeasibility sf = new SubSetOrderingFeasibility(3);

		int[] requestIndices = {10, 20, 30};

		// Record two different orderings as infeasible
		sf.recordInfeasibleOrdering(requestIndices, new int[]{0, 1, 2}, 3); // (10,20,30)
		sf.recordInfeasibleOrdering(requestIndices, new int[]{0, 2, 1}, 3); // (10,30,20)
		sf.commit();

		assertEquals(1, sf.getTripleCount()); // same triple, different bits

		// Both orderings should be infeasible
		assertTrue(sf.isInfeasible(requestIndices, new int[]{0, 1}, 2, 2));  // (10,20,30)
		assertTrue(sf.isInfeasible(requestIndices, new int[]{0, 2}, 2, 1));  // (10,30,20)
		// But (20,10,30) should not be
		assertFalse(sf.isInfeasible(requestIndices, new int[]{1, 0}, 2, 2)); // (20,10,30)
	}

	@Test
	void depth1_noLookup() {
		SubSetOrderingFeasibility sf = new SubSetOrderingFeasibility(3);
		int[] requestIndices = {10, 20, 30};
		sf.recordInfeasibleOrdering(requestIndices, new int[]{0, 1, 2}, 3);
		sf.commit();

		// At depth 1, only 1 origin placed → can't form a triple → no lookup
		assertFalse(sf.isInfeasible(requestIndices, new int[]{0}, 1, 1));
	}

	// ---- DAG tightening tests ----

	@Test
	void tightenDAG_forcesDirection() {
		SubSetOrderingFeasibility sf = new SubSetOrderingFeasibility(3);

		// Set {A=10, B=20, C=30, D=40}
		// Record ALL orderings with A before B as infeasible for BOTH triples containing A,B
		// Triple {A,B,C}: orderings with A<B are Lehmer 0(A,B,C), 1(A,C,B), 4(C,A,B)
		int[] reqABC = {10, 20, 30};
		sf.recordInfeasibleOrdering(reqABC, new int[]{0, 1, 2}, 3); // (A,B,C) L=0
		sf.recordInfeasibleOrdering(reqABC, new int[]{0, 2, 1}, 3); // (A,C,B) L=1
		// Need Lehmer 4 = (C,A,B) → perm (2,0,1) → elements at rank 2 first, rank 0 second, rank 1 third
		sf.recordInfeasibleOrdering(reqABC, new int[]{2, 0, 1}, 3); // (C,A,B) L=4

		// Triple {A,B,D}: same — all A<B orderings infeasible
		int[] reqABD = {10, 20, 40};
		sf.recordInfeasibleOrdering(reqABD, new int[]{0, 1, 2}, 3); // (A,B,D) L=0
		sf.recordInfeasibleOrdering(reqABD, new int[]{0, 2, 1}, 3); // (A,D,B) L=1
		sf.recordInfeasibleOrdering(reqABD, new int[]{2, 0, 1}, 3); // (D,A,B) L=4
		sf.commit();

		// Build unconstrained DAG for {A,B,C,D}
		int[] requestIndices = {10, 20, 30, 40};
		Boolean[][] adj = new Boolean[4][4]; // all null = unconstrained

		int added = sf.tightenDAG(adj, requestIndices, 4);

		// Should add edge: B before A (because A<B is always infeasible)
		assertEquals(true, adj[1][0]); // B→A: B must come before A
		assertEquals(false, adj[0][1]); // A→B: blocked
		assertTrue(added >= 1);
	}

	@Test
	void tightenDAG_noEffectWhenMixed() {
		SubSetOrderingFeasibility sf = new SubSetOrderingFeasibility(3);

		// Record only SOME orderings with A<B as infeasible for {A,B,C}
		int[] reqABC = {10, 20, 30};
		sf.recordInfeasibleOrdering(reqABC, new int[]{0, 1, 2}, 3); // (A,B,C) only
		sf.commit();

		Boolean[][] adj = new Boolean[3][3];
		int added = sf.tightenDAG(adj, reqABC, 3);

		// Only 1 of 3 A<B orderings is infeasible → can't force direction
		assertEquals(0, added);
		assertNull(adj[0][1]);
		assertNull(adj[1][0]);
	}

	@Test
	void tightenDAG_skipsAlreadyConstrained() {
		SubSetOrderingFeasibility sf = new SubSetOrderingFeasibility(3);

		int[] req = {10, 20, 30};
		// Record all 6 orderings as infeasible
		for (int a = 0; a < 3; a++)
			for (int b = 0; b < 3; b++) {
				if (b == a) continue;
				for (int c = 0; c < 3; c++) {
					if (c == a || c == b) continue;
					sf.recordInfeasibleOrdering(req, new int[]{a, b, c}, 3);
				}
			}
		sf.commit();

		// DAG already has constraint A→B
		Boolean[][] adj = new Boolean[3][3];
		adj[0][1] = true; adj[1][0] = false;

		int added = sf.tightenDAG(adj, req, 3);

		// Pair (A,B) already constrained — should not be modified
		assertEquals(true, adj[0][1]);
		assertEquals(false, adj[1][0]);
	}

	@Test
	void stats() {
		SubSetOrderingFeasibility sf = new SubSetOrderingFeasibility(3);
		int[] req = {10, 20, 30};

		// Record 3 out of 6 orderings as infeasible
		sf.recordInfeasibleOrdering(req, new int[]{0, 1, 2}, 3);
		sf.recordInfeasibleOrdering(req, new int[]{1, 0, 2}, 3);
		sf.recordInfeasibleOrdering(req, new int[]{2, 1, 0}, 3);
		sf.commit();

		assertEquals(1, sf.getTripleCount());
		assertEquals(3.0, sf.getAvgInfeasiblePerTriple(), 0.01);
		assertEquals(3, sf.getTotalInfeasibleTripleBits());
	}
}
