package org.matsim.contrib.demand_extraction.algorithm.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntConsumer;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideStore;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.network.TravelSegmentLookup;
import org.matsim.contrib.demand_extraction.algorithm.util.PackedKeyCodec;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

import it.unimi.dsi.fastutil.booleans.BooleanArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;
import it.unimi.dsi.fastutil.ints.Int2DoubleMaps;
import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenCustomHashMap;

/**
 * Post-process ExMAS rides to enrich them with:
 * - maxCosts: maximum fare per passenger before utility equals best alternative
 * - shapleyValues: distance contribution per passenger
 * - predecessors/successors: feasible ride sequencing edges
 */
public final class RidePostProcessor {
    private static final Logger log = LogManager.getLogger(RidePostProcessor.class);

    /**
     * Resolves the maximum acceptable fare (EUR) for one passenger of one ride.
     * Phase 1 wires this from {@code BudgetToConstraintsCalculator.budgetToMaxCost(...)}
     * with a Person lookup; Phase 2 wires it from cost parameters alone (no Population).
     */
    @FunctionalInterface
    public interface MaxCostResolver {
        double maxCost(double budget, DrtRequest request, double travelTime, double distance);
    }

    private final ExMasConfigGroup config;
    private final TravelSegmentLookup networkCache;
    private final MaxCostResolver maxCostResolver;

    /**
     * Real network used by the predecessor/successor spatial pre-filter to look up link
     * coordinates and the network's max free speed. {@code null} when the processor is
     * built from a bare {@link TravelSegmentLookup} stub (tests) — the pre-filter is then a
     * no-op and the pass routes every in-time-window candidate exactly as before.
     */
    private final org.matsim.api.core.v01.network.Network prefilterNetwork;

    /**
     * Deduplicated packed OD/bin keys ({@link PackedKeyCodec#segmentKey}) of every handoff segment
     * the predecessor/successor pass evaluated — accepted AND rejected. This is the Task-9 "window"
     * export domain (= the lookup set of Python's {@code compute_dynamic_successors}).
     *
     * <p>Populated once at the end of {@link #computePredecessors} by merging the per-thread primitive
     * key buffers (see there). The old design appended a <em>boxed</em> {@link Long} per evaluated pair
     * onto a single shared {@link ConcurrentLinkedQueue} — billions of boxed Longs through one
     * contended tail-CAS, the documented OOM/serialization hot spot. Per-thread {@code long[]} buffers
     * merged at the join carry the same keys with no boxing and no cross-thread contention.
     * Duplicates and order are irrelevant — the export sorts deterministically.
     */
    private volatile LongOpenHashSet windowKeys = new LongOpenHashSet();

    /**
     * Production constructor — wires a full {@link MatsimNetworkCache}.
     * Prefer this in runners and Guice modules.
     */
    public RidePostProcessor(ExMasConfigGroup config, MatsimNetworkCache networkCache,
                            MaxCostResolver maxCostResolver) {
        this(config, (TravelSegmentLookup) networkCache, maxCostResolver, networkCache.getNetwork());
    }

    /**
     * Flexible constructor — accepts any {@link TravelSegmentLookup}.
     * Used in tests with lightweight stubs. The spatial pre-filter is disabled (no network).
     */
    public RidePostProcessor(ExMasConfigGroup config, TravelSegmentLookup networkCache,
                            MaxCostResolver maxCostResolver) {
        this(config, networkCache, maxCostResolver, null);
    }

    /**
     * Full constructor — accepts a {@link TravelSegmentLookup} plus the real {@link org.matsim.api.core.v01.network.Network}
     * that backs it. The network powers the predecessor/successor spatial pre-filter (link
     * coordinates + max free speed). Pass {@code null} to disable the pre-filter (stub tests).
     */
    public RidePostProcessor(ExMasConfigGroup config, TravelSegmentLookup networkCache,
                            MaxCostResolver maxCostResolver,
                            org.matsim.api.core.v01.network.Network prefilterNetwork) {
        this.config = config;
        this.networkCache = networkCache;
        this.maxCostResolver = maxCostResolver;
        this.prefilterNetwork = prefilterNetwork;
    }

    public List<Ride> process(RideStore store) {
        if (store == null || store.size() == 0) {
            return new ArrayList<>();
        }
        // Materialize the full ordered list once — leaves the entire body unchanged
        // and guarantees byte-identical output. The memory optimization (stream over
        // stubs without a full list) is deferred to the stub-backing phase.
        List<Ride> rides = new ArrayList<>(store.size());
        store.forEachMaterialized(rides::add);

		log.info("Post-processing {} rides...", rides.size());
		long startTime = System.currentTimeMillis();

        // Barrier eviction BEFORE any cross-ride pass. Enumeration leaves the speculative tier full
        // (at 100% Lyon: 250M segments, retained 0), and none of the passes below re-read those
        // within-ride segments — maxCost and Shapley do no routing at all, and the predecessor pass
        // routes ride-to-ride handoffs, which are different OD pairs. Holding them here is what
        // pushed the 100% degree-8 run into a full-GC death spiral inside computeShapleyValues.
        // Output-invariant: an evicted speculative segment re-routes bit-identically, and the
        // retained tier (the export domain) is untouched. computePredecessors repeats this drop at
        // its own barrier; with nothing repopulating in between that call is simply a no-op.
        if (networkCache instanceof MatsimNetworkCache cache) {
            cache.dropSpeculativeTier();
        }

		log.info("  Computing max costs...");
		long maxCostStart = System.currentTimeMillis();
        // Position-indexed, NOT keyed by ride index: a HashMap<Integer,...> over 29.6M rides costs
        // ~56 B/entry in nodes and boxed keys before the value. The parallel passes and the
        // enrichment loop below both walk `rides` in order, so the list position IS the key.
        MaxCostResult[] maxCostByPos = computeMaxCosts(rides);
		log.info("  Max costs computed in {} ms", System.currentTimeMillis() - maxCostStart);

		double[][] shapleyByPos;
		if (config.isCalcShapleyValues()) {
			log.info("  Computing Shapley values...");
			long shapleyStart = System.currentTimeMillis();
			shapleyByPos = computeShapleyValues(rides);
			log.info("  Shapley values computed in {} ms", System.currentTimeMillis() - shapleyStart);
		} else {
			log.info("  Shapley values disabled (skipped)");
			shapleyByPos = null;
		}

		PredSucc predsAndSuccs;
		if (config.isCalcPredecessors()) {
			log.info("  Computing handoff repositioning times...");
			long predStart = System.currentTimeMillis();
			predsAndSuccs = computePredecessors(rides);
			log.info("  Handoff repositioning times computed in {} ms", System.currentTimeMillis() - predStart);
		} else {
			log.info("  Handoff repositioning pass disabled (skipped)");
			predsAndSuccs = new PredSucc(Int2DoubleMaps.EMPTY_MAP);
		}

        // Enrich IN PLACE. The previous version accumulated a second `enriched` list while `rides`
        // was still fully referenced, so both generations of 29.6M Ride objects were alive at the
        // peak. Overwriting each slot lets the pre-enrichment ride become collectable immediately,
        // and drops the per-ride result slots as they are consumed.
        int rideCount = rides.size();
        for (int pos = 0; pos < rideCount; pos++) {
            Ride ride = rides.get(pos);
            int rideId = ride.getIndex();
            double[] shapley = shapleyByPos != null ? shapleyByPos[pos] : null;
            MaxCostResult maxCostResult = maxCostByPos[pos];
            double reposMean = predsAndSuccs.reposTimeMeans().getOrDefault(rideId, -1.0);

            rides.set(pos, ride.toBuilder()
                    .maxCosts(maxCostResult.maxCosts())
                    .maxCostsPerKm(maxCostResult.maxCostsPerKm())
                    .shapleyValues(shapley)
                    .reposTimeMeanOutgoing(reposMean)
                    .build());
            maxCostByPos[pos] = null;
            if (shapleyByPos != null) shapleyByPos[pos] = null;
        }

		long totalTime = System.currentTimeMillis() - startTime;
		log.info("Post-processing complete in {} ms", totalTime);
        return rides;
    }

