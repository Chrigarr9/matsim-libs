package org.matsim.contrib.demand_extraction.algorithm.extension;

import java.util.concurrent.ConcurrentLinkedQueue;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

/**
 * Push-based forbidden-prefix index for ordering pruning.
 *
 * <p>Records ordered stop sequences known to be infeasible (a passenger times out).
 * Each record (s_0, ..., s_m) is stored as: index[(s_0, ..., s_{m-1})] += s_m.
 * During B&amp;B descent the {@code OrderingEnumerator} maintains a per-set forbidden
 * set: on each placement, look up sub-sequences of placed stops ending at the new
 * stop and union the resulting forbidden completions; on backtrack, pop the delta.
 *
 * <p>Stops use the unified encoding: origin of request i = {@code 2*i}, destination
 * = {@code 2*i + 1}. The same index handles both origin and dest phases.
 *
 * <p>Keys use fastutil's {@link IntArrayList} as the map key so that lookup uses
 * content equality (inherited from {@code AbstractIntList}). This avoids the silent
 * false-positive prunes that a hash-only key could produce on a 64-bit hash collision
 * across millions of distinct prefixes.
 *
 * <p>Thread-safe recording via {@link #recordPending} (lock-free queue), flushed via
 * {@link #commit} between degrees. Lookups read the committed map — safe for
 * concurrent reads, no synchronization at lookup time.
 */
public final class ForbiddenPrefixIndex {

	private final Object2ObjectOpenHashMap<IntArrayList, IntOpenHashSet> committed = new Object2ObjectOpenHashMap<>();
	private final ConcurrentLinkedQueue<int[]> pending = new ConcurrentLinkedQueue<>();

	private int maxRecordedKeyLength = 0; // updated at commit

	/**
	 * Record an infeasible ordered stop sequence. The last element is the
	 * "forbidden next" given the prefix of all earlier elements. Thread-safe.
	 *
	 * <p>The input array is copied on enqueue and may be safely reused or mutated
	 * by the caller after this method returns.
	 *
	 * @param sequence ordered stop IDs, length >= 3 (length-2 records would create
	 *                 length-1 keys, which the shareability graph already handles)
	 */
	public void recordPending(int[] sequence) {
		if (sequence.length < 3) return;
		pending.add(sequence.clone());
	}

	/**
	 * Merge pending recordings into the committed map. Call between degrees.
	 *
	 * <p>Single-threaded — call between degrees, not concurrently with other commits.
	 */
	public void commit() {
		int[] seq;
		while ((seq = pending.poll()) != null) {
			int prefixLen = seq.length - 1;
			int last = seq[prefixLen];
			IntArrayList key = new IntArrayList(seq, 0, prefixLen);
			IntOpenHashSet set = committed.get(key);
			if (set == null) {
				set = new IntOpenHashSet(2);
				committed.put(key, set);
			}
			set.add(last);
			if (prefixLen > maxRecordedKeyLength) maxRecordedKeyLength = prefixLen;
		}
	}

	/** Look up the forbidden-next set for an ordered prefix. Returns null if no entry. */
	public IntOpenHashSet lookup(int[] prefix) {
		// Wrap without copying: IntArrayList.wrap aliases the backing array, and
		// the lookup does not retain the key beyond the call.
		return committed.get(IntArrayList.wrap(prefix));
	}

	/** Maximum prefix length seen across all committed entries. */
	public int getMaxRecordedKeyLength() {
		return maxRecordedKeyLength;
	}

	/** Number of distinct prefix keys committed. */
	public int size() {
		return committed.size();
	}
}
