"""
Analyze whether ordering conflicts from lower degrees should theoretically
prune a significant fraction of orderings at higher degrees.

Uses actual data from the 10% Bavaria benchmark (Trigger 2 run).
"""
import math
from itertools import combinations

# === Actual data from 10% Bavaria Trigger 2 run ===

total_requests = 21_000  # approximate

data = {
    3: {"sets": 7_053_256, "orderings_eval": 21_000_000, "rides": 1_065_484,
        "conflicts_after": {"L3": 1_729_149}, "pruned_by_conflict": 0},
    4: {"sets": 1_940_935, "orderings_eval": 21_000_000, "rides": 1_581_376,
        "conflicts_after": {"L3": 1_729_149, "L4": 424_361}, "pruned_by_conflict": 798_025},
    5: {"sets": 1_740_860, "orderings_eval": 131_000_000, "rides": 1_536_778,
        "conflicts_after": {"L3": 1_729_149, "L4": 424_363, "L5": 556_123},
        "pruned_by_conflict": 1_551_084},  # from deg 6 using deg5 conflicts
    6: {"sets": 965_232, "orderings_eval": 613_000_000, "rides": 890_312,
        "conflicts_after": {"L3": 1_729_149, "L4": 424_363, "L5": 556_128, "L6": 522_510},
        "pruned_by_conflict": 1_551_084},
    7: {"sets": 360_066, "orderings_eval": 2_076_840_260, "rides": 340_024,
        "conflicts_after": {"L3": 1_729_149, "L4": 424_363, "L5": 556_128,
                            "L6": 522_510, "L7": 445_636},
        "pruned_by_conflict": 1_673_003},
    8: {"sets": 89_200, "orderings_eval": 4_239_023_590, "rides": 83_861,
        "conflicts_after": {"L3": 1_729_149, "L4": 424_363, "L5": 556_128,
                            "L6": 522_524, "L7": 445_636, "L8": 411_022},
        "pruned_by_conflict": 945_299},
}

print("=" * 80)
print("CONFLICT DENSITY ANALYSIS")
print("=" * 80)

# === Part 1: Conflict density in the combinatorial space ===

print("\n--- Part 1: How sparse are the conflicts? ---\n")

for L in [3, 4, 5]:
    # Total possible ordered L-tuples from N requests
    total_ordered_tuples = 1
    for i in range(L):
        total_ordered_tuples *= (total_requests - i)

    # Number of conflicts at this length
    conflicts = data[max(data.keys())]["conflicts_after"].get(f"L{L}", 0)

    density = conflicts / total_ordered_tuples if total_ordered_tuples > 0 else 0

    print(f"L{L} conflicts: {conflicts:,}")
    print(f"  Possible ordered {L}-tuples from {total_requests:,} requests: {total_ordered_tuples:.2e}")
    print(f"  Conflict density: {density:.2e} ({density * 100:.8f}%)")
    print()

# === Part 2: Expected prune rate per ordering ===

print("\n--- Part 2: Expected prune rate per origin ordering ---\n")

for degree in [5, 6, 7, 8]:
    print(f"Degree {degree}:")
    print(f"  An origin ordering of {degree} elements has these subsequences:")

    total_prune_prob = 0
    for L in range(3, degree + 1):
        n_subseq = math.comb(degree, L)
        conflicts = data[degree]["conflicts_after"].get(f"L{L}", 0)
        total_ordered_tuples = 1
        for i in range(L):
            total_ordered_tuples *= (total_requests - i)
        density = conflicts / total_ordered_tuples if total_ordered_tuples > 0 else 0

        # Probability that at least one L-subsequence matches
        # P(match) ≈ 1 - (1 - density)^n_subseq ≈ n_subseq * density (for small density)
        p_match = n_subseq * density  # first-order approximation

        total_prune_prob += p_match
        print(f"    L{L}: C({degree},{L}) = {n_subseq} subsequences, "
              f"density = {density:.2e}, "
              f"P(match) ≈ {p_match:.6f} ({p_match*100:.4f}%)")

    print(f"  Total P(any conflict matches) ≈ {total_prune_prob:.6f} ({total_prune_prob*100:.4f}%)")

    d = data[degree]
    # Estimate origin orderings (total orderings / avg dest orderings per origin)
    avg_ord_per_set = d["orderings_eval"] / d["sets"] if d["sets"] > 0 else 0
    # Rough estimate: origin orderings ≈ sqrt(total orderings per set) for balanced cases
    est_origin_ords = max(1, avg_ord_per_set ** 0.5)
    est_total_origin_ords = est_origin_ords * d["sets"]

    expected_prunes = total_prune_prob * est_total_origin_ords
    actual_prunes = d["pruned_by_conflict"]

    print(f"  Estimated origin orderings: {est_total_origin_ords:,.0f} "
          f"({est_origin_ords:.0f}/set × {d['sets']:,} sets)")
    print(f"  Expected prunes (uniform): {expected_prunes:,.0f}")
    print(f"  Actual prunes:             {actual_prunes:,}")
    if expected_prunes > 0:
        print(f"  Actual / Expected ratio:   {actual_prunes / expected_prunes:.1f}x "
              f"(conflicts are {'clustered' if actual_prunes > expected_prunes else 'sparser than uniform'})")
    print()