    private record MaxCostResult(double[] maxCosts, double[] maxCostsPerKm) {}

    /** Results in list-position order (see the note in {@link #process}). */
    private MaxCostResult[] computeMaxCosts(List<Ride> rides) {
        MaxCostResult[] byPos = new MaxCostResult[rides.size()];
        for (int pos = 0; pos < byPos.length; pos++) {
            byPos[pos] = computeMaxCostsForRide(rides.get(pos));
        }
        return byPos;
    }

    /**
     * Per-ride maxCosts/maxCostsPerKm — the inner loop of {@link #computeMaxCosts}, factored out so
     * the streaming enricher ({@link #streamingPerRideEnricher()}) computes the SAME values one ride
     * at a time without materializing the full list. maxCost is a pure function of the ride's own
     * budget/travel-time/distance, so this is cross-ride independent (unlike Shapley/predecessors).
     */
    private MaxCostResult computeMaxCostsForRide(Ride ride) {
        double[] remainingBudgets = ride.getRemainingBudgets();
        double[] maxCosts = new double[ride.getDegree()];
        double[] maxCostsPerKm = new double[ride.getDegree()];
        DrtRequest[] requests = ride.getRequests();
        double[] travelTimes = ride.getPassengerTravelTimes();
        double[] distances = ride.getPassengerDistances();

        for (int i = 0; i < ride.getDegree(); i++) {
            DrtRequest request = requests[i];
            double budget = (remainingBudgets != null && remainingBudgets.length > i) ? remainingBudgets[i] : 0.0;

            maxCosts[i] = maxCostResolver.maxCost(budget, request, travelTimes[i], distances[i]);

            // Derive per-km cost (source of truth for Python optimization pipeline)
            maxCostsPerKm[i] = distances[i] > 0
                ? maxCosts[i] / (distances[i] / 1000.0)
                : Double.MAX_VALUE;
        }

        return new MaxCostResult(maxCosts, maxCostsPerKm);
    }

    /**
     * True when no cross-ride pass is enabled (Shapley off AND predecessors off). Only then can the
     * Stage-1 output be streamed one ride at a time via {@link #streamingPerRideEnricher()} instead
     * of materializing the full fat list in {@link #process}. Shapley needs the global
     * {@code subsetDistance} map and predecessors need the all-rides windowed search, so neither is
     * a pure per-ride function (those streaming passes are added in later plan stages).
     */
    public boolean isStreamingPostProcessSupported() {
        return !config.isCalcShapleyValues() && !config.isCalcPredecessors();
    }

    /**
     * A per-ride enrichment equivalent to {@link #process} when {@link #isStreamingPostProcessSupported()}:
     * computes this ride's maxCosts/maxCostsPerKm and leaves Shapley and the repositioning mean unset.
     * Stateless over the shared resolver; intended for {@code ExMasCsvWriter.writeRidesStreaming}.
     *
     * @throws IllegalStateException if a cross-ride pass is enabled (then the full {@link #process} is required)
     */
    public UnaryOperator<Ride> streamingPerRideEnricher() {
        if (!isStreamingPostProcessSupported()) {
            throw new IllegalStateException(
                    "streamingPerRideEnricher requires calcShapleyValues=false AND calcPredecessors=false; "
                    + "those passes are cross-ride and cannot be streamed per ride. Use process() instead.");
        }
        return ride -> {
            MaxCostResult mc = computeMaxCostsForRide(ride);
            return ride.toBuilder()
                    .maxCosts(mc.maxCosts())
                    .maxCostsPerKm(mc.maxCostsPerKm())
                    .shapleyValues(null)
                    .reposTimeMeanOutgoing(-1.0)
                    .build();
        };
    }

    /**
     * Largest ride degree this pass will attempt. The Shapley value is a sum over all {@code 2^n}
     * sub-coalitions, so the work and the scratch buffer both double with every extra passenger.
     * The previous implementation was equally exponential (it allocated {@code 2^(n-1)} HashSets per
     * player), so this bound rejects nothing that used to succeed — it just fails loudly instead of
     * exhausting the heap.
     */
    static final int MAX_SHAPLEY_DEGREE = 20;

    /**
     * Per-worker scratch for {@link #computeShapleyValues}: the sub-coalition value table and one
     * exact-length key buffer per subset size. Reused across every ride a worker touches, so the
     * subset enumeration allocates nothing at all.
     */
    private static final class ShapleyScratch {
        private double[] values = new double[0];
        private int[][] keyByLength = new int[0][];

        void ensureCapacity(int degree) {
            int subsetCount = 1 << degree;
            if (values.length < subsetCount) {
                values = new double[subsetCount];
            }
            if (keyByLength.length <= degree) {
                int[][] grown = new int[degree + 1][];
                System.arraycopy(keyByLength, 0, grown, 0, keyByLength.length);
                for (int len = keyByLength.length; len <= degree; len++) {
                    grown[len] = new int[len];
                }
                keyByLength = grown;
            }
        }
    }

