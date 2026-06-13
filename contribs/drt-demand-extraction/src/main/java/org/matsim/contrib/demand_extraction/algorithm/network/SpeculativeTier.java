package org.matsim.contrib.demand_extraction.algorithm.network;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;

/**
 * Evictable speculative tier of the connection cache
 * (2026-06-12-connection-cache-memory-design §3): rejected pair candidates, full SSSP
 * cones, and their ssspCompleted marks. Two generations (young/old); {@link #rotate()}
 * drops the old generation wholesale (O(1) bulk free, recency preserved) — called by
 * the heap-watermark policy at coarse barriers, never per entry.
 *
 * <p>Marks and segments live in the same generation pair so they are always evicted
 * together: a surviving mark over evicted segments would make {@code batchPrecompute}
 * skip the SSSP and leave destinations to point-to-point fills.
 *
 * <p>Generation swap is coarse-synchronized; reads access volatile fields without
 * locking — a get racing a rotate() can miss an entry, which is safe: the caller
 * re-routes, and values are history-independent (single value source), so the
 * recomputed segment is bit-identical.
 */
final class SpeculativeTier {

	private volatile ConcurrentHashMap<Long, TravelSegment> young = new ConcurrentHashMap<>();
	private volatile ConcurrentHashMap<Long, TravelSegment> old = new ConcurrentHashMap<>();
	private volatile Set<Long> youngMarks = ConcurrentHashMap.newKeySet();
	private volatile Set<Long> oldMarks = ConcurrentHashMap.newKeySet();

	TravelSegment get(long key) {
		TravelSegment s = young.get(key);
		return s != null ? s : old.get(key);
	}

	/**
	 * First write wins within the young generation; an identical key may transiently exist in
	 * old too (harmless duplicate, values identical by determinism rule).
	 *
	 * @return {@code true} iff this call inserted the key (no prior mapping in the young
	 *         generation) — used by the journaling path to record each key exactly once.
	 */
	boolean put(long key, TravelSegment seg) {
		return young.putIfAbsent(key, seg) == null;
	}

	void remove(long key) {
		young.remove(key);
		old.remove(key);
	}

	/**
	 * @return {@code true} iff this call newly marked the SSSP key in the young generation.
	 */
	boolean markSssp(long ssspKey) {
		return youngMarks.add(ssspKey);
	}

	boolean isSsspDone(long ssspKey) {
		return youngMarks.contains(ssspKey) || oldMarks.contains(ssspKey);
	}

	/** Drop the old generation; young becomes old. Watermark-policy call sites only. */
	synchronized void rotate() {
		old = young;
		oldMarks = youngMarks;
		young = new ConcurrentHashMap<>();
		youngMarks = ConcurrentHashMap.newKeySet();
	}

	long size() {
		return (long) young.size() + old.size();
	}

	/**
	 * Visit every segment key currently live (young + old generations), deduped. Intended for
	 * the single-threaded export/drain paths.
	 */
	void forEachKey(it.unimi.dsi.fastutil.longs.LongConsumer action) {
		ConcurrentHashMap<Long, TravelSegment> y = young;
		ConcurrentHashMap<Long, TravelSegment> o = old;
		for (Long key : y.keySet()) {
			action.accept(key);
		}
		for (Long key : o.keySet()) {
			if (!y.containsKey(key)) {
				action.accept(key);
			}
		}
	}

	/** Drop both generations and their SSSP marks. Single-threaded call sites only. */
	synchronized void clear() {
		young = new ConcurrentHashMap<>();
		old = new ConcurrentHashMap<>();
		youngMarks = ConcurrentHashMap.newKeySet();
		oldMarks = ConcurrentHashMap.newKeySet();
	}
}
