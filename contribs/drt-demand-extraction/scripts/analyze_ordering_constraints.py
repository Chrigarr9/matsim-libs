#!/usr/bin/env python3
"""
Analyze ordering constraint propagation potential from existing ride data.

Uses 10% Bavaria rides to estimate:
1. Pair graph flexibility (how many pairs allow both origin orderings)
2. How much sub-triple orderings tighten degree-4 enumeration (origin + destination)
3. How much sub-quad orderings tighten degree-5 enumeration
4. Net effect: losing distance B&B vs gaining ordering constraints
"""

import csv
import sys
import time
from itertools import permutations, combinations
from collections import defaultdict
import random

random.seed(42)

BASE_DIR = "matsim_scenarios/bavaria/output/demand-extraction-10pct-graph-analysis/drt_demand"
REQUESTS_FILE = f"{BASE_DIR}/bavaria-30km-100pct-exmas.drt_requests.csv"
RIDES_FILE = f"{BASE_DIR}/bavaria-30km-100pct-exmas.exmas_rides.csv"


def parse_pipe_list(s, dtype=float):
    """Parse '[a | b | c]' into list."""
    s = s.strip('[]')
    if not s:
        return []
    return [dtype(x.strip()) for x in s.split('|')]


def resolve_ordering(req_indices, request_times_map, delays, start_time):
    """Determine which request is at each pickup position using delays + requestTimes.

    delays[pos] = actual_pickup_time[pos] - requestTime[req_at_pos]
    So: requestTime[req_at_pos] = actual_pickup_time[pos] - delays[pos]

    For position 0: actual_pickup_time = startTime
    For position k: actual_pickup_time > actual_pickup_time[k-1] (monotonic)

    Returns list of request indices in pickup order, or None if ambiguous."""
    n = len(req_indices)
    req_times = {r: request_times_map[r] for r in req_indices}

    # For position 0: expected_requestTime = startTime - delays[0]
    expected_rt_0 = start_time - delays[0]

    # Find best match for position 0
    candidates_0 = []
    for r in req_indices:
        if abs(req_times[r] - expected_rt_0) < 0.5:  # within 0.5 seconds
            candidates_0.append(r)

    if len(candidates_0) == 0:
        return None

    # Try each candidate for position 0 and greedily assign remaining
    best_assignment = None
    for r0 in candidates_0:
        assignment = [r0]
        remaining = [r for r in req_indices if r != r0]
        prev_pickup = start_time
        success = True

        for pos in range(1, n):
            # Find the request whose requestTime + delays[pos] gives next pickup > prev_pickup
            best_r = None
            best_pickup = None
            for r in remaining:
                pickup = req_times[r] + delays[pos]
                if pickup > prev_pickup - 0.5:  # allow small tolerance
                    if best_r is None or pickup < best_pickup:
                        best_r = r
                        best_pickup = pickup

            if best_r is None:
                success = False
                break

            assignment.append(best_r)
            remaining.remove(best_r)
            prev_pickup = best_pickup

        if success and len(assignment) == n:
            if best_assignment is None:
                best_assignment = assignment
            else:
                # Multiple valid assignments — ambiguous
                if assignment != best_assignment:
                    return None  # Truly ambiguous

    return best_assignment


def resolve_dest_ordering(req_indices, dest_link_map, dest_links_ordered):
    """Resolve destination ordering from destination link IDs.
    Falls back to None if ambiguous (shared dest links)."""
    link_to_reqs = defaultdict(list)
    for r in req_indices:
        link_to_reqs[dest_link_map[r]].append(r)

    order = []
    for link in dest_links_ordered:
        candidates = [r for r in link_to_reqs.get(link, []) if r not in order]
        if len(candidates) == 1:
            order.append(candidates[0])
        elif len(candidates) > 1:
            return None  # Ambiguous
        else:
            return None

    return order if len(order) == len(req_indices) else None


def count_valid_orderings(n, constraints):
    """Count permutations of 0..n-1 satisfying all precedence constraints."""
    count = 0
    for perm in permutations(range(n)):
        pos = {v: i for i, v in enumerate(perm)}
        if all(pos[i] < pos[j] for i, j in constraints):
            count += 1
    return count