    /**
     * Shapley distance shares per ride, in list-position order.
     *
     * <p>The characteristic function is {@code v(S) = } the shortest ride distance that serves
     * exactly the request set {@code S}, or 0 when no such ride exists. Keys are SORTED request-index
     * arrays under fastutil's array hash strategy rather than {@code HashSet<Integer>}: a HashSet of
     * 8 numbers costs ~590 B (set + inner map + table + a node and a boxed Integer per element) to
     * carry 32 B of data, and the map holds one per ride. At 29.6M rides that packaging alone was
     * ~12 GB. A sorted {@code int[]} key is ~56 B.
     *
     * <p>Request indices within a ride are distinct by construction, so the sorted array is exactly
     * the set the old code built.
     */
    private double[][] computeShapleyValues(List<Ride> rides) {
        Object2DoubleOpenCustomHashMap<int[]> subsetDistance =
                new Object2DoubleOpenCustomHashMap<>(IntArrays.HASH_STRATEGY);
        // Absent sub-coalition => value 0, matching the old getOrDefault(subset, 0.0). The empty set
        // is covered by the same default, so it needs no explicit entry.
        subsetDistance.defaultReturnValue(0.0);
        for (Ride ride : rides) {
            // getRequestIndices() returns a fresh array per call, so sorting and retaining it as a
            // map key cannot alias the ride's own state.
            int[] key = ride.getRequestIndices();
            Arrays.sort(key);
            double rideDistance = ride.getRideDistance();
            if (rideDistance < subsetDistance.getOrDefault(key, Double.POSITIVE_INFINITY)) {
                subsetDistance.put(key, rideDistance);
            }
        }

        double[][] shapleyByPos = new double[rides.size()][];
        ThreadLocal<ShapleyScratch> scratch = ThreadLocal.withInitial(ShapleyScratch::new);
        int availableParallelism = resolveParallelism();
        runIndexedParallel(rides.size(), availableParallelism, pos -> {
            Ride ride = rides.get(pos);
            int[] requests = ride.getRequestIndices();
            int n = requests.length;

            ShapleyScratch buf = scratch.get();
            buf.ensureCapacity(Math.min(n, MAX_SHAPLEY_DEGREE));

            if (n == 1) {
                int[] singleton = buf.keyByLength[1];
                singleton[0] = requests[0];
                // Preserves the old fallback: a degree-1 ride whose singleton is somehow absent
                // scores its own distance, NOT 0.
                shapleyByPos[pos] = new double[] {
                        subsetDistance.containsKey(singleton)
                                ? subsetDistance.getDouble(singleton)
                                : ride.getRideDistance()
                };
                return;
            }
            if (n > MAX_SHAPLEY_DEGREE) {
                throw new IllegalStateException("Shapley value needs 2^degree sub-coalitions; ride "
                        + ride.getIndex() + " has degree " + n + ", above the supported maximum of "
                        + MAX_SHAPLEY_DEGREE + ". Disable calcShapleyValues or cap maxDegree.");
            }

            // Enumerate sub-coalitions over the SORTED request order so each key comes out ascending
            // with no per-subset sort, while `rank` maps every original position back to its bit.
            int[] sorted = requests.clone();
            Arrays.sort(sorted);
            int[] rank = new int[n];
            for (int i = 0; i < n; i++) {
                rank[i] = Arrays.binarySearch(sorted, requests[i]);
            }

            int subsetCount = 1 << n;
            double[] values = buf.values;
            // One lookup per sub-coalition, cached. The old loop re-looked-up v(S) and v(S+i) inside
            // the per-player loop, doing n*2^n map probes per ride instead of 2^n.
            values[0] = 0.0;
            for (int mask = 1; mask < subsetCount; mask++) {
                int len = Integer.bitCount(mask);
                int[] key = buf.keyByLength[len];
                int p = 0;
                for (int bit = 0; bit < n; bit++) {
                    if ((mask & (1 << bit)) != 0) {
                        key[p++] = sorted[bit];
                    }
                }
                values[mask] = subsetDistance.getDouble(key);
            }

            double nFactorial = factorial(n);
            double[] shapley = new double[n];
            for (int i = 0; i < n; i++) {
                int bit = 1 << rank[i];
                double acc = 0.0;
                for (int mask = 0; mask < subsetCount; mask++) {
                    if ((mask & bit) != 0) continue;
                    int sSize = Integer.bitCount(mask);
                    double weight = (factorial(sSize) * factorial(n - sSize - 1)) / nFactorial;
                    acc += weight * (values[mask | bit] - values[mask]);
                }
                shapley[i] = acc;
            }

            shapleyByPos[pos] = shapley;
        });

        return shapleyByPos;
    }

