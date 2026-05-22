import pandas as pd
import numpy as np
import math
import time
import os

def parse_link_list(cell):
    s = str(cell).strip()
    if s.startswith('['):
        s = s[1:]
    if s.endswith(']'):
        s = s[:-1]
    parts = [p.strip() for p in s.split('|')]
    return [p for p in parts if p]

def analyze_dataset(name, rides_path, cache_path):
    print()
    print('=' * 70)
    print(f'  Analyzing: {name}')
    print('=' * 70)
    t0 = time.time()
    rides = pd.read_csv(rides_path)
    t_load = time.time() - t0
    print(f'Loaded {len(rides):,} rides in {t_load:.1f}s')
    print(f'  Degree distribution: {rides["degree"].value_counts().sort_index().to_dict()}')
    first_origins = set()
    last_dests = set()
    for _, row in rides.iterrows():
        origins = parse_link_list(row["originsOrdered"])
        dests = parse_link_list(row["destinationsOrdered"])
        if origins:
            first_origins.add(origins[0])
        if dests:
            last_dests.add(dests[-1])
    n_first_origins = len(first_origins)
    n_last_dests = len(last_dests)
    all_endpoint_links = first_origins | last_dests
    n_union = len(all_endpoint_links)
    print(f'  Unique first-origin links:  {n_first_origins:,}')
    print(f'  Unique last-dest links:     {n_last_dests:,}')
    print(f'  Union of endpoint links:    {n_union:,}')
    print(f'  Overlap:                    {n_first_origins + n_last_dests - n_union:,}')
    n_pairs = n_last_dests * n_first_origins
    print(f'  Max unique (last_dest -> first_origin) pairs: {n_last_dests:,} x {n_first_origins:,} = {n_pairs:,}')
    t0 = time.time()
    cache = pd.read_csv(cache_path)
    t_load_cache = time.time() - t0
    print(f'  Loaded connection cache: {len(cache):,} rows in {t_load_cache:.1f}s')
    print(f'  Cache columns: {cache.columns.tolist()}')
    time_bins = sorted(cache["time_bin"].unique())
    n_time_bins = len(time_bins)
    print(f'  Time bins: {n_time_bins} bins: {time_bins}')
    cache_pairs = cache.groupby(["origin", "destination"]).size().reset_index(name="count")
    n_cached_pairs = len(cache_pairs)
    print(f'  Unique (origin, destination) pairs in cache: {n_cached_pairs:,}')
    cache_origins = set(cache["origin"].unique())
    cache_dests = set(cache["destination"].unique())
    print(f'  Unique origins in cache: {len(cache_origins):,}')
    print(f'  Unique destinations in cache: {len(cache_dests):,}')
    last_dests_in_cache_origins = last_dests & cache_origins
    first_origins_in_cache_dests = first_origins & cache_dests
    print(f'  Last-dest links found as cache origins: {len(last_dests_in_cache_origins):,} / {n_last_dests:,}')
    print(f'  First-origin links found as cache dests: {len(first_origins_in_cache_dests):,} / {n_first_origins:,}')
    cached_od_set = set(zip(cache_pairs["origin"], cache_pairs["destination"]))
    already_cached = 0
    for ld in last_dests:
        for fo in first_origins:
            if (ld, fo) in cached_od_set:
                already_cached += 1
    missing_pairs = n_pairs - already_cached
    print(f'  Cross-product pairs already in cache: {already_cached:,}')
    print(f'  Missing pairs (need routing):         {missing_pairs:,}')
    if n_pairs > 0:
        print(f'  Cache hit rate:                       {already_cached/n_pairs*100:.1f}%')
    total_routes_needed = n_pairs * n_time_bins
    total_routes_missing = missing_pairs * n_time_bins
    print(f'  Total routes needed (pairs x time_bins): {n_pairs:,} x {n_time_bins} = {total_routes_needed:,}')
    print(f'  Total routes missing (to compute):       {missing_pairs:,} x {n_time_bins} = {total_routes_missing:,}')
    for ms_per_route in [0.5, 1.0, 2.0]:
        time_s = total_routes_missing * ms_per_route / 1000
        time_min = time_s / 60
        time_hr = time_min / 60
        if time_hr >= 1:
            print(f'  At {ms_per_route}ms/route: {time_hr:.1f} hours ({time_min:.0f} min)')
        elif time_min >= 1:
            print(f'  At {ms_per_route}ms/route: {time_min:.1f} min ({time_s:.0f} s)')
        else:
            print(f'  At {ms_per_route}ms/route: {time_s:.1f} s')
    return {
        "name": name, "n_rides": len(rides), "n_first_origins": n_first_origins,
        "n_last_dests": n_last_dests, "n_union_links": n_union, "n_pairs": n_pairs,
        "n_time_bins": n_time_bins, "total_routes_needed": total_routes_needed,
        "n_cached_pairs": n_cached_pairs, "already_cached_endpoint_pairs": already_cached,
        "missing_pairs": missing_pairs, "total_routes_missing": total_routes_missing,
        "total_cache_rows": len(cache),
    }

