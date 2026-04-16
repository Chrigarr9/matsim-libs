"""Compare mode_cache.csv files from planCalcScore vs DMC scoring adapters."""
import csv
import statistics
from collections import defaultdict

def read_cache(path):
    data = {}
    with open(path, "r") as f:
        reader = csv.DictReader(f)
        for row in reader:
            key = (row["personId"], int(row["tripIndex"]), row["mode"])
            data[key] = {
                "travelTime": float(row["travelTime"]),
                "distance": float(row["distance"]),
                "score": float(row["score"]),
            }
    return data

pcs_path = "matsim_scenarios/matsim-kelheim/output/scoring-validation-planCalcScore/drt_demand/kelheim-1pct-planCalcScore.mode_cache.csv"
dmc_path = "matsim_scenarios/matsim-kelheim/output/scoring-validation-dmc/drt_demand/kelheim-1pct-dmc.mode_cache.csv"

pcs = read_cache(pcs_path)
dmc = read_cache(dmc_path)

print(f"planCalcScore entries: {len(pcs)}")
print(f"DMC entries: {len(dmc)}")

# Keys in common
common = set(pcs.keys()) & set(dmc.keys())
only_pcs = set(pcs.keys()) - set(dmc.keys())
only_dmc = set(dmc.keys()) - set(pcs.keys())
print(f"\nCommon tuples: {len(common)}")
print(f"Only in planCalcScore: {len(only_pcs)}")
print(f"Only in DMC: {len(only_dmc)}")

if only_pcs:
    print(f"  Examples only in PCS: {list(only_pcs)[:5]}")
if only_dmc:
    print(f"  Examples only in DMC: {list(only_dmc)[:5]}")

# ============================================================
# 1. Check travel time and distance identity
# ============================================================
print("\n" + "=" * 80)
print("TRAVEL TIME AND DISTANCE COMPARISON")
print("=" * 80)

tt_diffs = 0
dist_diffs = 0
tt_diff_by_mode = defaultdict(int)
dist_diff_by_mode = defaultdict(int)

for key in common:
    mode = key[2]
    if abs(pcs[key]["travelTime"] - dmc[key]["travelTime"]) > 0.001:
        tt_diffs += 1
        tt_diff_by_mode[mode] += 1
    if abs(pcs[key]["distance"] - dmc[key]["distance"]) > 0.001:
        dist_diffs += 1
        dist_diff_by_mode[mode] += 1

print(f"Travel time mismatches: {tt_diffs} / {len(common)} ({100*tt_diffs/len(common):.2f}%)")
print(f"Distance mismatches: {dist_diffs} / {len(common)} ({100*dist_diffs/len(common):.2f}%)")
if tt_diff_by_mode:
    print(f"  TT mismatches by mode: {dict(tt_diff_by_mode)}")
if dist_diff_by_mode:
    print(f"  Dist mismatches by mode: {dict(dist_diff_by_mode)}")

# ============================================================
# 2. Score differences
# ============================================================
print("\n" + "=" * 80)
print("SCORE DIFFERENCES (DMC - planCalcScore)")
print("=" * 80)

diffs_by_mode = defaultdict(list)
all_diffs = []

for key in common:
    mode = key[2]
    diff = dmc[key]["score"] - pcs[key]["score"]
    diffs_by_mode[mode].append((key, diff, pcs[key], dmc[key]))
    all_diffs.append((key, diff))

# Overall stats
total = len(all_diffs)
identical = sum(1 for _, d in all_diffs if abs(d) < 1e-6)
small_diff = sum(1 for _, d in all_diffs if 1e-6 <= abs(d) < 0.01)
medium_diff = sum(1 for _, d in all_diffs if 0.01 <= abs(d) < 0.1)
large_diff = sum(1 for _, d in all_diffs if abs(d) >= 0.1)

print(f"\nOverall: {total} tuples compared")
print(f"  Identical (|diff| < 1e-6):     {identical:6d} ({100*identical/total:.1f}%)")
print(f"  Small (1e-6 <= |diff| < 0.01): {small_diff:6d} ({100*small_diff/total:.1f}%)")
print(f"  Medium (0.01 <= |diff| < 0.1): {medium_diff:6d} ({100*medium_diff/total:.1f}%)")
print(f"  Large (|diff| >= 0.1):         {large_diff:6d} ({100*large_diff/total:.1f}%)")

# Per-mode breakdown
print("\n" + "-" * 80)
print("PER-MODE BREAKDOWN")
print("-" * 80)