    private PredSucc computePredecessors(List<Ride> rides) {
		log.info("    Sorting {} rides by start time...", rides.size());
        List<Ride> sortedByStart = new ArrayList<>(rides);
        sortedByStart.sort(Comparator.comparingDouble(Ride::getStartTime));
        int total = sortedByStart.size();

        double[] startTimes = new double[total];
        double[] endTimes = new double[total];
        double[] rideDistances = new double[total];
        @SuppressWarnings("unchecked")
        Id<Link>[] firstOrigins = (Id<Link>[]) new Id[total];
        @SuppressWarnings("unchecked")
        Id<Link>[] lastDests = (Id<Link>[]) new Id[total];
        // Sorted request indices per ride, used only for the pairwise overlap test below. Same
        // reasoning as the Shapley keys: a HashSet<Integer> per ride cost ~590 B to hold ~32 B of
        // data, ~12 GB across the 100% pool. Sorted int[] plus a merge walk is ~56 B and faster.
        int[][] requestSets = new int[total][];

        for (int idx = 0; idx < total; idx++) {
            Ride ride = sortedByStart.get(idx);
            startTimes[idx] = ride.getStartTime();
            endTimes[idx] = ride.getEndTime();
            rideDistances[idx] = ride.getRideDistance();
            Id<Link>[] origins = ride.getOriginsOrdered();
            Id<Link>[] destinations = ride.getDestinationsOrdered();
            firstOrigins[idx] = origins.length > 0 ? origins[0] : null;
            lastDests[idx] = destinations.length > 0 ? destinations[destinations.length - 1] : null;
            int[] reqs = ride.getRequestIndices();
            Arrays.sort(reqs);
            requestSets[idx] = reqs;
        }

        // -1 or null => unbounded (no filter)
        Double rawFilterTime = config.getPredecessorsFilterTime();
        double filterTime = (rawFilterTime != null && rawFilterTime >= 0) ? rawFilterTime : Double.POSITIVE_INFINITY;
        Double rawFilterDist = config.getPredecessorsFilterDistanceFactor();
        double filterDistanceFactor = (rawFilterDist != null && rawFilterDist >= 0) ? rawFilterDist : Double.POSITIVE_INFINITY;
        int maxSuccessors = config.getMaxSuccessors();

        // ── Spatial pre-filter setup ─────────────────────────────────────────────
        // A handoff i->j needs network travel time <= gap_j = startTimes[j] - endTimes[i].
        // Network time is always >= euclidean(lastDest_i, firstOrigin_j) / maxSpeed, so when
        // euclidean/maxSpeed > gap_j the pair is provably infeasible -> skip before routing.
        // SOUND: a feasible handoff has routed_tt <= gap_j, and euclidean/maxSpeed <= routed_tt,
        // so it always passes the filter; only far-and-infeasible pairs are dropped. Successor
        // output is therefore identical; only the routing/retain/window work for dropped pairs
        // is saved (that work dominates the pass at 100% scale). Needs a real network + a finite
        // filterTime; otherwise it is a no-op and the pass behaves exactly as before.
        final boolean spatialPrefilter = config.isPredecessorsSpatialPrefilter()
                && prefilterNetwork != null && Double.isFinite(filterTime);
        // Distance-factor cap applied as a PRE-ROUTING euclidean cut. LOSSLESS w.r.t. the existing
        // post-route distance filter (below): a pair with euclidean > rideDist_i*factor has routed
        // distance >= euclidean > cap, so the post filter would drop it anyway — skipping it before
        // routing is output-identical. Unlike the time-reach cut (which reaches ~the whole study area
        // at a 900 s gap), this is an ABSOLUTE cap and so prunes at every time gap — the lever that
        // actually makes the 100% pass tractable. Active whenever a finite filterDistanceFactor is set
        // and a real network is available (coords needed).
        final boolean distancePrefilter = prefilterNetwork != null && Double.isFinite(filterDistanceFactor);
        final boolean coordsNeeded = spatialPrefilter || distancePrefilter;
        final double[] originX = coordsNeeded ? new double[total] : null;
        final double[] originY = coordsNeeded ? new double[total] : null;
        final double[] destX = coordsNeeded ? new double[total] : null;
        final double[] destY = coordsNeeded ? new double[total] : null;
        final double maxSpeed;
        if (coordsNeeded) {
            var links = prefilterNetwork.getLinks();
            if (spatialPrefilter) {
                double override = config.getPredecessorsPrefilterMaxSpeedMps();
                if (override > 0.0) {
                    // Explicit upper-bound speed (m/s). Use when the network has artifact links whose
                    // freespeed inflates the auto bound and weakens pruning; the caller asserts no
                    // real handoff exceeds this effective speed.
                    maxSpeed = override;
                } else {
                    double maxFree = 0.0;
                    for (org.matsim.api.core.v01.network.Link link : links.values()) {
                        double fs = link.getFreespeed();
                        // Ignore non-finite freespeed (artifact/virtual links, e.g. Infinity): including
                        // them yields maxSpeed=Infinity -> the filter never prunes. Finite max is the sound
                        // bound for every finite-speed link (which is all but a handful of artifacts).
                        if (Double.isFinite(fs) && fs > maxFree) maxFree = fs;
                    }
                    // 1.5x safety margin absorbs (a) time-dependent travel times faster than freespeed,
                    // (b) the link-coord vs routed-node-coord discrepancy, and (c) the negligible euclidean
                    // distance any short non-finite-speed link could cover "for free", keeping the bound sound.
                    maxSpeed = Math.max(maxFree, 1.0) * 1.5;
                }
            } else {
                maxSpeed = 0.0;
            }
            for (int idx = 0; idx < total; idx++) {
                org.matsim.api.core.v01.network.Link oLink =
                        firstOrigins[idx] != null ? links.get(firstOrigins[idx]) : null;
                org.matsim.api.core.v01.network.Link dLink =
                        lastDests[idx] != null ? links.get(lastDests[idx]) : null;
                if (oLink != null) { originX[idx] = oLink.getCoord().getX(); originY[idx] = oLink.getCoord().getY(); }
                if (dLink != null) { destX[idx] = dLink.getCoord().getX(); destY[idx] = dLink.getCoord().getY(); }
            }
            log.info("    Predecessor pre-filter: time-reach={} (maxSpeed {} m/s, radius {} m @ {}s), distance-cap={} (factor {} x ride distance)",
                    spatialPrefilter ? "ON" : "off",
                    spatialPrefilter ? String.format("%.1f", maxSpeed) : "-",
                    spatialPrefilter ? String.format("%.0f", maxSpeed * filterTime) : "-",
                    String.format("%.0f", filterTime),
                    distancePrefilter ? "ON" : "off",
                    distancePrefilter ? String.format("%.2f", filterDistanceFactor) : "-");
        } else {
            maxSpeed = 0.0;
            if (config.isPredecessorsSpatialPrefilter() && prefilterNetwork == null) {
                log.info("    Predecessor spatial pre-filter requested but no network available — disabled (stub lookup).");
            }
        }

        // Position-indexed, primitive, no concurrent map. Each parallel task owns exactly one slot i,
        // so plain array writes are safe and need no synchronization.
        //
        // The pass no longer materialises the successor LISTS at all. It used to keep the kept
        // positions per ride, derive the reverse (predecessor) map from them, translate both to ride
        // ids and hand them to Ride — ~48 GB of boxed collections at the 100% pool before the int[]
        // conversion doubled the successor side again. Nothing consumed either column: Python's
        // solver_input_builder overwrites `successors` with the dynamically recomputed edges over the
        // MIP-selected ride set, and `predecessors` was already dropped from the emit as a redundant
        // reverse map. Only the aggregate below survives the pass.
        double[] reposTimeByPos = new double[total];
        Arrays.fill(reposTimeByPos, -1.0);
        // Diagnostic only: how many handoff edges survived the top-K cap, summed over all rides.
        final LongAdder keptHandoffs = new LongAdder();

        // ── Group rides by (last-destination link, end-time bin) ─────────────────
        // Every ride in a group resolves exactly the SAME connection keys: getSegment keys on
        // (origin, dest, timeBin) and routes at the bin's canonical MIDPOINT
        // (MatsimNetworkCache.getSegment), so a segment value is a pure function of that triple —
        // independent of which ride asks and of the order they ask in. A group therefore needs one
        // cache read, one retain and one window-key append per distinct destination link, not one
        // per ride PAIR.
        //
        // This is the pass's dominant cost. Measured on the Lyon 25% pool (2,134,934 rides):
        // 7,824,389,388 cache lookups at a 100.0% hit rate, producing 3,228,231 distinct exported
        // keys — 2,423 redundant lookups per distinct route, and 47.5 min of wall clock in which
        // only 0.03% of lookups needed routing at all. The window-key buffer is the same story in
        // memory: it appended one long per evaluated pair, tens of GB to carry 3.2M distinct keys.
        //
        // The dedup is MEMOISATION, NOT FILTERING: the per-pair prefilter and disjointness tests
        // stay exactly where they were, so a key is recorded precisely when some pair passes the
        // same tests it passes today. RidePostProcessorWindowKeyTest pins that set.
        final int binSize = config.getNetworkTimeBinSize();
        Long2ObjectOpenHashMap<IntArrayList> groupIndex = new Long2ObjectOpenHashMap<>();
        for (int pos = 0; pos < total; pos++) {
            Id<Link> from = lastDests[pos];
            // A ride with no destination link gets its own group (index -1): the inner loop drops
            // every candidate for it, so it still yields an empty successor list, as before.
            long gk = ((long) (from != null ? from.index() : -1) << 32)
                    | ((int) (endTimes[pos] / binSize) & 0xFFFFFFFFL);
            groupIndex.computeIfAbsent(gk, k -> new IntArrayList()).add(pos);
        }
        int[][] groupMembers = new int[groupIndex.size()][];
        int groupFill = 0;
        for (IntArrayList members : groupIndex.values()) {
            groupMembers[groupFill++] = members.toIntArray();
        }
        groupIndex = null;   // release the index before the parallel pass allocates
        final int groupCount = groupMembers.length;

        int parallelism = resolveParallelism();
        log.info("    Grouped {} rides into {} (dest-link, bin) groups ({} rides per group)",
                total, groupCount,
                String.format("%.1f", (double) total / Math.max(1, groupCount)));
		log.info("    Computing predecessor/successor connections (parallelism: {})...", parallelism);
		log.info("    Filter: time={}, distanceFactor={}, maxSuccessors={}",
				Double.isInfinite(filterTime) ? "unbounded" : String.format("%.0fs", filterTime),
				Double.isInfinite(filterDistanceFactor) ? "unbounded" : String.format("%.2f", filterDistanceFactor),
				maxSuccessors <= 0 ? "all" : maxSuccessors);
		if (Double.isInfinite(filterTime)) {
			log.warn("    predecessorsFilterTime is unbounded (-1) — all {} ride pairs will be considered. " +
					"This creates a complete connection cache but scales O(n²).", (long) total * (total - 1) / 2);
		}
		log.info("    This requires routing up to {} potential connections via network...", (long) total * (total - 1) / 2);

		long routingStartTime = System.currentTimeMillis();
		AtomicInteger processed = new AtomicInteger(0);
		long routingStartNanos = System.nanoTime();

        // One-shot barrier eviction (single-threaded, before any routing): drop the speculative tier
        // left over from enumeration. Those within-ride segments are never re-read here — the pass
        // routes ride-to-ride handoffs — so they are dead weight. Clearing them at this barrier (no
        // routing in flight) also resets the SSSP marks, so the predecessor pass re-routes its
        // handoffs fresh under its own filterTime bound instead of inheriting enumeration's truncated
        // global-max tree. Memory is then kept in check during the parallel loop by the per-ride
        // checkWatermark() below (the speculative batch fills are output-invariant under eviction);
        // the handoffs this pass produces are retained by value, and the retained tier (enumeration
        // survivor legs for export) is kept.
        networkCache.dropSpeculativeTier();

        // Window keys (the export domain) are recorded into per-thread primitive buffers, NOT a single
        // shared concurrent queue: every evaluated pair appends one packed long, so a shared structure
        // would serialize all workers on one lock/CAS and box billions of Longs (the documented OOM).
        // Each worker thread lazily creates its own LongArrayList and registers it here; the buffers
        // are merged into the deduped windowKeys set once, after the parallel pass joins.
        ConcurrentLinkedQueue<it.unimi.dsi.fastutil.longs.LongArrayList> windowKeyBuffers = new ConcurrentLinkedQueue<>();
        ThreadLocal<it.unimi.dsi.fastutil.longs.LongArrayList> windowKeyBuffer = ThreadLocal.withInitial(() -> {
            it.unimi.dsi.fastutil.longs.LongArrayList buf = new it.unimi.dsi.fastutil.longs.LongArrayList();
            windowKeyBuffers.add(buf);
            return buf;
        });

        // Measure the routing this pass alone performs (cumulative counters are dominated by pair-gen /
        // extension): snapshot before the loop, snapshot after the join, report the delta.
        long[] routingBefore = networkCache.routingCountersSnapshot();

        // Forward search: for each GROUP, then each ride i within it, find successors j.
        runIndexedParallel(groupCount, parallelism, g -> {
            int[] groupRides = groupMembers[g];

            // Group-local memo: destination link index -> slot in the three value lists below.
            // The group fixes (from, bin), so the destination link alone identifies the connection
            // key. First sight of a link pays the global-cache read, the retain (a map WRITE) and
            // the window-key append; every later pair in the group reads an array slot instead.
            // Lifetime is one group on one thread, so no synchronisation is needed.
            Int2IntOpenHashMap toSlot = new Int2IntOpenHashMap();
            toSlot.defaultReturnValue(-1);
            DoubleArrayList memoTravelTime = new DoubleArrayList();
            DoubleArrayList memoDistance = new DoubleArrayList();
            BooleanArrayList memoReachable = new BooleanArrayList();

            // Top-K selector, allocated once per group and reset per ride: the candidate loop below
            // used to allocate one record per feasible candidate and then full-sort them.
            TopKScratch topK = new TopKScratch();

            for (int i : groupRides) {
			int done = processed.incrementAndGet();
			if (done == total || (int)(100.0 * done / total) > (int)(100.0 * (done - 1) / total)) {
				double elapsedSeconds = (System.nanoTime() - routingStartNanos) / 1e9;
				double remainingSeconds = done <= 0 ? 0.0 : (elapsedSeconds / done) * (total - done);
				double percent = 100.0 * done / total;
				log.info("      Predecessor/successor routing progress: {}/{} ({}%), ETA {}",
						done, total, String.format("%.1f", percent), formatDuration(remainingSeconds));
			}
            double endTime = endTimes[i];
            // Constant across the group by construction — the group key IS (lastDest, bin).
            int bin = (int) (endTime / binSize);
            double minStartTime = endTime; // Successor must start after predecessor ends
            double maxStartTime = endTime + filterTime;

            // Find range in sortedByStart [minStartTime, maxStartTime]
            int sliceStart = Arrays.binarySearch(startTimes, minStartTime);
            if (sliceStart < 0) {
                sliceStart = -sliceStart - 1;
            } else {
                // Handle duplicates: move left to first occurrence
                while (sliceStart > 0 && startTimes[sliceStart - 1] >= minStartTime) {
                    sliceStart--;
                }
            }
            
            int sliceEnd = Arrays.binarySearch(startTimes, maxStartTime);
            if (sliceEnd < 0) {
                sliceEnd = -sliceEnd - 1;
            } else {
                sliceEnd += 1; // upper bound
            }

            // SSSP batch precompute: one Dijkstra tree from lastDests[i] populates the cache
            // for all candidate destination links in [sliceStart, sliceEnd). Replaces N
            // point-to-point routing calls with 1 tree. Pattern from PairGenerator.java:203.
            // The no-op default on TravelSegmentLookup keeps test stubs working unchanged.
            Id<Link> originLink = lastDests[i];
            if (originLink != null && sliceEnd > sliceStart) {
                List<Id<Link>> candidateLinksList = new ArrayList<>(sliceEnd - sliceStart);
                for (int j = sliceStart; j < sliceEnd; j++) {
                    if (i == j) continue;
                    Id<Link> to = firstOrigins[j];
                    if (to == null) continue;
                    // Pre-filter: only seed the SSSP tree with candidates that pass the time-reach
                    // and/or distance-cap cuts. Skipped pairs are provably droppable (routed later
                    // would fail arrivalTime<=startTime or the distance filter), so leaving them out
                    // of the tree targets is output-invariant.
                    if (coordsNeeded && !preRouteKeep(spatialPrefilter, startTimes[j] - endTime, maxSpeed,
                            distancePrefilter, rideDistances[i], filterDistanceFactor,
                            originX[j] - destX[i], originY[j] - destY[i])) {
                        continue;
                    }
                    // Already resolved for this group: no getSegment will ask about it, so seeding
                    // the tree with it is wasted work. Dropping a target can only turn a later
                    // cache hit into a point-to-point route, which is bit-identical (both settle
                    // the same optimum at the bin midpoint) — the same invariance watermark
                    // eviction already relies on.
                    if (toSlot.get(to.index()) >= 0) {
                        continue;
                    }
                    candidateLinksList.add(to);
                }
                if (!candidateLinksList.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Id<Link>[] toArr = candidateLinksList.toArray(new Id[0]);
                    networkCache.batchPrecompute(originLink, endTime, toArr, filterTime);
                }
            }

            topK.reset(maxSuccessors);

            for (int j = sliceStart; j < sliceEnd; j++) {
                if (i == j) continue;

                // Basic checks
                Id<Link> from = lastDests[i];
                Id<Link> to = firstOrigins[j];
                if (from == null || to == null) continue;

                // Pre-filter: skip provably-droppable handoffs BEFORE routing them. Time-reach cut
                // (euclidean > maxSpeed*gap => routed_tt > gap => arrivalTime > startTime[j]) and/or
                // absolute distance cap (euclidean > rideDist_i*factor => routed > cap => post-route
                // distance filter drops it). Both are lossless; this avoids the getSegment route +
                // retain + window-record for the far pairs that dominate the window at 100% scale.
                //
                // Ordered BEFORE the disjointness test deliberately: this is a handful of
                // multiplications and comparisons on primitives already in registers, whereas
                // disjointSorted is a merge walk over two arrays that must be pulled from memory.
                // Both are pure predicates, so the conjunction — and therefore the window-key set —
                // is unchanged; only the order in which pairs are rejected differs. At the hundreds
                // of billions of pairs the 100% pool reaches, cheapest-first is worth real time.
                if (coordsNeeded && !preRouteKeep(spatialPrefilter, startTimes[j] - endTime, maxSpeed,
                        distancePrefilter, rideDistances[i], filterDistanceFactor,
                        originX[j] - destX[i], originY[j] - destY[i])) {
                    continue;
                }

                // Disjoint requests check
                if (!disjointSorted(requestSets[i], requestSets[j])) {
                    continue;
                }

                // Cache-first lookup. batchPrecompute above populated the cache for the bulk of
                // in-window candidates (~99% hit at Kelheim scale), so this resolves without routing;
                // getSegment routes point-to-point only on a miss. We must NOT treat a cache miss as
                // "infeasible and skip it": the bounded SSSP tree is cost-ordered (least generalized
                // disutility) but stopped on a TIME bound, and DRT disutility is distance-aware (not
                // time-proportional), so a time-FEASIBLE handoff lying on a higher-disutility path is
                // popped only after the time-bound break fires and is therefore left unsettled/uncached.
                // Skipping such a miss silently drops feasible successors (confirmed at Kelheim scale:
                // a single ride losing 41 of 50 successors). getSegment routes the true optimal on
                // demand — correct, eviction-invariant, and only ~1% of lookups.
                // Group memo. The group fixes (from, bin) and getSegment routes at the bin's
                // canonical midpoint, so the segment is a pure function of the destination link:
                // resolving it once per group is exactly equivalent to resolving it per pair.
                int toIdx = to.index();
                int slot = toSlot.get(toIdx);
                if (slot < 0) {
                    TravelSegment connection = networkCache.getSegment(from, to, endTime);
                    // Cache-memory tiers: pin this evaluated handoff into the never-evicted retained tier.
                    // This window domain — the lookup set of Python's compute_dynamic_successors — must
                    // survive watermark eviction so the connection_cache export is stable. We retain BY
                    // VALUE (the cached segment), NOT promoteSegment (a spec→retained move): this pass
                    // evicts the speculative tier *during* the parallel routing (checkWatermark at the end
                    // of each group), so a move-based promote could find the entry already evicted and
                    // silently drop the handoff from the export, whereas a by-value retain always lands it.
                    networkCache.retainSegment(from, to, endTime,
                            connection.getTravelTime(), connection.getDistance(), connection.getNetworkUtility());
                    // Record this evaluated handoff in the "window" export domain. Pack with the same
                    // time-bin convention getSegment uses, so the export's cache.get(key) resolves it.
                    // Once per DISTINCT key rather than once per pair: the buffer previously grew by
                    // one long per evaluated pair, which is the pass's second scaling wall.
                    windowKeyBuffer.get().add(PackedKeyCodec.segmentKey(from.index(), toIdx, bin));
                    slot = memoTravelTime.size();
                    toSlot.put(toIdx, slot);
                    memoTravelTime.add(connection.getTravelTime());
                    memoDistance.add(connection.getDistance());
                    memoReachable.add(connection.isReachable());
                }
                if (!memoReachable.getBoolean(slot)) continue;
                double connectionTravelTime = memoTravelTime.getDouble(slot);
                double connectionDistance = memoDistance.getDouble(slot);

                double arrivalTime = endTime + connectionTravelTime;
                // Arrival at successor start must be feasible? 
                // Actually, successor starts at startTimes[j].
                // We arrive at 'arrivalTime'.
                // If arrivalTime > startTimes[j], we are late.
                if (arrivalTime > startTimes[j]) continue;
                
                // Also check if we arrive too early? (Wait time constraint?)
                // The filterTime constrains (startTimes[j] - endTime).
                // So the gap is bounded.

                if (Double.isFinite(filterDistanceFactor)
                        && connectionDistance > rideDistances[i] * filterDistanceFactor) {
                    continue;
                }

                // Selection score: keep the smallest distance * idling. "Short distance doesn't help
                // us if we have a low idling time" -> minimize the product; max(1.0, idling) keeps
                // distance the primary factor when idling is near zero. The heap applies the cap as
                // candidates arrive, so nothing beyond K is ever retained or sorted.
                double idlingTime = startTimes[j] - arrivalTime;
                topK.offer(connectionDistance * Math.max(1.0, idlingTime), j, connectionTravelTime);
            }

            // Mean outgoing repositioning travel time over the KEPT (post-pruning) successors.
            reposTimeByPos[i] = topK.isEmpty() ? -1.0 : topK.meanTravelTime();
            keptHandoffs.add(topK.size());
            }

            // Per-group barrier: sample heap and rotate the older speculative generation under
            // pressure, so a large scenario evicts this group's (and earlier groups') dead batch
            // fills instead of letting the speculative tier grow until OOM. Output-invariant: an
            // evicted segment re-routes bit-identically (a feasible handoff is within filterTime, so
            // the bounded batch tree settled it optimally = the unbounded point-to-point value), and
            // the evaluated handoffs are already retained by value, so eviction never drops an
            // export key. HeapWatermark is synchronized (safe under the parallel pass) and a no-op
            // below the watermark; at watermark 1.0 (tests) it never fires, keeping them
            // bit-reproducible. Per group rather than per ride: groups average ~10 rides at the 25%
            // pool, so the eviction cadence is materially unchanged while the synchronized call
            // count drops with it.
            networkCache.checkWatermark();
        });

        // Per-phase routing report: what the predecessor pass alone routed (the cumulative cache
        // statistics are dominated by pair-gen / extension and cannot answer this on their own).
        long[] routingAfter = networkCache.routingCountersSnapshot();
        if (routingBefore != null && routingAfter != null) {
            long lookups = routingAfter[0] - routingBefore[0]; // peekSegment calls (present + absent)
            long routed = routingAfter[1] - routingBefore[1];  // SpeedyALT point-to-point on peek miss
            long hits = lookups - routed;                      // present-in-cache (batch-populated) handoffs
            long trees = routingAfter[2] - routingBefore[2];
            long treesSkipped = routingAfter[3] - routingBefore[3];
            long segsPopulated = routingAfter[4] - routingBefore[4];
            log.info("    Predecessor-pass routing: {} cache lookups ({} hits = {}%, "
                    + "{} SpeedyALT point-to-point on miss); {} SSSP trees ({} skipped) -> {} segments populated",
                    String.format("%,d", lookups),
                    String.format("%,d", hits),
                    lookups > 0 ? String.format("%.1f", 100.0 * hits / lookups) : "n/a",
                    String.format("%,d", routed),
                    String.format("%,d", trees),
                    String.format("%,d", treesSkipped),
                    String.format("%,d", segsPopulated));
        }

        // Merge the per-thread window-key buffers into the deduped export set (single-threaded; the
        // parallel pass has joined). Same keys as the old shared queue, no boxing, no contention.
        LongOpenHashSet mergedWindowKeys = new LongOpenHashSet();
        for (it.unimi.dsi.fastutil.longs.LongArrayList buf : windowKeyBuffers) {
            mergedWindowKeys.addAll(buf);
        }
        this.windowKeys = mergedWindowKeys;

        // Single-threaded barrier (the parallel pass has joined): freeze the retained overlay of
        // pinned handoffs into a compact snapshot before export.
        networkCache.compactRetained();

		long routingTime = System.currentTimeMillis() - routingStartTime;
		log.info("    Network routing completed in {} ms", routingTime);

        // Positions -> ride ids. Rides with no feasible handoff are left out: the read site
        // substitutes -1 for a missing key, so this is the same result with fewer entries held.
        Int2DoubleOpenHashMap reposTimeMeans = new Int2DoubleOpenHashMap(total);
        for (int p = 0; p < total; p++) {
            if (reposTimeByPos[p] != -1.0) {
                reposTimeMeans.put(sortedByStart.get(p).getIndex(), reposTimeByPos[p]);
            }
        }

		log.info("    Found {} handoff connections over {} rides with a feasible successor",
				String.format("%,d", keptHandoffs.sum()), String.format("%,d", reposTimeMeans.size()));

        return new PredSucc(reposTimeMeans);
    }