base = r"C:\Users\VWAUCCY\dev\msf\projects\Dissertation\matsim_scenarios\matsim-kelheim\output"
datasets = {
    "1pct": {
        "rides": os.path.join(base, "kelheim-demand-extraction-1pct", "drt_demand", "kelheim-1pct-exmas.exmas_rides.csv"),
        "cache": os.path.join(base, "kelheim-demand-extraction-1pct", "drt_demand", "kelheim-1pct-exmas.connection_cache.csv"),
    },
    "10pct": {
        "rides": os.path.join(base, "kelheim-demand-extraction-10pct", "drt_demand", "kelheim-10pct-exmas.exmas_rides.csv"),
        "cache": os.path.join(base, "kelheim-demand-extraction-10pct", "drt_demand", "kelheim-10pct-exmas.connection_cache.csv"),
    },
}
results = {}
for name, paths in datasets.items():
    results[name] = analyze_dataset(name, paths["rides"], paths["cache"])
r1 = results["1pct"]
r10 = results["10pct"]

print()
print('=' * 70)
print("  SCALING ANALYSIS & 100%% PROJECTION")
print('=' * 70)
rides_ratio = r10["n_rides"] / r1["n_rides"]
links_ratio = r10["n_union_links"] / r1["n_union_links"]
alpha = math.log(links_ratio) / math.log(rides_ratio)
print(f'  Rides ratio (10pct/1pct):  {rides_ratio:.2f}x')
print(f'  Links ratio (10pct/1pct):  {links_ratio:.2f}x')
print(f'  Scaling exponent (alpha):  {alpha:.3f}')
print(f'    (1.0 = linear, 0.5 = sqrt, 0.0 = constant)')
factor_100 = 100.0 / 10.0
projected_rides_100 = r10["n_rides"] * factor_100
alpha_origins = math.log(r10["n_first_origins"] / r1["n_first_origins"]) / math.log(rides_ratio)
alpha_dests = math.log(r10["n_last_dests"] / r1["n_last_dests"]) / math.log(rides_ratio)
projected_origins_100 = r10["n_first_origins"] * (factor_100 ** alpha_origins)
projected_dests_100 = r10["n_last_dests"] * (factor_100 ** alpha_dests)
projected_pairs_100 = projected_origins_100 * projected_dests_100
projected_links_100_alpha = r10["n_union_links"] * (factor_100 ** alpha)
print(f'  Alpha for first-origins: {alpha_origins:.3f}')
print(f'  Alpha for last-dests:   {alpha_dests:.3f}')
print(f'  Projected 100%% unique first-origins: {projected_origins_100:,.0f}')
print(f'  Projected 100%% unique last-dests:    {projected_dests_100:,.0f}')
print(f'  Projected 100%% unique pairs:         {projected_pairs_100:,.0f}')
n_time_bins = r10["n_time_bins"]
projected_total_routes_100 = projected_pairs_100 * n_time_bins
print(f'  Projected 100%% total routes (x {n_time_bins} time bins): {projected_total_routes_100:,.0f}')
for ms_per_route in [0.5, 1.0, 2.0]:
    time_s = projected_total_routes_100 * ms_per_route / 1000
    time_hr = time_s / 3600
    time_days = time_hr / 24
    if time_days >= 1:
        print(f'  At {ms_per_route}ms/route: {time_days:.1f} days ({time_hr:.0f} hours)')
    elif time_hr >= 1:
        print(f'  At {ms_per_route}ms/route: {time_hr:.1f} hours')
    else:
        print(f'  At {ms_per_route}ms/route: {time_s/60:.1f} min')

