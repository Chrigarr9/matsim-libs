package org.matsim.contrib.demand_extraction.algorithm.extension;

import java.util.Arrays;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

/**
 * Per-set push-based forbidden-set cursor.
 *
 * <p>Tracks the placed-stop prefix during a single B&amp;B descent. On each placement,
 * looks up subsequences of prior placements ending at the newly placed stop in the
 * {@link ForbiddenPrefixIndex} and unions the forbidden-next sets into a per-depth
 * delta. On backtrack, removes the delta from the active forbidden set so that
 * undo is O(|delta|).
 *
 * <p>Subsequence semantics: a recorded sequence {@code (s_0, ..., s_m)} fires
 * whenever the placed prefix contains {@code (s_0, ..., s_{m-1})} as an ordered
 * sub-sequence (not necessarily contiguous). By the triangle inequality, inserting
 * extra stops between recorded positions only lengthens the victim's in-vehicle
 * time, so the same passenger still times out.
 *
 * <p>NOT thread-safe — one cursor per worker thread per set.
 */
public final class ForbiddenPrefixCursor {

	private final ForbiddenPrefixIndex index;
	private final int maxKeyLength;

	private final int[] placed;
	private int depth = 0;

	private final IntOpenHashSet forbiddenSet = new IntOpenHashSet();
	private final IntOpenHashSet[] deltaStack;

	private final int[] scratchKey;

	public ForbiddenPrefixCursor(ForbiddenPrefixIndex index, int capacity) {
		this.index = index;
		this.maxKeyLength = Math.max(2, index.getMaxRecordedKeyLength());
		this.placed = new int[capacity];
		this.deltaStack = new IntOpenHashSet[capacity];
		for (int i = 0; i < capacity; i++) {
			this.deltaStack[i] = new IntOpenHashSet();
		}
		this.scratchKey = new int[maxKeyLength];
	}

	/** Returns true if the given stop is currently forbidden as the next placement. */
	public boolean isForbidden(int stop) {
		return forbiddenSet.contains(stop);
	}

	/** Current placement depth. */
	public int depth() {
		return depth;
	}

	/**
	 * Place a stop at the current depth. Updates {@link #forbiddenSet} by looking up
	 * all ordered subsequences of prior placements ending at the new stop, of length
	 * 2..{@code maxKeyLength}.
	 */
	public void place(int stop) {
		if (depth >= placed.length) {
			throw new IllegalStateException("ForbiddenPrefixCursor capacity " + placed.length + " exceeded");
		}
		placed[depth] = stop;
		IntOpenHashSet additions = deltaStack[depth];
		additions.clear();

		int maxL = Math.min(maxKeyLength, depth + 1);
		for (int L = 2; L <= maxL; L++) {
			scratchKey[L - 1] = stop;
			enumerate(0, 0, L - 1, additions, L);
		}

		depth++;
	}

	/** Roll back the most recent placement. */
	public void unplace() {
		if (depth == 0) {
			throw new IllegalStateException("ForbiddenPrefixCursor.unplace called with depth=0");
		}
		depth--;
		IntOpenHashSet additions = deltaStack[depth];
		for (int s : additions) {
			forbiddenSet.remove(s);
		}
	}

	/**
	 * Recursively pick {@code needed} ordered positions from {@code placed[start..depth-1]},
	 * append the just-placed stop (already in {@code scratchKey[totalLen-1]}), look up the
	 * resulting prefix in the index, and union any forbidden completions into
	 * {@code additions} — only the elements not already present in {@code forbiddenSet}, so
	 * that {@link #unplace()} removes exactly what this {@link #place(int)} added.
	 */
	private void enumerate(int start, int chosen, int needed, IntOpenHashSet additions, int totalLen) {
		if (chosen == needed) {
			IntOpenHashSet forbidden = index.lookup(Arrays.copyOf(scratchKey, totalLen));
			if (forbidden != null) {
				for (int s : forbidden) {
					if (forbiddenSet.add(s)) {
						additions.add(s);
					}
				}
			}
			return;
		}
		int remaining = needed - chosen;
		for (int p = start; p <= depth - remaining; p++) {
			scratchKey[chosen] = placed[p];
			enumerate(p + 1, chosen + 1, needed, additions, totalLen);
		}
	}
}
