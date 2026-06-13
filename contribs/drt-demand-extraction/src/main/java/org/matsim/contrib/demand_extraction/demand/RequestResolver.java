package org.matsim.contrib.demand_extraction.demand;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the two request-identity lookups and their collision contract.
 *
 * <p>Paper-2 Extension-2 hub expansion emits virtual request copies that SHARE the
 * parent's {@link DrtRequest#index}, so {@code index != array position} and several
 * requests may collide on one index. Two resolutions exist, and they are NOT
 * interchangeable:
 * <ul>
 *   <li>{@link #byIndex(int)} — last-write-wins over input iteration order. This is
 *       the canonical copy the ride extender resolves set members through;
 *       materialization and selection metrics MUST use the same map so they
 *       reproduce the extender's origins/destinations exactly.</li>
 *   <li>{@link #byPosition(int)} — the exact generation copy at an array position.
 *       Required where a position was recorded at generation time (degree-2 pair
 *       rows record positions because index collisions would pick a different
 *       copy -> wrong/unreachable OD).</li>
 * </ul>
 */
public final class RequestResolver {

    private final DrtRequest[] byPosition;
    private final Map<Integer, DrtRequest> byIndex;

    public RequestResolver(List<DrtRequest> requests) {
        this.byPosition = requests.toArray(new DrtRequest[0]);
        this.byIndex = new HashMap<>();
        for (DrtRequest r : requests) {
            byIndex.put(r.index, r); // last write wins, by contract
        }
    }

    public DrtRequest byIndex(int requestIndex) {
        DrtRequest r = byIndex.get(requestIndex);
        if (r == null) {
            throw new IllegalArgumentException("unknown request index: " + requestIndex);
        }
        return r;
    }

    public DrtRequest byPosition(int position) { return byPosition[position]; }
    public DrtRequest[] positionalArray() { return byPosition; }

    /**
     * The canonical index → request map (last-write-wins, see {@link #byIndex}). Exposed so
     * consumers that still hold a legacy {@code Map<Integer,DrtRequest>} signature resolve
     * through the SAME instance the engine and ride extender share — there is exactly one
     * construction of this map, here, instead of several "built identically" copies. Returns
     * {@code null} on a missing key (the legacy {@code .get()} contract), unlike the throwing
     * {@link #byIndex(int)}.
     */
    public Map<Integer, DrtRequest> indexMap() { return byIndex; }

    public int size() { return byPosition.length; }
}
