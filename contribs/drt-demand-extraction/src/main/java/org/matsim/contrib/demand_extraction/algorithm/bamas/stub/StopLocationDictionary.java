package org.matsim.contrib.demand_extraction.algorithm.bamas.stub;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
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
 * <h3>Interning identity — full stop, NOT linkId-only</h3>
 * Stops are interned by their FULL identity — {@code (linkId, coord.x, coord.y,
 * snappingPenalty)} — rather than by {@link StopLocation#equals} (which is linkId-only).
 * This matters because a stop's coordinate is request-derived: stop finders snap to the
 * closest point on the link to the <em>centroid of the served passengers' locations</em>
 * (see {@code NetworkLinkStopFinder}, {@code GeometricStopFinder}). Two S2S rides can
 * therefore pin DIFFERENT coordinates on the SAME link — most visibly for upsampled
 * duplicate persons whose jittered origins shift the centroid by centimetres.
 *
 * <p>Interning by linkId-only would collapse those distinct-coordinate stops to a single
 * id and replay the first-inserted instance's coordinate for all of them, so the stub
 * path's {@code pickupStopX/Y} and {@code snappingPenalty} columns would diverge from the
 * fat path by ~0.01 m and break {@code exmas_rides.csv} byte-parity (Plan A2). Interning
 * by full identity makes {@link #byId(int)} return the EXACT stop the fat ride carried, so
 * the round-trip is bit-exact. Stops that are genuinely identical (same link, same coord,
 * same penalty) still dedup to one id, so the memory win is preserved for the common case;
 * only genuinely-distinct stops cost an extra entry (negligible — tens of MB at 100%).
 *
 * <h3>Thread safety</h3>
 * Not thread-safe. Use per-thread instances and merge if needed.
 */
public final class StopLocationDictionary {

	/**
	 * Full-identity interning key. Two stops dedup to one id iff their link, coordinate and
	 * snapping penalty are all bit-equal (record {@code equals} compares doubles via
	 * {@link Double#compare}). This is deliberately stricter than {@link StopLocation#equals}
	 * (linkId-only) so the reverse lookup replays the exact coordinate — required for
	 * {@code exmas_rides.csv} byte-parity between the fat and stub paths.
	 */
	private record StopKey(Id<Link> linkId, double x, double y, double snappingPenalty) {
		static StopKey of(StopLocation stop) {
			return new StopKey(stop.getLinkId(), stop.getCoord().getX(), stop.getCoord().getY(),
					stop.getSnappingPenalty());
		}
	}

	/** Maps full-identity StopKey → dense int id. Default return value -1 signals "absent". */
	private final Object2IntOpenHashMap<StopKey> forward;

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
	 * if a stop with this exact full identity ({@code linkId}, coordinate, snapping penalty)
	 * has not been interned before.
	 *
	 * @param stop the stop to intern; must not be null
	 * @return dense id in [0, size())
	 */
	public int idOf(StopLocation stop) {
		StopKey key = StopKey.of(stop);
		int existing = forward.getInt(key);
		if (existing != -1) {
			return existing;
		}
		int newId = reverse.size();
		forward.put(key, newId);
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
