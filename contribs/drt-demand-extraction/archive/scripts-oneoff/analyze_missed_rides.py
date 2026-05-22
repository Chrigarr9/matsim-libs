"""
Investigate WHY some degree-4 rides have sub-triples that aren't valid degree-3 rides.

If {A,B,D} is infeasible, how can {A,B,C,D} be feasible?
Adding passengers only adds stops → more detour → should be WORSE for existing passengers.

Hypothesis: the sub-triple was rejected by DISTANCE SAVINGS PRUNING, not by actual
constraint infeasibility. The degree-4 ride has a larger distance budget (more direct
distances summed) so orderings pruned at degree 3 survive at degree 4.
"""

import csv
import sys
from collections import defaultdict
from itertools import combinations


def parse_index_list(s):
    s = s.strip("[]")
    if not s:
        return ()
    if " | " in s:
        return tuple(sorted(int(x.strip()) for x in s.split(" | ")))
    return tuple(sorted(int(x.strip()) for x in s.split(",")))


def parse_float_list(s):
    s = s.strip("[]")
    if not s:
        return ()
    if " | " in s:
        return tuple(float(x.strip()) for x in s.split(" | "))
    return tuple(float(x.strip()) for x in s.split(","))


def main():
    if len(sys.argv) < 2:
        print("Usage: python analyze_missed_rides.py <rides_csv>")
        sys.exit(1)

    rides_path = sys.argv[1]

    # Parse rides with full data
    rides_by_set = {}  # (sorted request indices) -> ride data
    rides_by_degree = defaultdict(list)
    ride_sets_by_degree = defaultdict(set)
    request_direct_distances = {}  # request_index -> direct distance

    with open(rides_path) as f:
        reader = csv.DictReader(f)
        for row in reader:
            degree = int(row["degree"])
            req_indices = parse_index_list(row["requestIndices"])
            ride_dist = float(row["rideDistance"])
            pax_tt = parse_float_list(row["passengerTravelTimes"])
            pax_dist = parse_float_list(row["passengerDistances"])
            remaining_budgets = parse_float_list(row["remainingBudgets"])
            detours = parse_float_list(row["detours"])

            rides_by_degree[degree].append(req_indices)
            ride_sets_by_degree[degree].add(req_indices)
            rides_by_set[req_indices] = {
                "degree": degree,
                "rideDistance": ride_dist,
                "passengerTravelTimes": pax_tt,
                "passengerDistances": pax_dist,
                "remainingBudgets": remaining_budgets,
                "detours": detours,
                "requestIndices": req_indices,
            }

            # For singles, record direct distance
            if degree == 1:
                request_direct_distances[req_indices[0]] = float(row["rideDistance"])

    # Find degree-4 rides with only 1 valid sub-triple
    deg3_sets = ride_sets_by_degree.get(3, set())
    deg4_sets = ride_sets_by_degree.get(4, set())

    print("=" * 70)
    print("DETAILED ANALYSIS OF DEGREE-4 RIDES WITH ONLY 1 VALID SUB-TRIPLE")
    print("=" * 70)

    missed_count = 0
    for ride_set in sorted(deg4_sets):
        sub_triples = list(combinations(ride_set, 3))
        valid_subs = [s for s in sub_triples if tuple(sorted(s)) in deg3_sets]
        invalid_subs = [s for s in sub_triples if tuple(sorted(s)) not in deg3_sets]

        if len(valid_subs) != 1:
            continue

        missed_count += 1
        ride_data = rides_by_set[ride_set]

        print(f"\n--- Ride {ride_set} ---")
        print(f"  Ride distance: {ride_data['rideDistance']:.1f}m")
        print(f"  Detours: {', '.join(f'{d:.2f}x' for d in ride_data['detours'])}")
        print(f"  Remaining budgets: {', '.join(f'{b:.2f}' for b in ride_data['remainingBudgets'])}")

        # Compute distance budget for this degree-4 set
        sum_direct_deg4 = sum(request_direct_distances.get(r, 0) for r in ride_set)
        distance_saving_deg4 = 1.0 - ride_data["rideDistance"] / sum_direct_deg4 if sum_direct_deg4 > 0 else 0
        print(f"  Sum direct distances (deg4): {sum_direct_deg4:.0f}m")
        print(f"  Distance saving (deg4): {distance_saving_deg4:.1%}")

        print(f"  Valid sub-triple: {valid_subs[0]}")
        valid_sub_data = rides_by_set.get(tuple(sorted(valid_subs[0])))
        if valid_sub_data:
            print(f"    Sub-ride distance: {valid_sub_data['rideDistance']:.1f}m")
            sum_direct_sub = sum(request_direct_distances.get(r, 0) for r in valid_subs[0])
            sub_saving = 1.0 - valid_sub_data["rideDistance"] / sum_direct_sub if sum_direct_sub > 0 else 0
            print(f"    Sub sum direct: {sum_direct_sub:.0f}m, saving: {sub_saving:.1%}")

        print(f"  Invalid sub-triples ({len(invalid_subs)}):")
        for inv_sub in invalid_subs:
            sum_direct_sub = sum(request_direct_distances.get(r, 0) for r in inv_sub)
            # Check if any of these requests appear in pair rides
            print(f"    {tuple(sorted(inv_sub))} — sum direct: {sum_direct_sub:.0f}m")

    print(f"\n\nTotal degree-4 rides with only 1 valid sub-triple: {missed_count}")

    # Key question: what is the distance-savings pruning threshold?
    # From the config: requiredSaving = scale * ln(degree) / ln(2)
    # Default scale = 0.15, so:
    # degree 3: 0.15 * ln(3)/ln(2) = 0.15 * 1.585 = 0.238 = 23.8%
    # degree 4: 0.15 * ln(4)/ln(2) = 0.15 * 2.0 = 0.30 = 30.0%
    print("\n" + "=" * 70)
    print("DISTANCE SAVINGS THRESHOLDS (scale=0.15, max=0.75)")
    print("=" * 70)
    import math
    scale = 0.15
    max_saving = 0.75
    for deg in range(2, 8):
        saving = min(max_saving, scale * math.log(deg) / math.log(2))
        print(f"  Degree {deg}: required saving ≥ {saving:.1%} → maxRideDistance ≤ {1-saving:.1%} × sumDirect")

    # Now check: for each invalid sub-triple, what WOULD its distance saving be
    # if it existed? We can estimate from the degree-4 ride.
    print("\n" + "=" * 70)
    print("HYPOTHESIS TEST: Were invalid sub-triples rejected by distance pruning?")
    print("=" * 70)
    print("If the sub-triple's ride would exceed the deg-3 distance threshold,")
    print("it was rejected by PRUNING, not by constraint infeasibility.")

    import math
    deg3_threshold = min(max_saving, scale * math.log(3) / math.log(2))
    deg4_threshold = min(max_saving, scale * math.log(4) / math.log(2))

    for ride_set in sorted(deg4_sets):
        sub_triples = list(combinations(ride_set, 3))
        valid_subs = [s for s in sub_triples if tuple(sorted(s)) in deg3_sets]
        if len(valid_subs) != 1:
            continue

        ride_data = rides_by_set[ride_set]

        # For the degree-4 ride: what distance saving does it achieve?
        sum_direct_4 = sum(request_direct_distances.get(r, 0) for r in ride_set)
        saving_4 = 1.0 - ride_data["rideDistance"] / sum_direct_4 if sum_direct_4 > 0 else 0

        # Check: does the degree-4 ride barely pass its own threshold?
        barely_passes = saving_4 < deg4_threshold + 0.05  # within 5% of threshold

        print(f"\n  Ride {ride_set}: deg4 saving={saving_4:.1%} (threshold={deg4_threshold:.1%}) {'← BARELY PASSES' if barely_passes else ''}")

        # For each invalid sub-triple, estimate what its ride distance would be
        # (approximate: degree-4 ride minus the extra passenger's contribution)
        for inv_sub in [s for s in sub_triples if tuple(sorted(s)) not in deg3_sets]:
            inv_sub = tuple(sorted(inv_sub))
            sum_direct_3 = sum(request_direct_distances.get(r, 0) for r in inv_sub)
            max_ride_dist_3 = (1 - deg3_threshold) * sum_direct_3
            print(f"    Sub-triple {inv_sub}: sumDirect={sum_direct_3:.0f}m, maxRideDist@deg3={max_ride_dist_3:.0f}m")


if __name__ == "__main__":
    main()