# === Part 3: What density would we NEED? ===

print("\n--- Part 3: What conflict density would be needed for 50% pruning? ---\n")

for degree in [6, 7, 8]:
    n_subseq_L3 = math.comb(degree, 3)
    # For 50% of origin orderings to have at least one L3 conflict match:
    # 1 - (1-d)^n_subseq = 0.5 → d = 1 - 0.5^(1/n_subseq)
    needed_density = 1 - 0.5 ** (1 / n_subseq_L3)
    needed_conflicts = needed_density * total_requests * (total_requests-1) * (total_requests-2)

    actual_L3 = data[degree]["conflicts_after"].get("L3", 0)
    print(f"Degree {degree}: need L3 density = {needed_density:.6f} "
          f"→ {needed_conflicts:,.0f} L3 conflicts (have {actual_L3:,}, "
          f"need {needed_conflicts / actual_L3:.0f}x more)")

# === Part 4: The real issue — request clustering ===

print("\n\n--- Part 4: Request clustering effect ---\n")

print("The uniform analysis above assumes conflicts are spread across all 21k requests.")
print("But in reality, degree-7 sets only use highly-connected requests.\n")

for degree in [5, 6, 7, 8]:
    d = data[degree]
    # Upper bound on unique requests: sets × degree (with lots of overlap)
    max_unique = d["sets"] * degree
    # Lower bound: at least degree requests
    # Estimate: requests follow power-law connectivity; top ~30-50% participate at high degrees
    est_unique = min(total_requests, int(total_requests * 0.4))  # rough estimate

    for pool_name, pool_size in [("all 21k", total_requests),
                                  ("est. active ~8k", 8000),
                                  ("hypothetical 3k", 3000)]:
        total_L3_tuples = pool_size * (pool_size - 1) * (pool_size - 2)
        L3_conflicts = d["conflicts_after"].get("L3", 0)
        density = L3_conflicts / total_L3_tuples if total_L3_tuples > 0 else 0
        n_subseq = math.comb(degree, 3)
        p_match = min(1.0, n_subseq * density)

        print(f"  Degree {degree}, pool={pool_name}: "
              f"L3 density={density:.2e}, "
              f"P(prune)={p_match*100:.3f}%")
    print()

# === Part 5: Actual conflict check — how many unique requests in conflicts? ===

print("\n--- Part 5: Summary ---\n")
print("The core problem: with 21k requests, the 3-tuple space is ~9 TRILLION.")
print("Even 1.7M conflicts cover only 0.00002% of that space.")
print()
print("For conflicts to prune 50% of orderings at degree 7, we'd need")
print("~570 BILLION L3 conflicts — 330,000x more than we have.")
print()
print("This means ordering conflicts as currently designed (exact request-index")
print("subsequences) are fundamentally too sparse to meaningfully prune the")
print("ordering space at higher degrees.")
print()
print("POSSIBLE SOLUTIONS:")
print("  1. Abstract conflicts: Instead of specific request indices, learn")
print("     patterns like 'if detour between origin i and origin j exceeds X,")
print("     skip'. This generalizes across request sets.")
print("  2. Pairwise direction constraints: For each pair (A,B), learn which")
print("     origin direction works. This is dense (one bit per pair) and")
print("     directly reduces topological sort count. Already partially done")
print("     via degree graph consensus tightening.")
print("  3. Destination constraint tightening: Use sub-set orderings to")
print("     constrain destination orderings (currently only origins are")
print("     tightened via consensus).")
print("  4. Within-set heuristic: Try the most promising ordering first")
print("     (e.g., nearest-neighbor), get a tight B&B bound, then prune")
print("     most alternatives via distance B&B.")