print()
print('=' * 90)
print("  COMPARISON TABLE: Option 2 Pre-Routing Scaling")
print('=' * 90)

def ratio_str(v10, v1):
    if v1 == 0: return "N/A"
    return f"{v10/v1:.2f}x"

def proj_str(v):
    if v >= 1e9: return f"{v/1e9:.1f}B"
    elif v >= 1e6: return f"{v/1e6:.1f}M"
    elif v >= 1e3: return f"{v/1e3:.1f}K"
    else: return f"{v:.0f}"

def time_str(n_routes, ms=1.0):
    s = n_routes * ms / 1000
    if s < 60: return f"{s:.0f}s"
    elif s < 3600: return f"{s/60:.1f}min"
    elif s < 86400: return f"{s/3600:.1f}hr"
    else: return f"{s/86400:.1f}days"

headers = ["Metric", "1pct", "10pct", "Ratio", "Projected 100pct"]
rows = [
    ("Rides", f"{r1['n_rides']:,}", f"{r10['n_rides']:,}", ratio_str(r10["n_rides"], r1["n_rides"]), f"{projected_rides_100:,.0f}"),
    ("Unique first-origin links", f"{r1['n_first_origins']:,}", f"{r10['n_first_origins']:,}", ratio_str(r10["n_first_origins"], r1["n_first_origins"]), f"{projected_origins_100:,.0f}"),
    ("Unique last-dest links", f"{r1['n_last_dests']:,}", f"{r10['n_last_dests']:,}", ratio_str(r10["n_last_dests"], r1["n_last_dests"]), f"{projected_dests_100:,.0f}"),
    ("Union of endpoint links", f"{r1['n_union_links']:,}", f"{r10['n_union_links']:,}", ratio_str(r10["n_union_links"], r1["n_union_links"]), f"{projected_links_100_alpha:,.0f}"),
    ("Unique link pairs (cross-product)", f"{r1['n_pairs']:,}", f"{r10['n_pairs']:,}", ratio_str(r10["n_pairs"], r1["n_pairs"]), f"{projected_pairs_100:,.0f}"),
    ("Time bins", f"{r1['n_time_bins']}", f"{r10['n_time_bins']}", "-", f"{n_time_bins}"),
    ("Total routes (pairs x bins)", f"{r1['total_routes_needed']:,}", f"{r10['total_routes_needed']:,}", ratio_str(r10["total_routes_needed"], r1["total_routes_needed"]), f"{projected_total_routes_100:,.0f}"),
    ("Already cached pairs", f"{r1['already_cached_endpoint_pairs']:,}", f"{r10['already_cached_endpoint_pairs']:,}", ratio_str(r10["already_cached_endpoint_pairs"], r1["already_cached_endpoint_pairs"]) if r1["already_cached_endpoint_pairs"] > 0 else "N/A", "-"),
    ("Missing pairs", f"{r1['missing_pairs']:,}", f"{r10['missing_pairs']:,}", ratio_str(r10["missing_pairs"], r1["missing_pairs"]), f"~{proj_str(projected_pairs_100)}"),
    ("Missing routes (x bins)", f"{r1['total_routes_missing']:,}", f"{r10['total_routes_missing']:,}", ratio_str(r10["total_routes_missing"], r1["total_routes_missing"]), f"~{proj_str(projected_total_routes_100)}"),
    ("Est. time @0.5ms/route", time_str(r1["total_routes_missing"], 0.5), time_str(r10["total_routes_missing"], 0.5), "-", time_str(projected_total_routes_100, 0.5)),
    ("Est. time @1ms/route", time_str(r1["total_routes_missing"]), time_str(r10["total_routes_missing"]), ratio_str(r10["total_routes_missing"], r1["total_routes_missing"]), time_str(projected_total_routes_100)),
    ("Est. time @2ms/route", time_str(r1["total_routes_missing"], 2.0), time_str(r10["total_routes_missing"], 2.0), "-", time_str(projected_total_routes_100, 2.0)),
]