def get_pairwise_origin_constraints(request_set, pair_graph):
    """Extract origin ordering constraints from pair graph.
    Returns list of (i, j) meaning request_set[i] before request_set[j] in origins."""
    constraints = []
    n = len(request_set)
    for i in range(n):
        for j in range(i + 1, n):
            a, b = request_set[i], request_set[j]
            key = (min(a, b), max(a, b))
            if key not in pair_graph:
                return None

            info = pair_graph[key]
            if key[0] == a:
                fwd = info['forward']
                rev = info['reverse']
            else:
                fwd = info['reverse']
                rev = info['forward']

            if fwd and not rev:
                constraints.append((i, j))
            elif rev and not fwd:
                constraints.append((j, i))
    return constraints


def add_subset_origin_constraints(request_set, pair_constraints, subset_orderings):
    """Add origin ordering constraints from sub-set orderings."""
    n = len(request_set)
    constrained = set()
    for i, j in pair_constraints:
        constrained.add((min(i, j), max(i, j)))

    additional = []
    stats = {'flexible': 0, 'tightened': 0, 'conflicting': 0}

    for i in range(n):
        for j in range(i + 1, n):
            if (i, j) in constrained:
                continue
            stats['flexible'] += 1
            a, b = request_set[i], request_set[j]

            directions = set()
            for skip in range(n):
                if skip == i or skip == j:
                    continue
                sub = tuple(sorted(request_set[k] for k in range(n) if k != skip))
                if sub in subset_orderings:
                    origin_order = subset_orderings[sub][0]
                    try:
                        pa, pb = origin_order.index(a), origin_order.index(b)
                        directions.add('fwd' if pa < pb else 'rev')
                    except ValueError:
                        pass

            if len(directions) == 1:
                stats['tightened'] += 1
                if 'fwd' in directions:
                    additional.append((i, j))
                else:
                    additional.append((j, i))
            elif len(directions) == 2:
                stats['conflicting'] += 1

    return pair_constraints + additional, stats


def add_subset_dest_constraints(request_set, pair_graph, origin_order, subset_orderings):
    """Add destination ordering constraints from pair graph FIFO/LIFO + sub-set orderings.

    Given a fixed origin ordering, the pair graph's FIFO/LIFO determines destination constraints.
    Sub-set orderings can tighten further."""
    n = len(request_set)
    constraints = []

    # From pair graph: given origin order, derive dest constraints
    for i in range(n):
        for j in range(i + 1, n):
            a, b = request_set[i], request_set[j]
            key = (min(a, b), max(a, b))
            if key not in pair_graph:
                continue

            info = pair_graph[key]
            # Determine which direction was chosen in the origin ordering
            oi = origin_order.index(a) if a in origin_order else -1
            oj = origin_order.index(b) if b in origin_order else -1
            if oi < 0 or oj < 0:
                continue

            if oi < oj:
                # a picked up before b (forward for a<b, reverse for a>b)
                if key[0] == a:
                    fifo = info['forward_fifo']
                    lifo = info['forward_lifo']
                else:
                    fifo = info['reverse_fifo']
                    lifo = info['reverse_lifo']
            else:
                if key[0] == b:
                    fifo = info['forward_fifo']
                    lifo = info['forward_lifo']
                else:
                    fifo = info['reverse_fifo']
                    lifo = info['reverse_lifo']

            # FIFO: same order as origins (a before b in dest)
            # LIFO: reverse order (b before a in dest)
            if oi < oj:
                if fifo and not lifo:
                    constraints.append((i, j))  # a before b in dest
                elif lifo and not fifo:
                    constraints.append((j, i))  # b before a in dest
            else:
                if fifo and not lifo:
                    constraints.append((j, i))
                elif lifo and not fifo:
                    constraints.append((i, j))

    # Add sub-set destination constraints (same logic as origin)
    constrained = set()
    for ci, cj in constraints:
        constrained.add((min(ci, cj), max(ci, cj)))

    for i in range(n):
        for j in range(i + 1, n):
            if (i, j) in constrained:
                continue
            a, b = request_set[i], request_set[j]

            directions = set()
            for skip in range(n):
                if skip == i or skip == j:
                    continue
                sub = tuple(sorted(request_set[k] for k in range(n) if k != skip))
                if sub in subset_orderings:
                    dest_order = subset_orderings[sub][1]
                    try:
                        da, db = dest_order.index(a), dest_order.index(b)
                        directions.add('fwd' if da < db else 'rev')
                    except ValueError:
                        pass

            if len(directions) == 1:
                if 'fwd' in directions:
                    constraints.append((i, j))
                else:
                    constraints.append((j, i))

    return constraints


