package org.matsim.contrib.demand_extraction.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SmallLru} — fixed-capacity LRU built on LinkedHashMap.
 *
 * <p>Used by {@link org.matsim.contrib.demand_extraction.demand.BudgetToConstraintsCalculator}
 * to memoize the result of pooled-ride binary searches per {@code DrtRequest} (4 entries,
 * quantized keys).
 */
class SmallLruTest {

	@Test
	void putAndGet_returnsInsertedValue() {
		SmallLru<Integer, String> lru = new SmallLru<>(4);
		lru.put(1, "a");

		assertEquals("a", lru.get(1));
	}

	@Test
	void get_missingKey_returnsNull() {
		SmallLru<Integer, String> lru = new SmallLru<>(4);

		assertNull(lru.get(42));
	}

	@Test
	void evictsLeastRecentlyUsed_onCapacityOverflow() {
		SmallLru<Integer, String> lru = new SmallLru<>(4);
		lru.put(1, "a");
		lru.put(2, "b");
		lru.put(3, "c");
		lru.put(4, "d");

		// 1 is now MRU because of the get; 2 should be LRU.
		assertEquals("a", lru.get(1));

		// Insert 5: evicts 2 (the LRU).
		lru.put(5, "e");

		assertEquals("a", lru.get(1));
		assertNull(lru.get(2));
		assertEquals("c", lru.get(3));
		assertEquals("d", lru.get(4));
		assertEquals("e", lru.get(5));
	}

	@Test
	void put_existingKey_updatesValueAndRefreshesRecency() {
		SmallLru<Integer, String> lru = new SmallLru<>(2);
		lru.put(1, "a");
		lru.put(2, "b");

		// Re-put 1 — now 2 is the LRU.
		lru.put(1, "a-updated");
		lru.put(3, "c"); // should evict 2

		assertEquals("a-updated", lru.get(1));
		assertNull(lru.get(2));
		assertEquals("c", lru.get(3));
	}

	@Test
	void size_reflectsCurrentEntries_clampedByCapacity() {
		SmallLru<Integer, String> lru = new SmallLru<>(3);
		assertEquals(0, lru.size());
		lru.put(1, "a");
		lru.put(2, "b");
		assertEquals(2, lru.size());
		lru.put(3, "c");
		lru.put(4, "d");
		assertTrue(lru.size() <= 3, "size should never exceed capacity");
	}

	@Test
	void constructor_rejectsNonPositiveCapacity() {
		assertThrows(IllegalArgumentException.class, () -> new SmallLru<Integer, String>(0));
		assertThrows(IllegalArgumentException.class, () -> new SmallLru<Integer, String>(-1));
	}
}
