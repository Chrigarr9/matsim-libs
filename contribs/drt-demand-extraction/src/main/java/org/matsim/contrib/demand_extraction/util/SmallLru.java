package org.matsim.contrib.demand_extraction.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fixed-capacity LRU cache backed by a {@link LinkedHashMap} in access order.
 *
 * <p>Used by {@link org.matsim.contrib.demand_extraction.demand.BudgetToConstraintsCalculator}
 * to memoize the result of pooled-ride binary searches per {@code DrtRequest}.
 * Capacity is typically small (4 entries) — the cache amortizes redundant
 * searches within an enumeration burst where sibling orderings of the same
 * passenger evaluate against quantization-equivalent params.
 *
 * <p>Thread-safe by coarse synchronization on the cache instance. Stage 1's
 * {@code parallelStream} can invoke the calculator on the same {@code DrtRequest}
 * from multiple worker threads when that request appears in several source
 * rides; accessOrder={@code true} {@link LinkedHashMap} mutates its internal
 * linked-list on every {@code get}, so unsynchronized concurrent access would
 * corrupt the cache. Contention is per-{@code DrtRequest} and the critical
 * sections are O(1) — the cost is negligible compared to a 15+-iteration
 * binary search.
 */
public final class SmallLru<K, V> {

	private final int capacity;
	private final LinkedHashMap<K, V> map;

	public SmallLru(int capacity) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("capacity must be positive, got " + capacity);
		}
		this.capacity = capacity;
		this.map = new LinkedHashMap<>(capacity, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
				return size() > SmallLru.this.capacity;
			}
		};
	}

	public synchronized V get(K key) {
		return map.get(key);
	}

	public synchronized void put(K key, V value) {
		map.put(key, value);
	}

	public synchronized int size() {
		return map.size();
	}
}