def main():
    t0 = time.time()

    # === Load requests ===
    print("Loading requests...")
    req_times = {}
    req_origin = {}
    req_dest = {}
    with open(REQUESTS_FILE) as f:
        for row in csv.DictReader(f):
            idx = int(row['index'])
            req_times[idx] = float(row['requestTime'])
            req_origin[idx] = int(row['originLinkId'])
            req_dest[idx] = int(row['destinationLinkId'])
    print(f"  {len(req_times)} requests ({time.time()-t0:.1f}s)")

    # === Load rides ===
    print("Loading rides...")
    pair_rides = []
    triple_rides = []
    quad_rides = []
    quint_rides = []
    t1 = time.time()
    with open(RIDES_FILE) as f:
        for row in csv.DictReader(f):
            d = int(row['degree'])
            if d == 2: pair_rides.append(row)
            elif d == 3: triple_rides.append(row)
            elif d == 4: quad_rides.append(row)
            elif d == 5: quint_rides.append(row)
    print(f"  Pair: {len(pair_rides)}, Triple: {len(triple_rides)}, "
          f"Quad: {len(quad_rides)}, Quint: {len(quint_rides)} ({time.time()-t1:.1f}s)")

    # === Build pair graph ===
    print("\n=== STEP 1: Build pair graph ===")
    pair_graph = defaultdict(lambda: {'forward': False, 'reverse': False,
                                       'forward_fifo': False, 'forward_lifo': False,
                                       'reverse_fifo': False, 'reverse_lifo': False})
    for row in pair_rides:
        ri = parse_pipe_list(row['requestIndices'], int)
        a, b = sorted(ri)
        delays = parse_pipe_list(row['delays'], float)
        start = float(row['startTime'])
        kind = row['kind']

        # Position 0 = first picked up. Who is it?
        expected_rt = start - delays[0]
        if abs(req_times[a] - expected_rt) < abs(req_times[b] - expected_rt):
            direction = 'forward'   # a first
        else:
            direction = 'reverse'   # b first

        pair_graph[(a, b)][direction] = True
        if kind == 'FIFO':
            pair_graph[(a, b)][f'{direction}_fifo'] = True
        elif kind == 'LIFO':
            pair_graph[(a, b)][f'{direction}_lifo'] = True

    n_pairs = len(pair_graph)
    both = sum(1 for v in pair_graph.values() if v['forward'] and v['reverse'])
    fwd = sum(1 for v in pair_graph.values() if v['forward'] and not v['reverse'])
    rev = sum(1 for v in pair_graph.values() if not v['forward'] and v['reverse'])
    print(f"  {n_pairs} pairs: fwd_only={fwd} ({100*fwd/n_pairs:.1f}%), "
          f"rev_only={rev} ({100*rev/n_pairs:.1f}%), both={both} ({100*both/n_pairs:.1f}%)")

    # === Extract degree-3 orderings ===
    print("\n=== STEP 2: Extract degree-3 orderings (delay-based matching) ===")
    triple_orderings = {}
    resolved = 0
    failed = 0

    for row in triple_rides:
        ri = sorted(parse_pipe_list(row['requestIndices'], int))
        delays = parse_pipe_list(row['delays'], float)
        start = float(row['startTime'])
        dests_links = parse_pipe_list(row['destinationsOrdered'], int)

        origin_order = resolve_ordering(ri, req_times, delays, start)
        if origin_order is None:
            failed += 1
            continue

        dest_order = resolve_dest_ordering(ri, req_dest, dests_links)
        if dest_order is None:
            # Try inferring from detours or just store origin only
            # For now, store origin and skip dest
            triple_orderings[tuple(ri)] = (tuple(origin_order), None)
            resolved += 1
            continue

        triple_orderings[tuple(ri)] = (tuple(origin_order), tuple(dest_order))
        resolved += 1

    has_dest = sum(1 for v in triple_orderings.values() if v[1] is not None)
    print(f"  Resolved: {resolved}, Failed: {failed} ({100*failed/len(triple_rides):.1f}%)")
    print(f"  With dest ordering: {has_dest} ({100*has_dest/resolved:.1f}% of resolved)")

    # === Extract degree-4 orderings ===
    print("\n=== STEP 3: Extract degree-4 orderings ===")
    quad_orderings = {}
    resolved4 = 0
    failed4 = 0

    for row in quad_rides:
        ri = sorted(parse_pipe_list(row['requestIndices'], int))
        delays = parse_pipe_list(row['delays'], float)
        start = float(row['startTime'])
        dests_links = parse_pipe_list(row['destinationsOrdered'], int)

        origin_order = resolve_ordering(ri, req_times, delays, start)
        if origin_order is None:
            failed4 += 1; continue

        dest_order = resolve_dest_ordering(ri, req_dest, dests_links)
        quad_orderings[tuple(ri)] = (tuple(origin_order), dest_order and tuple(dest_order))
        resolved4 += 1

    has_dest4 = sum(1 for v in quad_orderings.values() if v[1] is not None)
    print(f"  Resolved: {resolved4}, Failed: {failed4}")
    print(f"  With dest ordering: {has_dest4}")

    # === Degree-4 ordering analysis ===
    print("\n=== STEP 4: Degree-4 ordering reduction (origin + destination) ===")
    sample_size = min(20000, len(quad_orderings))
    quad_keys = list(quad_orderings.keys())
    random.shuffle(quad_keys)

    results4 = []
    skip_triple = 0
    skip_pair = 0

    for key in quad_keys:
        if len(results4) >= sample_size:
            break

        rs = list(key)
        n = len(rs)

        # Need all sub-triples
        all_present = True
        for skip in range(n):
            sub = tuple(sorted(rs[k] for k in range(n) if k != skip))
            if sub not in triple_orderings:
                all_present = False; break
        if not all_present:
            skip_triple += 1; continue

        pg = get_pairwise_origin_constraints(rs, pair_graph)
        if pg is None:
            skip_pair += 1; continue

        # Origin orderings: pair graph only vs + sub-triple constraints
        orig_pg = count_valid_orderings(n, pg)
        orig_tight, ostats = add_subset_origin_constraints(rs, pg, triple_orderings)
        orig_with_sub = count_valid_orderings(n, orig_tight)

        # Destination orderings: for each valid origin ordering, count dest orderings
        # with pair-graph-only vs + sub-triple constraints
        dest_pg_total = 0
        dest_tight_total = 0

        # Check the stored ordering for this quad
        stored = quad_orderings[key]
        stored_origin = stored[0] if stored else None

        # Sample a few valid origin orderings (or all if few)
        valid_origins = []
        for perm in permutations(range(n)):
            pos = {v: i for i, v in enumerate(perm)}
            if all(pos[i] < pos[j] for i, j in orig_tight):
                origin_order = [rs[perm[p]] for p in range(n)]
                valid_origins.append(origin_order)

        for origin_order in valid_origins[:6]:  # cap at 6 to keep fast
            # Dest constraints from pair graph
            dc_pg = add_subset_dest_constraints(rs, pair_graph, origin_order, {})
            dest_pg = count_valid_orderings(n, dc_pg)

            # Dest constraints from pair graph + sub-triple orderings
            dc_tight = add_subset_dest_constraints(rs, pair_graph, origin_order, triple_orderings)
            dest_tight = count_valid_orderings(n, dc_tight)

            dest_pg_total += dest_pg
            dest_tight_total += dest_tight

        # Total orderings = sum over valid origins of dest orderings per origin
        total_pg = 0
        for perm in permutations(range(n)):
            pos = {v: i for i, v in enumerate(perm)}
            if all(pos[i] < pos[j] for i, j in pg):
                origin_order = [rs[perm[p]] for p in range(n)]
                dc = add_subset_dest_constraints(rs, pair_graph, origin_order, {})
                total_pg += count_valid_orderings(n, dc)

        total_tight = 0
        for origin_order in valid_origins:
            dc = add_subset_dest_constraints(rs, pair_graph, origin_order, triple_orderings)
            total_tight += count_valid_orderings(n, dc)

        results4.append({
            'orig_pg': orig_pg, 'orig_sub': orig_with_sub,
            'total_pg': total_pg, 'total_sub': total_tight,
            'flex': ostats['flexible'], 'tight': ostats['tightened'], 'conflict': ostats['conflicting'],
        })

        if len(results4) % 2000 == 0:
            print(f"  ... {len(results4)} analyzed")

    print(f"  Analyzed: {len(results4)}, Skipped: {skip_triple} (triples), {skip_pair} (pairs)")

    if results4:
        ao = sum(r['orig_pg'] for r in results4) / len(results4)
        as_ = sum(r['orig_sub'] for r in results4) / len(results4)
        tp = sum(r['total_pg'] for r in results4) / len(results4)
        ts = sum(r['total_sub'] for r in results4) / len(results4)

        print(f"\n  Degree 4 — ORIGIN orderings:")
        print(f"    Pair graph only:        {ao:.2f}")
        print(f"    + sub-triple:           {as_:.2f}  ({100*(1-as_/ao):.1f}% reduction)")
        print(f"  Degree 4 — TOTAL orderings (origin × dest):")
        print(f"    Pair graph only:        {tp:.2f}")
        print(f"    + sub-triple:           {ts:.2f}  ({100*(1-ts/tp):.1f}% reduction)")
        print(f"  Avg flexible pairs: {sum(r['flex'] for r in results4)/len(results4):.2f}")
        print(f"  Avg tightened:      {sum(r['tight'] for r in results4)/len(results4):.2f}")
        print(f"  Avg conflicting:    {sum(r['conflict'] for r in results4)/len(results4):.2f}")

        # Distribution of total orderings
        print(f"\n  Total ordering distribution (pair graph only):")
        dist = defaultdict(int)
        for r in results4: dist[r['total_pg']] += 1
        for k in sorted(dist.keys())[:12]:
            print(f"    {k}: {dist[k]} ({100*dist[k]/len(results4):.1f}%)")

        print(f"\n  Total ordering distribution (+ sub-triple):")
        dist2 = defaultdict(int)
        for r in results4: dist2[r['total_sub']] += 1
        for k in sorted(dist2.keys())[:12]:
            print(f"    {k}: {dist2[k]} ({100*dist2[k]/len(results4):.1f}%)")

    # === Degree-5 ordering analysis ===
    print("\n=== STEP 5: Degree-5 ordering reduction ===")
    quint_parsed = []
    for row in quint_rides:
        quint_parsed.append(tuple(sorted(parse_pipe_list(row['requestIndices'], int))))
    random.shuffle(quint_parsed)

    results5 = []
    skip5q = 0
    skip5p = 0
    sample5 = min(5000, len(quint_parsed))

    for key in quint_parsed:
        if len(results5) >= sample5:
            break
        rs = list(key)
        n = len(rs)

        all_present = True
        for skip in range(n):
            sub = tuple(sorted(rs[k] for k in range(n) if k != skip))
            if sub not in quad_orderings:
                all_present = False; break
        if not all_present:
            skip5q += 1; continue

        pg = get_pairwise_origin_constraints(rs, pair_graph)
        if pg is None:
            skip5p += 1; continue

        orig_pg = count_valid_orderings(n, pg)
        orig_tight, ostats = add_subset_origin_constraints(rs, pg, quad_orderings)
        orig_sub = count_valid_orderings(n, orig_tight)

        # Total orderings (origin × dest) — only sample a few for speed
        total_pg = 0
        count_pg = 0
        for perm in permutations(range(n)):
            pos = {v: i for i, v in enumerate(perm)}
            if all(pos[i] < pos[j] for i, j in pg):
                origin_order = [rs[perm[p]] for p in range(n)]
                dc = add_subset_dest_constraints(rs, pair_graph, origin_order, {})
                total_pg += count_valid_orderings(n, dc)
                count_pg += 1
                if count_pg >= 30:  # Cap for speed
                    # Extrapolate
                    total_pg = int(total_pg * orig_pg / count_pg)
                    break

        total_sub = 0
        valid_origins = []
        for perm in permutations(range(n)):
            pos = {v: i for i, v in enumerate(perm)}
            if all(pos[i] < pos[j] for i, j in orig_tight):
                valid_origins.append([rs[perm[p]] for p in range(n)])

        for oo in valid_origins[:30]:
            dc = add_subset_dest_constraints(rs, pair_graph, oo, quad_orderings)
            total_sub += count_valid_orderings(n, dc)
        if len(valid_origins) > 30:
            total_sub = int(total_sub * len(valid_origins) / 30)

        results5.append({
            'orig_pg': orig_pg, 'orig_sub': orig_sub,
            'total_pg': total_pg, 'total_sub': total_sub,
            'flex': ostats['flexible'], 'tight': ostats['tightened'], 'conflict': ostats['conflicting'],
        })
        if len(results5) % 500 == 0:
            print(f"  ... {len(results5)} analyzed")

    print(f"  Analyzed: {len(results5)}, Skipped: {skip5q} (quads), {skip5p} (pairs)")

    if results5:
        ao5 = sum(r['orig_pg'] for r in results5) / len(results5)
        as5 = sum(r['orig_sub'] for r in results5) / len(results5)
        tp5 = sum(r['total_pg'] for r in results5) / len(results5)
        ts5 = sum(r['total_sub'] for r in results5) / len(results5)

        print(f"\n  Degree 5 — ORIGIN orderings:")
        print(f"    Pair graph only:       {ao5:.2f}")
        print(f"    + sub-quad:            {as5:.2f}  ({100*(1-as5/ao5):.1f}% reduction)")
        print(f"  Degree 5 — TOTAL orderings (origin × dest):")
        print(f"    Pair graph only:       {tp5:.2f}")
        print(f"    + sub-quad:            {ts5:.2f}  ({100*(1-ts5/tp5):.1f}% reduction)")

    # === Summary ===
    print("\n" + "=" * 70)
    print("COMBINED EFFECT ESTIMATION")
    print("=" * 70)
    print("\nCandidate reduction (measured from Java instrumentation):")
    print("  Degree 4: 10.73M → 1.94M (82% reduction)")
    print("  Degree 5: 26.59M → 3.09M (88% reduction)")

    if results4:
        print(f"\nOrdering reduction per candidate (origin × dest, measured from rides):")
        print(f"  Degree 4: {tp:.1f} → {ts:.1f} ({100*(1-ts/tp):.1f}% reduction)")
    if results5:
        print(f"  Degree 5: {tp5:.1f} → {ts5:.1f} ({100*(1-ts5/tp5):.1f}% reduction)")

    if results4 and results5:
        # From profiling: actual orderings/set (including B&B + travel time pruning)
        actual_4 = 7.7
        actual_5 = 58.5

        # The pair-graph-only total orderings from our analysis ≈ theoretical max without B&B
        # Ratio: actual / theoretical ≈ effect of B&B + travel time pruning
        ratio4 = actual_4 / tp if tp > 0 else 1
        ratio5 = actual_5 / tp5 if tp5 > 0 else 1

        print(f"\n  B&B + travel time pruning effect (actual/theoretical):")
        print(f"    Degree 4: {actual_4:.1f} / {tp:.1f} = {ratio4:.2f}")
        print(f"    Degree 5: {actual_5:.1f} / {tp5:.1f} = {ratio5:.2f}")

        # Full approach: sub-set constraints (no B&B) vs current (pair graph + B&B)
        est4 = ts  # with sub-set constraints, no B&B
        est5 = ts5

        print(f"\n  FULL APPROACH orderings/set (sub-set constraints, no distance B&B):")
        print(f"    Degree 4: {est4:.1f} (vs current {actual_4:.1f})")
        print(f"    Degree 5: {est5:.1f} (vs current {actual_5:.1f})")

        print(f"\n  COMBINED: candidates × orderings/set:")
        curr4 = 10_730_328 * actual_4
        prop4 = 1_940_953 * est4
        curr5 = 26_587_744 * actual_5
        prop5 = 3_085_700 * est5
        print(f"    Degree 4: {curr4/1e6:.0f}M → {prop4/1e6:.0f}M ({100*(1-prop4/curr4):.1f}% reduction)")
        print(f"    Degree 5: {curr5/1e6:.0f}M → {prop5/1e6:.0f}M ({100*(1-prop5/curr5):.1f}% reduction)")

        # Time estimates
        # Current cost per ordering ≈ time / orderings
        # Degree 5: 410s / 1.55B orderings ≈ 0.264 μs/ordering
        cost_per_ordering_us = 410 / 1555e6 * 1e6
        print(f"\n  Time estimates (at {cost_per_ordering_us:.3f} μs/ordering):")
        print(f"    Degree 4 current: {curr4 * cost_per_ordering_us / 1e6:.1f}s")
        print(f"    Degree 4 proposed: {prop4 * cost_per_ordering_us / 1e6:.1f}s")
        print(f"    Degree 5 current: {curr5 * cost_per_ordering_us / 1e6:.1f}s")
        print(f"    Degree 5 proposed: {prop5 * cost_per_ordering_us / 1e6:.1f}s")

        # Cost of finding all orderings at degree 3 (to build the graph)
        # Currently: 32s for 7.05M candidates × 0.6 orderings
        # Without B&B: need all valid orderings per feasible set
        # Estimate: ~1.5x more orderings evaluated at degree 3
        print(f"\n  Degree 3 overhead (find all orderings, no B&B): ~35-40s (vs 32s current)")

    print(f"\nTotal analysis time: {time.time()-t0:.1f}s")


if __name__ == '__main__':
    main()