for mode in sorted(diffs_by_mode.keys()):
    entries = diffs_by_mode[mode]
    n = len(entries)
    diffs = [d for _, d, _, _ in entries]
    identical_m = sum(1 for d in diffs if abs(d) < 1e-6)
    nonzero = [d for d in diffs if abs(d) >= 1e-6]

    print(f"\n  MODE: {mode}")
    print(f"    Count: {n}")
    print(f"    Identical: {identical_m} ({100*identical_m/n:.1f}%)")
    print(f"    Different: {n - identical_m} ({100*(n-identical_m)/n:.1f}%)")

    if nonzero:
        print(f"    Among non-identical:")
        print(f"      Mean diff: {statistics.mean(nonzero):+.6f}")
        print(f"      Median diff: {statistics.median(nonzero):+.6f}")
        print(f"      Min diff: {min(nonzero):+.6f}")
        print(f"      Max diff: {max(nonzero):+.6f}")
        if len(nonzero) > 1:
            print(f"      Stdev: {statistics.stdev(nonzero):.6f}")

        # Distribution of diff signs
        pos = sum(1 for d in nonzero if d > 0)
        neg = sum(1 for d in nonzero if d < 0)
        print(f"      DMC > PCS (positive): {pos}")
        print(f"      DMC < PCS (negative): {neg}")

# ============================================================
# 3. Detailed examples for modes with largest differences
# ============================================================
print("\n" + "=" * 80)
print("DETAILED EXAMPLES - MODES WITH DIFFERENCES")
print("=" * 80)

for mode in sorted(diffs_by_mode.keys()):
    entries = diffs_by_mode[mode]
    nonzero_entries = [(k, d, p, dm) for k, d, p, dm in entries if abs(d) >= 1e-6]

    if not nonzero_entries:
        continue

    # Sort by absolute diff descending
    nonzero_entries.sort(key=lambda x: abs(x[1]), reverse=True)

    print(f"\n  MODE: {mode} - Top 10 largest absolute differences:")
    print(
        f"  {'personId':>10} {'trip':>4} {'PCS_score':>12} {'DMC_score':>12} "
        f"{'diff':>12} {'PCS_tt':>10} {'DMC_tt':>10} {'PCS_dist':>10} {'DMC_dist':>10}"
    )
    for k, d, p, dm in nonzero_entries[:10]:
        print(
            f"  {k[0]:>10} {k[1]:>4} {p['score']:>12.4f} {dm['score']:>12.4f} "
            f"{d:>+12.4f} {p['travelTime']:>10.1f} {dm['travelTime']:>10.1f} "
            f"{p['distance']:>10.1f} {dm['distance']:>10.1f}"
        )

# ============================================================
# 4. Deep dive on DRT mode differences
# ============================================================
print("\n" + "=" * 80)
print("DRT MODE - DETAILED ANALYSIS")
print("=" * 80)

