package org.matsim.contrib.demand_extraction.algorithm.network;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Never-evicted retained tier of the connection cache
 * (2026-06-12-connection-cache-memory-design §3).
 *
 * <p>Two layers: an immutable frozen snapshot (open-addressed {@code long -> slot}
 * index into parallel double arrays, ~40 B/entry, safe for lock-free concurrent reads
 * because it is never mutated after construction) and a boxed CHM overlay for entries
 * promoted since the last {@link #compact()}. Compaction merges the overlay into a new
 * frozen snapshot; it must only be called from single-threaded barriers (end of pair
 * generation, degree barriers).
 *
 * <p>First-write-wins: by the value-source determinism rule every fill for a key
 * carries bit-identical values, so overwrites are pointless work and are skipped.
 */
final class CompactSegmentStore {

	/** Frozen snapshot. Replaced wholesale by compact(); readers see old or new, both complete. */
	private volatile Frozen frozen = Frozen.EMPTY;
	private final ConcurrentHashMap<Long, TravelSegment> overlay = new ConcurrentHashMap<>();

	TravelSegment get(long key) {
		Frozen f = frozen;
		int slot = f.index.getOrDefault(key, -1);
		if (slot >= 0) {
			return new TravelSegment(f.tt[slot], f.dist[slot], f.util[slot]);
		}
		return overlay.get(key);
	}

	void put(long key, TravelSegment seg) {
		if (frozen.index.containsKey(key)) {
			return; // first write wins
		}
		overlay.putIfAbsent(key, seg);
	}

	/** Merge overlay into a new frozen snapshot. Call only from single-threaded barriers. */
	void compact() {
		if (overlay.isEmpty()) {
			return;
		}
		Frozen old = frozen;
		int n = old.size + overlay.size();
		Long2IntOpenHashMap index = new Long2IntOpenHashMap(n);
		index.defaultReturnValue(-1);
		double[] tt = new double[n];
		double[] dist = new double[n];
		double[] util = new double[n];
		int slot = 0;
		for (Long2IntMap.Entry e : old.index.long2IntEntrySet()) {
			int oldSlot = e.getIntValue();
			index.put(e.getLongKey(), slot);
			tt[slot] = old.tt[oldSlot];
			dist[slot] = old.dist[oldSlot];
			util[slot] = old.util[oldSlot];
			slot++;
		}
		for (Map.Entry<Long, TravelSegment> e : overlay.entrySet()) {
			long key = e.getKey();
			if (index.containsKey(key)) {
				continue; // frozen copy wins (first write)
			}
			index.put(key, slot);
			tt[slot] = e.getValue().getTravelTime();
			dist[slot] = e.getValue().getDistance();
			util[slot] = e.getValue().getNetworkUtility();
			slot++;
		}
		frozen = new Frozen(index, tt, dist, util, slot);
		overlay.clear();
	}

	int size() {
		return frozen.size + overlay.size();
	}

	boolean containsKey(long key) {
		return frozen.index.containsKey(key) || overlay.containsKey(key);
	}

	/**
	 * Visit every key currently retained (frozen snapshot + overlay), deduped. Intended for
	 * the single-threaded export/drain paths; concurrent compaction may shift the snapshot
	 * but every key present at call time is visited at least once.
	 */
	void forEachKey(it.unimi.dsi.fastutil.longs.LongConsumer action) {
		Frozen f = frozen;
		for (Long2IntMap.Entry e : f.index.long2IntEntrySet()) {
			action.accept(e.getLongKey());
		}
		for (Long key : overlay.keySet()) {
			if (!f.index.containsKey((long) key)) {
				action.accept(key);
			}
		}
	}

	/** Remove all entries (frozen snapshot + overlay). Single-threaded call sites only. */
	void clear() {
		frozen = Frozen.EMPTY;
		overlay.clear();
	}

	private static final class Frozen {
		static final Frozen EMPTY =
				new Frozen(emptyIndex(), new double[0], new double[0], new double[0], 0);

		final Long2IntOpenHashMap index; // never mutated after construction
		final double[] tt;
		final double[] dist;
		final double[] util;
		final int size;

		Frozen(Long2IntOpenHashMap index, double[] tt, double[] dist, double[] util, int size) {
			this.index = index;
			this.tt = tt;
			this.dist = dist;
			this.util = util;
			this.size = size;
		}

		private static Long2IntOpenHashMap emptyIndex() {
			Long2IntOpenHashMap m = new Long2IntOpenHashMap(0);
			m.defaultReturnValue(-1);
			return m;
		}
	}
}
