package org.matsim.contrib.demand_extraction.algorithm.bamas.stub;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;

/**
 * Interns {@link StopLocation} objects to dense {@code int} ids in first-insertion order.
 *
 * <p>Stops are heavily shared in stop-to-stop (S2S) ride enumeration: many rides pass
 * through the same pickup or dropoff stop. Storing a compact {@code int} id per row
 * instead of an object reference reduces per-row memory from ~32 bytes (reference +
 * object header + fields) to 4 bytes, and enables fastutil primitive collections
 * downstream.
 *
 * <h3>Equality semantics</h3>
 * Two {@link StopLocation} instances are equal if and only if their {@code linkId}s are
 * equal (see {@link StopLocation#equals}). Consequently two instances constructed with
 * the same {@code linkId} but different coordinates or snapping penalties are interned to
 * the SAME id. The first-inserted instance is stored for reverse lookup.
 *
 * <h3>Thread safety</h3>
 * Not thread-safe. Use per-thread instances and merge if needed.
 */
public final class StopLocationDictionary {

	/** Maps StopLocation → dense int id. Default return value -1 signals "absent". */
	private final Object2IntOpenHashMap<StopLocation> forward;

	/** Reverse lookup: id → StopLocation (first-inserted instance). */
	private final List<StopLocation> reverse;

	/** Creates an empty dictionary. */
	public StopLocationDictionary() {
		forward = new Object2IntOpenHashMap<>();
		forward.defaultReturnValue(-1);
		reverse = new ArrayList<>();
	}

	/**
	 * Return the dense id for {@code stop}, assigning a new one (= current {@link #size()})
	 * if this stop has not been interned before.
	 *
	 * @param stop the stop to intern; must not be null
	 * @return dense id in [0, size())
	 */
	public int idOf(StopLocation stop) {
		int existing = forward.getInt(stop);
		if (existing != -1) {
			return existing;
		}
		int newId = reverse.size();
		forward.put(stop, newId);
		reverse.add(stop);
		return newId;
	}

	/**
	 * Return the {@link StopLocation} that was first interned under the given {@code id}.
	 *
	 * @param id dense id in [0, size())
	 * @return the stop interned at that id
	 * @throws IndexOutOfBoundsException if {@code id} is negative or >= {@link #size()}
	 */
	public StopLocation byId(int id) {
		if (id < 0 || id >= reverse.size()) {
			throw new IndexOutOfBoundsException(
					"id " + id + " is out of bounds for dictionary of size " + reverse.size());
		}
		return reverse.get(id);
	}

	/** Number of distinct stops interned so far. */
	public int size() {
		return reverse.size();
	}
}