    /**
     * Packed OD/bin keys of the handoff segments evaluated by the predecessor/successor pass
     * (accepted AND rejected) — the Task-9 {@code "window"} connection-cache export domain.
     * Valid after {@link #process(RideStore)} has run: {@link #computePredecessors} merges the
     * per-thread key buffers into this deduped {@link LongOpenHashSet} at the join. Empty when
     * predecessor computation is disabled.
     */
    public LongOpenHashSet getWindowKeys() {
        return windowKeys;
    }

    /**
     * Bounded top-K successor selector, reused across every ride in a group.
     *
     * <p>Replaces "allocate a record per feasible candidate, collect them in a growable list,
     * full-sort by score, take the first K". At the Lyon 25% pool that allocated billions of
     * short-lived objects and sorted thousands of them per ride; here three primitive arrays are
     * allocated once per group and reset per ride, and nothing is sorted.
     *
     * <p><b>The selection is identical to the sort it replaces.</b> The previous code appended
     * candidates in ascending ride position and sorted with a STABLE comparator on score alone, so
     * equal scores retained ascending position, and {@code subList(0, K)} therefore took the K
     * smallest by the pair {@code (score, position)}. This is a max-heap keyed on exactly that
     * pair: the root is the worst element currently kept, and an incoming candidate displaces it
     * only when strictly smaller. The surviving K are the same K.
     *
     * <p>{@code capacity <= 0} means "keep every feasible successor" (an unlimited
     * {@code maxSuccessors}). No selection happens then, so no ordering is imposed and the arrays
     * simply grow.
     */
    static final class TopKScratch {   // package-private: RidePostProcessorTopKParityTest
        private double[] score = new double[0];
        private int[] position = new int[0];
        private double[] travelTime = new double[0];
        private int size;
        private int capacity;

