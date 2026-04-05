package org.matsim.contrib.demand_extraction.algorithm.extension;

import java.util.concurrent.ConcurrentLinkedQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Stores proven-infeasible stop subsequences for ordering enumeration pruning.
 *
 * <p>Each request contributes two stops: origin ({@code requestIndex << 1}) and
 * destination ({@code (requestIndex << 1) | 1}). A conflict is an ordered subsequence
 * of stop IDs that, whenever it appears in a route, causes a passenger to time out.
 *
 * <p>Conflicts transfer across sets and degrees by the triangle inequality: inserting
 * stops between any two points can only increase travel time.
 *
 * <p>Thread safety: record via {@link #recordPending} (lock-free queue), then merge
 * via {@link #commit} between degrees. Lookups via {@link #hasConflict} read from
 * committed sets — safe for concurrent reads.
 */
public final class OrderingConflicts {

	private static final long HASH_PRIME = 1000003L;
	private static final int MIN_LENGTH = 3;

	private final LongOpenHashSet[] byLength;
	private final int maxLength;
	private final ConcurrentLinkedQueue<long[]> pending = new ConcurrentLinkedQueue<>();

	public OrderingConflicts(int maxLength) {
		this.maxLength = maxLength;
		this.byLength = new LongOpenHashSet[maxLength + 1];
		for (int i = MIN_LENGTH; i <= maxLength; i++) {
			byLength[i] = new LongOpenHashSet();
		}
	}

	public static int originStop(int requestIndex) { return requestIndex << 1; }
	public static int destStop(int requestIndex) { return (requestIndex << 1) | 1; }

	public static long hash(int[] stops, int from, int to) {
		long h = 0;
		for (int i = from; i < to; i++) {
			h = h * HASH_PRIME + stops[i];
		}
		return h;
	}

	public void recordPending(int[] stopSequence, int from, int to) {
		int len = to - from;
		if (len < MIN_LENGTH || len > maxLength) return;
		long h = hash(stopSequence, from, to);
		pending.add(new long[]{len, h});
	}

	public void commit() {
		long[] entry;
		while ((entry = pending.poll()) != null) {
			int len = (int) entry[0];
			if (len >= MIN_LENGTH && len <= maxLength) {
				byLength[len].add(entry[1]);
			}
		}
	}

	/**
	 * Check if adding candidateStop to the current path creates a known conflict.
	 * Enumerates all ordered subsequences of pathStops[0..pathLength-1],
	 * appends candidateStop, hashes, and checks.
	 */
	public boolean hasConflict(int[] pathStops, int pathLength, int candidateStop) {
		int maxL = Math.min(maxLength, pathLength + 1);
		for (int L = MIN_LENGTH; L <= maxL; L++) {
			LongOpenHashSet set = byLength[L];
			if (set.isEmpty()) continue;
			if (enumerateAndCheck(pathStops, pathLength, candidateStop, L - 1, set, 0, 0L)) {
				return true;
			}
		}
		return false;
	}

	public static boolean hasConflictSafe(OrderingConflicts conflicts,
										   int[] pathStops, int pathLength, int candidateStop) {
		return conflicts != null && conflicts.hasConflict(pathStops, pathLength, candidateStop);
	}

	private boolean enumerateAndCheck(int[] path, int pathLen, int candidate,
									   int remaining, LongOpenHashSet set,
									   int startIdx, long partialHash) {
		if (remaining == 0) {
			long fullHash = partialHash * HASH_PRIME + candidate;
			return set.contains(fullHash);
		}
		int maxStart = pathLen - remaining;
		for (int i = startIdx; i <= maxStart; i++) {
			long newHash = partialHash * HASH_PRIME + path[i];
			if (enumerateAndCheck(path, pathLen, candidate, remaining - 1, set, i + 1, newHash)) {
				return true;
			}
		}
		return false;
	}

	public int getConflictCount() {
		int total = 0;
		for (int i = MIN_LENGTH; i <= maxLength; i++) {
			if (byLength[i] != null) total += byLength[i].size();
		}
		return total;
	}

	public int getConflictCount(int length) {
		if (length < MIN_LENGTH || length > maxLength) return 0;
		return byLength[length] != null ? byLength[length].size() : 0;
	}

	public int getMaxLength() { return maxLength; }
}
