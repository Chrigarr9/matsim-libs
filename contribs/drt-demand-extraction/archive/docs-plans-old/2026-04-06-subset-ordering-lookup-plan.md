# Direct Sub-Set Ordering Lookup — Implementation Plan

## Problem

At degree 7, each candidate set has ~5,768 orderings despite pairwise consensus tightening. The factorial wall on orderings dominates runtime (401s at degree 7, 987s at degree 8). The existing OrderingConflicts mechanism (hash-based subsequence matching) covers only 0.00002% of the 3-tuple space and prunes <0.1% of orderings.

## Insight

Every degree-7 set has C(7,3)=35 triple sub-sets, C(7,4)=35 quad sub-sets, C(7,5)=21 quint sub-sets — and we already computed valid rides for ALL of them at degrees 3, 4, 5. We know exactly which origin orderings produced valid rides for each sub-set.

Instead of sparse cross-set conflict hashing, use **direct sub-set ordering lookup**: for each sub-set of the current set, check whether the implied origin ordering is known to be infeasible.

## Design

### Data Structure: `SubSetOrderingFeasibility`

After each degree k, build a map:
```
Map<long, BitSet>   // key = sorted sub-set hash, value = infeasible ordering bitmask
```

For a degree-3 sub-set {A, B, C}, there are 3! = 6 possible origin orderings. Map each ordering to a bit index (0-5). The BitSet records which orderings are **infeasible** (all destination orderings failed due to absolute travel time violations — same criterion as Trigger 2).

For degree 4: 4! = 24 possible orderings → 24 bits (fits in an int).
For degree 5: 5! = 120 possible orderings → 120 bits (2 longs).

### Ordering → Bit Index Mapping

Given a sub-set of size k with elements sorted as [a₀, a₁, ..., a_{k-1}], an origin ordering is a permutation of these k elements. The bit index is the **Lehmer code** (factorial number system) of the permutation:

```java
static int lehmerIndex(int[] perm, int k) {
    int index = 0;
    for (int i = 0; i < k; i++) {
        int count = 0;
        for (int j = i + 1; j < k; j++) {
            if (perm[j] < perm[i]) count++;
        }
        index = index * (k - i) + count;
    }
    return index;
}
```

This is O(k²) per permutation, trivial for k ≤ 7.

### Recording (after each degree)

During `processSet`, when Trigger 2 fires (all-dest-fail, no distance B&B):
1. The origin ordering `perm[0..n-1]` is known
2. For each sub-set of size k (k = 3, 4, 5):
   - Extract the sub-ordering (the relative order of the k elements within perm)
   - Compute the sorted sub-set hash and Lehmer index
   - Set the corresponding bit in the infeasibility map

Additionally, for sub-sets where the set itself was structurally infeasible (pair constraint incompatibility in `enumerateDestPrunedWithEval`), all orderings that reach the dest phase are infeasible.

The recording can happen inline in the existing Trigger 2 code path — when all-dest-fail fires, record for all sub-set sizes.

### Lookup (during enumeration)

During `enumerateOriginsPrunedWithEval` at each depth d > 2, when evaluating candidate c:

```java
// For each pair (a, b) of already-placed origins:
for (int i = 0; i < depth; i++) {
    for (int j = i + 1; j < depth; j++) {
        // Triple {perm[i], perm[j], c}
        int[] triple = sortAndTrack(perm[i], perm[j], c);
        long tripleHash = hashSorted(triple);
        BitSet infeasible = tripleInfeasibility.get(tripleHash);
        if (infeasible != null) {
            int ordering = lehmerFromPositions(perm[i], perm[j], c, depth);
            if (infeasible.get(ordering)) {
                // This triple ordering is proven infeasible
                stats.prunedBySubsetLookup++;
                skipCandidate = true;
                break;
            }
        }
    }
    if (skipCandidate) break;
}
```

For quads (if using degree-4 data):
```java
for (int i = 0; i < depth; i++) {
    for (int j = i + 1; j < depth; j++) {
        for (int k = j + 1; k < depth; k++) {
            // Quad {perm[i], perm[j], perm[k], c}
            // ... same lookup pattern ...
        }
    }
}
```

### Cost Analysis

| Check | Lookups per candidate at depth d | Cost per lookup | Total at depth 5 |
|-------|----------------------------------|-----------------|------------------|
| Triples | C(d,2) = 10 | ~30ns (hash + bit check) | ~300ns |
| Quads | C(d,3) = 10 | ~40ns | ~400ns |
| Quints | C(d,4) = 5 | ~50ns | ~250ns |
| **Total** | **25** | | **~1µs** |

Compare: current OrderingConflicts lookup is O(2^d) = 32-64 hash checks at depth 5-6.