        /** Start a new ride. {@code k <= 0} selects unbounded mode. */
        void reset(int k) {
            capacity = k;
            size = 0;
            int need = k > 0 ? k : 16;
            if (score.length < need) {
                growTo(need);
            }
        }

        void offer(double candidateScore, int candidatePosition, double candidateTravelTime) {
            if (capacity <= 0) {
                if (size == score.length) {
                    growTo(size + 1);
                }
                store(size++, candidateScore, candidatePosition, candidateTravelTime);
                return;
            }
            if (size < capacity) {
                store(size, candidateScore, candidatePosition, candidateTravelTime);
                siftUp(size++);
                return;
            }
            // Heap is full: the root is the worst kept pair. Positions are unique and scanned in
            // ascending order, so this comparison is never an exact tie against the root.
            if (candidateScore < score[0]
                    || (candidateScore == score[0] && candidatePosition < position[0])) {
                store(0, candidateScore, candidatePosition, candidateTravelTime);
                siftDown();
            }
        }

        boolean isEmpty() {
            return size == 0;
        }

        /** Number of candidates currently kept. */
        int size() {
            return size;
        }

        /** Mean over the KEPT set — the post-pruning semantics the repos-time resolver expects. */
        double meanTravelTime() {
            double sum = 0.0;
            for (int i = 0; i < size; i++) {
                sum += travelTime[i];
            }
            return sum / size;
        }

