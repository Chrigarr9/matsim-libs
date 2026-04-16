"""
Analyze sub-triple feasibility for the higher-order shareability graph idea.

For each degree-k ride (k >= 4), check how many of its (k choose k-1) sub-sets
exist as valid degree-(k-1) rides. This tells us whether building a degree-specific
shareability graph would miss valid rides.

Key question: if the degree-3 graph only generates degree-4 candidates where at least
2 sub-triples are valid, how many degree-4 rides would we miss?

Usage:
    python scripts/analyze_subtriple_feasibility.py <rides_csv_path>

Example:
    python scripts/analyze_subtriple_feasibility.py \
        matsim_scenarios/bavaria/output/demand-extraction-1pct-traveltime-pruning/drt_demand/bavaria-30km-100pct-exmas.exmas_rides.csv
"""

import csv
import sys
from collections import defaultdict
from itertools import combinations


def parse_index_list(s: str) -> tuple[int, ...]:
    """Parse '[0 | 1 | 2]' or '[0,1,2]' into (0, 1, 2)."""
    s = s.strip("[]")
    if not s:
        return ()
    # Handle both ' | ' and ',' separators
    if " | " in s:
        return tuple(sorted(int(x.strip()) for x in s.split(" | ")))
    return tuple(sorted(int(x.strip()) for x in s.split(",")))


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    rides_path = sys.argv[1]

    # Parse all rides, group by degree
    rides_by_degree: dict[int, list[tuple[int, ...]]] = defaultdict(list)
    ride_sets_by_degree: dict[int, set[tuple[int, ...]]] = defaultdict(set)

    # Also track ordering info per ride set
    orderings_by_set: dict[tuple[int, ...], list[tuple[tuple[int,...], tuple[int,...]]]] = defaultdict(list)

    with open(rides_path, "r") as f:
        reader = csv.DictReader(f)
        for row in reader:
            degree = int(row["degree"])
            req_indices = parse_index_list(row["requestIndices"])
            orig_ordered = parse_index_list(row["originsOrdered"])
            dest_ordered = parse_index_list(row["destinationsOrdered"])

            rides_by_degree[degree].append(req_indices)
            ride_sets_by_degree[degree].add(req_indices)
            orderings_by_set[req_indices].append((orig_ordered, dest_ordered))

    print("=" * 70)
    print("RIDE COUNTS BY DEGREE")
    print("=" * 70)
    for deg in sorted(rides_by_degree.keys()):
        print(f"  Degree {deg}: {len(rides_by_degree[deg])} rides ({len(ride_sets_by_degree[deg])} unique sets)")

    # Analysis 1: Sub-set feasibility
    print()
    print("=" * 70)
    print("ANALYSIS 1: SUB-SET FEASIBILITY")
    print("For each degree-k ride, how many (k-1)-subsets are valid rides?")
    print("=" * 70)

    for deg in sorted(rides_by_degree.keys()):
        if deg < 3:
            continue

        prev_deg = deg - 1
        prev_sets = ride_sets_by_degree.get(prev_deg, set())

        if not prev_sets:
            print(f"\n  Degree {deg}: no degree-{prev_deg} rides to check against")
            continue

        # Count valid sub-sets for each ride
        valid_subset_counts = defaultdict(int)  # count -> number of rides with that count

        for ride_set in ride_sets_by_degree[deg]:
            n_valid = 0
            for subset in combinations(ride_set, prev_deg):
                subset = tuple(sorted(subset))
                if subset in prev_sets:
                    n_valid += 1
            valid_subset_counts[n_valid] += 1

        total_rides = len(ride_sets_by_degree[deg])
        max_subsets = deg  # (k choose k-1) = k

        print(f"\n  Degree {deg} ({total_rides} rides, {max_subsets} possible sub-{prev_deg}-sets each):")

        for count in range(max_subsets + 1):
            n = valid_subset_counts.get(count, 0)
            pct = 100.0 * n / total_rides if total_rides > 0 else 0
            bar = "#" * int(pct / 2)
            print(f"    {count} valid sub-sets: {n:>6} rides ({pct:5.1f}%) {bar}")

        # Key metric: rides with 0 or 1 valid sub-sets (would be missed by degree-k-1 graph)
        missed_0 = valid_subset_counts.get(0, 0)
        missed_1 = valid_subset_counts.get(1, 0)
        found = total_rides - missed_0  # at least 1 valid sub-set = found by current approach
        found_by_graph = total_rides - missed_0 - missed_1  # at least 2 valid sub-sets

        print(f"\n    Summary:")
        print(f"      Rides with ≥1 valid sub-set (current approach finds): {found} ({100*found/total_rides:.1f}%)")
        print(f"      Rides with ≥2 valid sub-sets (degree-{prev_deg} graph finds): {found_by_graph} ({100*found_by_graph/total_rides:.1f}%)")
        print(f"      Would MISS (only 1 valid sub-set): {missed_1} ({100*missed_1/total_rides:.1f}%)")
        print(f"      Have 0 valid sub-sets: {missed_0} ({100*missed_0/total_rides:.1f}%)")

    # Analysis 2: How many candidate sets does each approach generate?
    print()
    print("=" * 70)
    print("ANALYSIS 2: CANDIDATE SET GENERATION COMPARISON")
    print("Current: base + pairwise common neighbors")
    print("Proposed: degree-(k-1) graph edges (pairs sharing k-2 members)")
    print("=" * 70)

    for deg in sorted(rides_by_degree.keys()):
        if deg < 4:
            continue

        prev_deg = deg - 1
        prev_sets = ride_sets_by_degree.get(prev_deg, set())

        if not prev_sets:
            continue

        # Build index: for each (k-2)-subset, which degree-(k-1) rides contain it?
        subset_index: dict[tuple[int,...], list[tuple[int,...]]] = defaultdict(list)
        shared_size = prev_deg - 1  # k-2 shared members

        for ride_set in prev_sets:
            for subset in combinations(ride_set, shared_size):
                subset = tuple(sorted(subset))
                subset_index[subset].append(ride_set)

        # Generate candidates from degree-(k-1) graph
        graph_candidates = set()
        for shared_subset, rides_with_subset in subset_index.items():
            if len(rides_with_subset) < 2:
                continue
            for r1, r2 in combinations(rides_with_subset, 2):
                candidate = tuple(sorted(set(r1) | set(r2)))
                if len(candidate) == deg:  # must be exactly k members
                    graph_candidates.add(candidate)

        actual_valid = ride_sets_by_degree[deg]
        graph_found = graph_candidates & actual_valid
        graph_missed = actual_valid - graph_candidates
        graph_overhead = graph_candidates - actual_valid

        print(f"\n  Degree {deg}:")
        print(f"    Valid rides that exist: {len(actual_valid)}")
        print(f"    Degree-{prev_deg} graph candidates: {len(graph_candidates)}")
        print(f"    Found by graph: {len(graph_found)} ({100*len(graph_found)/len(actual_valid):.1f}%)")
        print(f"    Missed by graph: {len(graph_missed)} ({100*len(graph_missed)/len(actual_valid):.1f}%)")
        print(f"    Graph overhead (candidates that aren't valid): {len(graph_overhead)}")
        if graph_missed:
            print(f"    Example missed rides (first 5):")
            for ride in sorted(graph_missed)[:5]:
                # Check how many valid sub-sets this ride has
                n_valid = sum(1 for s in combinations(ride, prev_deg) if tuple(sorted(s)) in prev_sets)
                print(f"      {ride} — {n_valid} valid sub-{prev_deg}-sets")

    # Analysis 3: Ordering reuse potential
    print()
    print("=" * 70)
    print("ANALYSIS 3: ORDERING INFORMATION FROM SUB-RIDES")
    print("How many valid sub-orderings does each ride's winning ordering contain?")
    print("=" * 70)

    for deg in sorted(rides_by_degree.keys()):
        if deg < 4:
            continue

        prev_deg = deg - 1

        # For each degree-k ride, check if its ordering contains valid sub-orderings
        contains_valid_sub = 0
        total_checked = 0

        for ride_set in ride_sets_by_degree[deg]:
            if ride_set not in orderings_by_set:
                continue

            orig_order, dest_order = orderings_by_set[ride_set][0]  # first (best) ordering
            total_checked += 1

            # Check each (k-1)-subset
            found_valid_sub_ordering = False
            for subset in combinations(ride_set, prev_deg):
                subset_sorted = tuple(sorted(subset))
                if subset_sorted not in orderings_by_set:
                    continue

                # Extract sub-ordering from the degree-k ordering
                sub_orig = tuple(x for x in orig_order if x in subset)
                sub_dest = tuple(x for x in dest_order if x in subset)

                # Check if this sub-ordering matches any valid ordering for the subset
                for valid_orig, valid_dest in orderings_by_set[subset_sorted]:
                    if sub_orig == valid_orig and sub_dest == valid_dest:
                        found_valid_sub_ordering = True
                        break
                if found_valid_sub_ordering:
                    break

            if found_valid_sub_ordering:
                contains_valid_sub += 1

        if total_checked > 0:
            print(f"  Degree {deg}: {contains_valid_sub}/{total_checked} rides ({100*contains_valid_sub/total_checked:.1f}%) contain a valid sub-ordering from degree {prev_deg}")


if __name__ == "__main__":
    main()
