package org.matsim.contrib.demand_extraction.algorithm.network;

import java.util.function.LongFunction;

import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;

import it.unimi.dsi.fastutil.longs.LongConsumer;

/**
 * Three-tier connection cache (2026-06-12-connection-cache-memory-design §3): a compact
 * retained tier (never evicted) over a two-generation speculative tier (watermark-evicted).
 * New fills land speculative; generators promote what later phases provably re-read.
 *
 * <p>Eviction is output-invariant: a segment dropped from the speculative tier and later
 * re-routed reproduces bit-identical values, because SpeedyALT (point-to-point fills) and
 * {@code LeastCostPathTree} (batch SSSP fills) yield bit-identical travel time / distance /
 * utility on every OD — the cross-engine value identity guaranteed by the deterministic
 * routing wrapper and guarded at unit level by {@code CrossEngineRoutingDeterminismTest}
 * (403,785 shared cache ODs measured with 0 value diffs at 1%). The fill source therefore
 * does not affect the cached value, so the timing of an eviction cannot affect output.
 */
public final class TieredSegmentCache {

	private final CompactSegmentStore retained = new CompactSegmentStore();
	private final SpeculativeTier speculative = new SpeculativeTier();

	public TravelSegment get(long key) {
		TravelSegment s = retained.get(key);
		return s != null ? s : speculative.get(key);
	}

	/**
	 * Return the cached segment for {@code key}, or fill it via {@code fill} and cache it in the
	 * speculative tier. Single-fill per key under normal (single-threaded-per-key) use; under a
	 * rare get/rotate race the filler may run twice, which is harmless — values are
	 * history-independent (cross-engine identity) and the speculative put is first-write-wins.
	 */
	public TravelSegment computeIfAbsent(long key, LongFunction<TravelSegment> fill) {
		TravelSegment s = get(key);
		if (s != null) return s;
		TravelSegment computed = fill.apply(key);
		speculative.put(key, computed);
		TravelSegment winner = get(key);
		return winner != null ? winner : computed; // racing rotate(): computed is still correct
	}

	/**
	 * Insert {@code seg} into the speculative tier (first-write-wins).
	 *
	 * @return {@code true} iff this call inserted the key — used by the journaling path to
	 *         record each newly-filled key exactly once.
	 */
	public boolean putSpeculative(long key, TravelSegment seg) {
		return speculative.put(key, seg);
	}

	/** Move a key from speculative to retained (no-op if not cached anywhere). */
	public void promote(long key) {
		if (retained.get(key) != null) return;
		TravelSegment s = speculative.get(key);
		if (s == null) return;
		retained.put(key, s);
		speculative.remove(key);
	}

	/**
	 * Mark an SSSP cone complete in the speculative tier.
	 *
	 * @return {@code true} iff this call newly recorded the mark.
	 */
	public boolean markSssp(long ssspKey) {
		return speculative.markSssp(ssspKey);
	}

	public boolean isSsspDone(long ssspKey) {
		return speculative.isSsspDone(ssspKey);
	}

	/** Rotate (drop the oldest generation of) the speculative tier. Watermark-policy call sites only. */
	public void evictSpeculative() {
		speculative.rotate();
	}

	/** Merge the retained overlay into a new frozen snapshot. Single-threaded barriers only. */
	public void compactRetained() {
		retained.compact();
	}

	/** Wipe both tiers and all SSSP marks. Single-threaded call sites only. */
	public void clear() {
		retained.clear();
		speculative.clear();
	}

	/**
	 * Visit every segment key currently cached in either tier, deduped (retained first, then
	 * speculative keys not already in retained). Single-threaded export / journal-drain only.
	 */
	public void forEachKey(LongConsumer action) {
		retained.forEachKey(action);
		speculative.forEachKey(k -> {
			if (!retained.containsKey(k)) {
				action.accept(k);
			}
		});
	}

	/**
	 * Visit every live SSSP mark, deduped. SSSP marks live only in the speculative tier (they are
	 * never promoted to retained), so this delegates straight to it. Single-threaded
	 * export / journal-snapshot only.
	 */
	public void forEachSssp(LongConsumer action) {
		speculative.forEachSssp(action);
	}

	public long retainedSize() { return retained.size(); }
	public long speculativeSize() { return speculative.size(); }
	public long size() { return retainedSize() + speculativeSize(); }
}