        /**
         * Candidate POSITIONS of the kept set, in heap order.
         *
         * <p>The pass itself only reads {@link #meanTravelTime()} — the successor lists are no
         * longer materialised. This accessor is what makes the selection observable, and
         * {@code RidePostProcessorTopKParityTest} asserts on it to prove the heap keeps the same K
         * elements the stable sort did. Without it the only witness to a selection fault would be a
         * mean, which can coincide across different sets.
         */
        int[] positions() {
            return Arrays.copyOf(position, size);
        }

        private void store(int i, double s, int p, double tt) {
            score[i] = s;
            position[i] = p;
            travelTime[i] = tt;
        }

        private void growTo(int need) {
            int n = Math.max(need, Math.max(16, score.length * 2));
            score = Arrays.copyOf(score, n);
            position = Arrays.copyOf(position, n);
            travelTime = Arrays.copyOf(travelTime, n);
        }

        /** True when slot {@code a} sorts AFTER slot {@code b} under (score, position). */
        private boolean worse(int a, int b) {
            return score[a] > score[b] || (score[a] == score[b] && position[a] > position[b]);
        }

        private void siftUp(int i) {
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (!worse(i, parent)) {
                    break;
                }
                swap(i, parent);
                i = parent;
            }
        }

        private void siftDown() {
            int i = 0;
            while (true) {
                int left = 2 * i + 1;
                if (left >= size) {
                    break;
                }
                int worst = left;
                int right = left + 1;
                if (right < size && worse(right, left)) {
                    worst = right;
                }
                if (!worse(worst, i)) {
                    break;
                }
                swap(i, worst);
                i = worst;
            }
        }