drt_entries = diffs_by_mode.get("drt", [])
if drt_entries:
    nonzero_drt = [(k, d, p, dm) for k, d, p, dm in drt_entries if abs(d) >= 1e-6]

    print(f"\n  Total DRT entries: {len(drt_entries)}")
    print(f"  With differences: {len(nonzero_drt)}")

    if nonzero_drt:
        # Check the pattern: is the diff correlated with travel time?
        nonzero_drt.sort(key=lambda x: x[2]["travelTime"])
        print(f"\n  Diff / travelTime relationship (sampled):")
        step = max(1, len(nonzero_drt) // 20)
        for i in range(0, len(nonzero_drt), step):
            k, d, p, dm = nonzero_drt[i]
            tt = p["travelTime"]
            dist = p["distance"]
            # Check if diff is proportional to something
            ratio_tt = d / (tt / 3600) if tt > 0 else 0
            print(
                f"    person={k[0]}, trip={k[1]}, tt={tt:.0f}s, dist={dist:.0f}m, "
                f"PCS={p['score']:.4f}, DMC={dm['score']:.4f}, diff={d:+.4f}, "
                f"diff/(tt_hr)={ratio_tt:+.4f}"
            )

        # Check if there is a constant marginal utility difference
        print(f"\n  Checking if diff = constant * travelTime:")
        ratios = []
        for k, d, p, dm in nonzero_drt:
            tt = p["travelTime"]
            if tt > 0:
                ratios.append(d / (tt / 3600.0))
        if ratios:
            print(f"    diff/tt_hr: mean={statistics.mean(ratios):+.6f}, "
                  f"stdev={statistics.stdev(ratios):.6f}, "
                  f"min={min(ratios):+.6f}, max={max(ratios):+.6f}")

        # Also check diff vs distance
        print(f"\n  Checking if diff = constant * distance:")
        dist_ratios = []
        for k, d, p, dm in nonzero_drt:
            dist = p["distance"]
            if dist > 0:
                dist_ratios.append(d / (dist / 1000.0))
        if dist_ratios:
            print(f"    diff/dist_km: mean={statistics.mean(dist_ratios):+.6f}, "
                  f"stdev={statistics.stdev(dist_ratios):.6f}, "
                  f"min={min(dist_ratios):+.6f}, max={max(dist_ratios):+.6f}")

# ============================================================
# 5. PT mode deep dive
# ============================================================
print("\n" + "=" * 80)
print("PT MODE - DETAILED ANALYSIS")
print("=" * 80)

pt_entries = diffs_by_mode.get("pt", [])
if pt_entries:
    nonzero_pt = [(k, d, p, dm) for k, d, p, dm in pt_entries if abs(d) >= 1e-6]

    print(f"\n  Total PT entries: {len(pt_entries)}")
    print(f"  With differences: {len(nonzero_pt)}")

    if nonzero_pt:
        # Check ratio to travel time
        nonzero_pt.sort(key=lambda x: abs(x[1]), reverse=True)
        print(f"\n  Top 20 PT differences:")
        print(
            f"  {'personId':>10} {'trip':>4} {'PCS_score':>12} {'DMC_score':>12} "
            f"{'diff':>12} {'tt':>10} {'dist':>10}"
        )
        for k, d, p, dm in nonzero_pt[:20]:
            print(
                f"  {k[0]:>10} {k[1]:>4} {p['score']:>12.4f} {dm['score']:>12.4f} "
                f"{d:>+12.4f} {p['travelTime']:>10.1f} {p['distance']:>10.1f}"
            )

        # Check if diff is proportional to travel time
        print(f"\n  Checking if diff = constant * travelTime:")
        ratios = []
        for k, d, p, dm in nonzero_pt:
            tt = p["travelTime"]
            if tt > 0:
                ratios.append(d / (tt / 3600.0))
        if ratios:
            print(f"    diff/tt_hr: mean={statistics.mean(ratios):+.6f}, "
                  f"stdev={statistics.stdev(ratios):.6f}, "
                  f"min={min(ratios):+.6f}, max={max(ratios):+.6f}")

# ============================================================
# 6. Bike and Walk deep dive
# ============================================================
print("\n" + "=" * 80)
print("BIKE AND WALK MODES")
print("=" * 80)

for mode in ["bike", "walk"]:
    entries = diffs_by_mode.get(mode, [])
    nonzero = [(k, d, p, dm) for k, d, p, dm in entries if abs(d) >= 1e-6]
    print(f"\n  MODE: {mode}")
    print(f"    Total: {len(entries)}, Different: {len(nonzero)}")
    if nonzero:
        for k, d, p, dm in nonzero[:5]:
            print(
                f"    person={k[0]}, trip={k[1]}, PCS={p['score']:.4f}, "
                f"DMC={dm['score']:.4f}, diff={d:+.4f}"
            )

# ============================================================
# 7. Car mode check
# ============================================================
print("\n" + "=" * 80)
print("CAR MODE")
print("=" * 80)

car_entries = diffs_by_mode.get("car", [])
if car_entries:
    nonzero_car = [(k, d, p, dm) for k, d, p, dm in car_entries if abs(d) >= 1e-6]
    print(f"  Total: {len(car_entries)}, Different: {len(nonzero_car)}")
else:
    print("  No car entries found in common tuples.")
    # Check if car exists at all
    pcs_modes = set(k[2] for k in pcs.keys())
    dmc_modes = set(k[2] for k in dmc.keys())
    print(f"  Modes in PCS: {pcs_modes}")
    print(f"  Modes in DMC: {dmc_modes}")

# ============================================================
# 8. Summary
# ============================================================
print("\n" + "=" * 80)
print("SUMMARY")
print("=" * 80)

all_abs_diffs = [abs(d) for _, d in all_diffs]
print(f"\nTotal tuples compared: {total}")
print(f"Fraction with identical scores: {identical}/{total} = {100*identical/total:.1f}%")
print(f"Fraction with different scores: {total-identical}/{total} = {100*(total-identical)/total:.1f}%")
print(f"Max absolute difference: {max(all_abs_diffs):.6f}")
print(f"Mean absolute difference (all): {sum(all_abs_diffs)/len(all_abs_diffs):.6f}")
nonzero_abs = [d for d in all_abs_diffs if d >= 1e-6]
if nonzero_abs:
    print(f"Mean absolute difference (non-zero only): {sum(nonzero_abs)/len(nonzero_abs):.6f}")

print(f"\nPer-mode summary:")
for mode in sorted(diffs_by_mode.keys()):
    entries = diffs_by_mode[mode]
    n = len(entries)
    diffs = [d for _, d, _, _ in entries]
    identical_m = sum(1 for d in diffs if abs(d) < 1e-6)
    abs_diffs_m = [abs(d) for d in diffs]
    max_d = max(abs_diffs_m)
    mean_d = sum(abs_diffs_m) / len(abs_diffs_m)
    print(
        f"  {mode:>6}: {n:5d} tuples, {identical_m:5d} identical ({100*identical_m/n:5.1f}%), "
        f"max |diff|={max_d:.6f}, mean |diff|={mean_d:.6f}"
    )