col_widths = [max(len(h), max(len(r[i]) for r in rows)) + 2 for i, h in enumerate(headers)]
header_line = "| " + " | ".join(h.center(col_widths[i]) for i, h in enumerate(headers)) + " |"
sep_line = "|" + "|".join("-" * (w + 2) for w in col_widths) + "|"
print(header_line)
print(sep_line)
for row in rows:
    line = "| " + " | ".join(str(row[i]).rjust(col_widths[i]) for i in range(len(headers))) + " |"
    print(line)

print()
print('=' * 70)
print("  KEY TAKEAWAYS")
print('=' * 70)

feasibility_10 = "FEASIBLE" if r10["total_routes_missing"] * 0.001 < 3600 else "CHALLENGING"
feasibility_100_s = projected_total_routes_100 * 0.001
if feasibility_100_s < 3600:
    feasibility_100 = "FEASIBLE"
elif feasibility_100_s < 86400:
    feasibility_100 = "CHALLENGING"
else:
    feasibility_100 = "INFEASIBLE for single-threaded; needs parallelization"

print()
print(f"1. SUBLINEAR LINK GROWTH:")
print(f"   - Scaling exponent alpha = {alpha:.3f} (union of links)")
print(f"   - Origins alpha = {alpha_origins:.3f}, Dests alpha = {alpha_dests:.3f}")
print(f"   - Going {rides_ratio:.1f}x in rides only gives ~{links_ratio:.1f}x more unique links")
print(f"   - This is expected: more rides share existing network links")
print()
print(f"2. QUADRATIC PAIR EXPLOSION:")
print(f"   - Unique pairs = |last_dests| x |first_origins| (cross-product)")
print(f'   - 1pct: {r1["n_pairs"]:,} pairs')
print(f'   - 10pct: {r10["n_pairs"]:,} pairs ({r10["n_pairs"]/r1["n_pairs"]:.1f}x)')
print(f"   - Projected 100pct: ~{proj_str(projected_pairs_100)} pairs")
print(f"   - The pair count grows ~quadratically with link count")
print()
print(f"3. TIME BIN MULTIPLIER:")
print(f"   - Each pair must be routed for {n_time_bins} time bins")
print(f"   - This multiplies all numbers by {n_time_bins}x")
print()
print(f"4. FEASIBILITY ASSESSMENT:")
print(f'   - 10pct: {time_str(r10["total_routes_missing"])} at 1ms/route - {feasibility_10}')
print(f'   - 100pct projected: {time_str(projected_total_routes_100)} at 1ms/route - {feasibility_100}')
print()
print(f"5. CACHE EFFECTIVENESS:")
hr1 = r1["already_cached_endpoint_pairs"] / max(r1["n_pairs"], 1) * 100
hr10 = r10["already_cached_endpoint_pairs"] / max(r10["n_pairs"], 1) * 100
print(f"   - 1pct cache hit rate: {hr1:.1f}%%")
print(f"   - 10pct cache hit rate: {hr10:.1f}%%")
print(f"   - Cache covers existing O-D pairs but cross-product generates many NEW pairs")
print(f"     that were never actual ride origins/destinations")
print()
print(f"6. MEMORY ESTIMATE:")
print(f"   - Each cached route: ~50 bytes (origin, dest, time_bin, travel_time, distance)")
print(f'   - 10pct total routes: {r10["total_routes_needed"]:,} x 50B = {r10["total_routes_needed"]*50/1e6:.0f} MB')
print(f'   - 100pct projected: {projected_total_routes_100:,.0f} x 50B = {projected_total_routes_100*50/1e9:.1f} GB')