        private void swap(int a, int b) {
            double s = score[a]; score[a] = score[b]; score[b] = s;
            int p = position[a]; position[a] = position[b]; position[b] = p;
            double t = travelTime[a]; travelTime[a] = travelTime[b]; travelTime[b] = t;
        }
    }


    /**
     * Do two ASCENDING-sorted index arrays share no element? Replaces
     * {@code Collections.disjoint(Set<Integer>, Set<Integer>)} in the predecessor pass. A single
     * merge walk is O(a+b) with no hashing and no boxing, and it lets the caller hold the request
     * indices as {@code int[]} instead of one HashSet per ride.
     */
    static boolean disjointSorted(int[] a, int[] b) {
        int i = 0;
        int j = 0;
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) return false;
            if (a[i] < b[j]) i++;
            else j++;
        }
        return true;
    }

    /**
     * Sound reachability lower bound for the predecessor/successor spatial pre-filter.
     *
     * <p>An empty vehicle handoff can only be feasible if it covers the straight-line gap
     * within the available time. The network travel time is always {@code >= euclidean / maxSpeed}
     * (a network path is at least the straight-line distance, driven at no more than maxSpeed),
     * so {@code euclidean > maxSpeed * gap} proves {@code routed_tt > gap} and the handoff is
     * infeasible. Returns {@code true} when the pair MIGHT be feasible (must be routed);
     * {@code false} when it is provably infeasible (safe to skip). Uses squared distance to
     * avoid a sqrt in the hot loop.
     *
     * @param gap {@code startTime_j - endTime_i} (>= 0 within the time-window slice)
     * @param maxSpeed conservative upper bound on network speed (m/s)
     * @param dx x-distance between ride i's last dropoff and ride j's first pickup (m)
     * @param dy y-distance between the same two points (m)
     */
    static boolean withinReach(double gap, double maxSpeed, double dx, double dy) {
        if (gap < 0.0) return false;
        double reach = maxSpeed * gap;
        return dx * dx + dy * dy <= reach * reach;
    }

    /**
     * Combined pre-routing keep test: returns {@code true} if the handoff (i-&gt;j) might be
     * feasible/kept and must be routed, {@code false} if it is provably droppable and can be
     * skipped before routing. Applies (a) the time-reach lower bound when {@code spatial} is on,
     * and (b) the absolute distance cap {@code rideDistI * distFactor} when {@code distCap} is on.
     * Both are lossless: (a) is a sound feasibility lower bound; (b) matches the post-route distance
     * filter (euclidean &le; routed, so euclidean &gt; cap =&gt; routed &gt; cap =&gt; post-filter drops it).
     */
    static boolean preRouteKeep(boolean spatial, double gap, double maxSpeed,
                                boolean distCap, double rideDistI, double distFactor,
                                double dx, double dy) {
        if (spatial && !withinReach(gap, maxSpeed, dx, dy)) return false;
        if (distCap) {
            double cap = rideDistI * distFactor;
            if (dx * dx + dy * dy > cap * cap) return false;
        }
        return true;
    }

    private int resolveParallelism() {
        int configured = config.getHeuristicsProcessCount();
        if (configured == 1) {
            return 1;
        }
        if (configured <= 0) {
            return Math.max(1, Runtime.getRuntime().availableProcessors());
        }
        return configured;
    }

    /**
     * Run {@code action} over indices {@code [0, total)} with the worker count bounded
     * to {@code parallelism}. A bare {@link IntStream#parallel()} always uses the common
     * ForkJoinPool, which is sized to all available cores and so ignores the configured
     * {@code heuristicsProcessCount}. Submitting the parallel stream inside a dedicated
     * {@link ForkJoinPool} caps the worker count at exactly {@code parallelism}, honouring
     * the config. {@code parallelism <= 1} runs sequentially, which is also what makes a
     * single-threaded run bit-reproducible (the shared connection cache fills in a fixed
     * order, so post-processing aggregates such as {@code reposTimeMeanOutgoing} are stable).
     */
    private void runIndexedParallel(int total, int parallelism, IntConsumer action) {
        if (parallelism <= 1) {
            for (int i = 0; i < total; i++) {
                action.accept(i);
            }
            return;
        }
        ForkJoinPool pool = new ForkJoinPool(parallelism);
        try {
            pool.submit(() -> IntStream.range(0, total).parallel().forEach(action)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during parallel post-processing", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new RuntimeException("Parallel post-processing failed", cause != null ? cause : e);
        } finally {
            pool.shutdown();
        }
    }

	private static String formatDuration(double seconds) {
		if (!Double.isFinite(seconds) || seconds <= 0) {
			return "0s";
		}

		long totalSeconds = (long) Math.ceil(seconds);
		long hours = TimeUnit.SECONDS.toHours(totalSeconds);
		long minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60;
		long secs = totalSeconds % 60;

		if (hours > 0) {
			return String.format("%dh%02dm%02ds", hours, minutes, secs);
		}
		if (minutes > 0) {
			return String.format("%dm%02ds", minutes, secs);
		}
		return String.format("%ds", secs);
	}

    private double factorial(int n) {
        double result = 1.0;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }

    /**
     * The only surviving output of the handoff pass: ride id -> mean repositioning travel time over
     * the kept top-K successors. The successor and predecessor lists this pass used to return were
     * both dead downstream (see the note on {@code reposTimeByPos}).
     */
    private record PredSucc(Int2DoubleMap reposTimeMeans) {}
}
