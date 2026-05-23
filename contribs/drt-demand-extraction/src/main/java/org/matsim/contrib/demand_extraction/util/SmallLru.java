package org.matsim.contrib.demand_extraction.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fixed-capacity LRU cache backed by a {@link LinkedHashMap} in access order.
 *
 * <p>Used by {@link org.matsim.contrib.demand_extraction.demand.BudgetToConstraintsCalculator}
 * to memoize the result of pooled-ride binary searches per {@code DrtRequest}.
 * Capacity is typically small (4 entries) — the cache amortizes redundant
 * searches within a single ordering-enumeration burst where multiple sibling
 * orderings of the same passenger evaluate against quantization-equivalent
 * params.
 *
 * <p>Not thread-safe. Each {@code DrtRequest} owns its own cache; concurrent
 * enumeration touches different requests.
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

	public V get(K key) {
		return map.get(key);
	}

	public void put(K key, V value) {
		map.put(key, value);
	}

	public int size() {
		return map.size();
	}
}