### Memory

| Degree | Sub-sets | Bits per sub-set | Total memory |
|--------|----------|------------------|--------------|
| 3 | ~7M triple sets | 6 bits (1 byte) | ~7 MB |
| 4 | ~1.9M quad sets | 24 bits (3 bytes) | ~6 MB |
| 5 | ~1.7M quint sets | 120 bits (15 bytes) | ~26 MB |
| **Total** | | | **~39 MB** |

Negligible compared to current memory usage (~10-20 GB).

### Expected Impact

At degree 7, pairwise consensus leaves ~5,768 orderings/set. Triple/quad/quint constraints eliminate orderings where ANY sub-ordering is proven infeasible. The pruning compounds:
- A triple constraint eliminating 2 of 6 orderings = 33% reduction per triple
- With 35 triples per degree-7 set, even modest per-triple pruning compounds
- Quads and quints add further constraints

Conservative estimate: 2-4x ordering reduction at degree 7. This would cut 401s to ~100-200s.

## Implementation Steps

### Step 1: SubSetOrderingFeasibility data structure
- New class `SubSetOrderingFeasibility` in `algorithm/extension/`
- Hash map from sorted sub-set hash → infeasibility bitmask (long[] or int)
- Lehmer code encoder for permutation → bit index
- Thread-safe recording (same pending + commit pattern as OrderingConflicts)
- Lookup method: `isInfeasible(int[] sortedSubset, int[] ordering)`

### Step 2: Recording during enumeration
- In `enumerateOriginsPrunedWithEval` at depth == n, when Trigger 2 fires:
  - For each sub-set size k (3, 4, ..., min(n, maxK)):
    - For each C(n, k) sub-sets of the origin perm:
      - Extract sub-ordering, compute Lehmer index
      - Record (sortedSubsetHash, lehmerIndex) as infeasible
- Also record when `enumerateDestPrunedWithEval` returns due to structural infeasibility

### Step 3: Lookup during origin candidate selection
- In `enumerateOriginsPrunedWithEval`, after conflict lookup, before routing:
  - For each sub-set size k (3, 4, ..., maxK):
    - For each C(depth, k-1) sub-sets of already-placed origins + candidate:
      - Check infeasibility map
      - If infeasible → skip candidate
- Add `prunedBySubsetLookup` counter to EnumerationStats

### Step 4: Wire up in ExMasEngine
- Create `SubSetOrderingFeasibility` alongside `OrderingConflicts`
- Pass to `RideExtender` → `OrderingEnumerator`
- Commit between degrees (same as OrderingConflicts)

### Step 5: Benchmark
- Run 10% Bavaria, compare ordering counts and timing per degree
- Verify ride counts match baseline (modulo time-dependent routing tolerance)
- Profile: what fraction of orderings pruned by sub-set lookup vs other mechanisms?

### Step 6: Evaluate whether OrderingConflicts can be removed
- If sub-set lookup subsumes the conflict mechanism, remove OrderingConflicts
- Simplify code path: no more O(2^d) subsequence enumeration

## Open Questions

1. **Max sub-set size (maxK):** Should we check triples only, or also quads and quints? Cost grows as C(d, k-1). Triples are cheapest and most numerous. Quads add value but cost more. Profile to find sweet spot.

2. **Intra-degree learning:** Currently recording only happens via Trigger 2 at the end of dest enumeration. Could we also record during enumeration when we discover infeasible sub-orderings from Check A/B/dropoff? This would capture more infeasibility data within a single degree.

3. **Destination ordering lookup:** The same mechanism could be applied to destination orderings: for each sub-set, which destination orderings are valid given the chosen origin ordering? This is more complex (depends on origin ordering) but could further reduce dest orderings.

4. **Replace vs supplement:** Should SubSetOrderingFeasibility replace OrderingConflicts entirely? The sub-set lookup is strictly more efficient (polynomial vs exponential) and has better coverage (100% of sub-sets vs sparse cross-set). The only thing OrderingConflicts does that sub-set lookup doesn't is cross-set transfer of mixed origin+dest conflicts — but those are unmatchable during origin-only lookup anyway.

## Relationship to Prior Work

This replaces the OrderingConflicts mechanism (commits 63c87f7 through 8b48055) with a fundamentally better data structure. The analysis in `scripts/conflict_density_analysis.py` showed that exact-index subsequence conflicts cover 0.00002% of the 3-tuple space. Sub-set ordering lookup achieves 100% coverage by using local sub-set data (already computed at lower degrees) instead of sparse global hashes.

The transfer principle is the same (triangle inequality: more stops → longer travel times → if infeasible at degree k, infeasible at degree k+1), just with a proper data organization.
