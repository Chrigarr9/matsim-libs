package org.matsim.contrib.demand_extraction.scoring;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.router.RoutingModule;
import org.matsim.core.router.RoutingRequest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RoutingOverrideManager} and {@link OverridableRoutingModule}.
 *
 * <p>Verifies override/clear semantics, delegation to wrapped module, and
 * thread-local isolation.
 */
class RoutingOverrideTest {

	@AfterEach
	void cleanup() {
		// Ensure ThreadLocal is clean after each test
		RoutingOverrideManager.clear();
	}

	// ---- RoutingOverrideManager tests ----

	@Test
	void testSetAndGet() {
		List<PlanElement> elements = createTestElements("car", 600.0, 5000.0);

		assertNull(RoutingOverrideManager.get(), "Should be null before set");
		assertFalse(RoutingOverrideManager.hasOverride());

		RoutingOverrideManager.set(elements);

		assertSame(elements, RoutingOverrideManager.get(), "Should return the same list");
		assertTrue(RoutingOverrideManager.hasOverride());
	}

	@Test
	void testClear() {
		List<PlanElement> elements = createTestElements("walk", 120.0, 100.0);

		RoutingOverrideManager.set(elements);
		assertTrue(RoutingOverrideManager.hasOverride());

		RoutingOverrideManager.clear();

		assertNull(RoutingOverrideManager.get(), "Should be null after clear");
		assertFalse(RoutingOverrideManager.hasOverride());
	}

	@Test
	void testHasOverride() {
		assertFalse(RoutingOverrideManager.hasOverride(), "Initially no override");

		RoutingOverrideManager.set(List.of());
		assertTrue(RoutingOverrideManager.hasOverride(), "Empty list is still an override");

		RoutingOverrideManager.clear();
		assertFalse(RoutingOverrideManager.hasOverride(), "After clear, no override");
	}

	// ---- OverridableRoutingModule tests ----

	@Test
	void testOverrideReturnsOverrideElements() {
		// Set up override elements
		List<PlanElement> overrideElements = createTestElements("drt", 450.0, 3000.0);

		// Create a delegate that should NOT be called
		RoutingModule mockDelegate = request -> {
			fail("Delegate should not be called when override is set");
			return null;
		};

		OverridableRoutingModule module = new OverridableRoutingModule(mockDelegate);

		RoutingOverrideManager.set(overrideElements);
		try {
			List<? extends PlanElement> result = module.calcRoute(null);
			assertSame(overrideElements, result,
					"Should return override elements, not delegate to wrapped module");
		} finally {
			RoutingOverrideManager.clear();
		}
	}

	@Test
	void testNoOverrideDelegatesToWrapped() {
		// Create a delegate that returns known elements
		List<PlanElement> delegateElements = createTestElements("car", 900.0, 8000.0);
		RoutingModule delegate = request -> delegateElements;

		OverridableRoutingModule module = new OverridableRoutingModule(delegate);

		// No override set -- should delegate
		List<? extends PlanElement> result = module.calcRoute(null);
		assertSame(delegateElements, result,
				"Should delegate to wrapped module when no override is set");
	}

	@Test
	void testClearCausesSubsequentCallsToDelegate() {
		List<PlanElement> overrideElements = createTestElements("drt", 300.0, 2000.0);
		List<PlanElement> delegateElements = createTestElements("car", 600.0, 5000.0);
		RoutingModule delegate = request -> delegateElements;

		OverridableRoutingModule module = new OverridableRoutingModule(delegate);

		// With override: returns override
		RoutingOverrideManager.set(overrideElements);
		assertSame(overrideElements, module.calcRoute(null));

		// After clear: delegates
		RoutingOverrideManager.clear();
		assertSame(delegateElements, module.calcRoute(null));
	}

	// ---- Thread safety tests ----

	@Test
	void testOverrideIsThreadLocal() throws InterruptedException {
		// Set override on main thread
		List<PlanElement> mainElements = createTestElements("drt", 600.0, 5000.0);
		RoutingOverrideManager.set(mainElements);

		// Verify main thread sees the override
		assertTrue(RoutingOverrideManager.hasOverride());
		assertSame(mainElements, RoutingOverrideManager.get());

		// Spawn another thread and verify it does NOT see main thread's override
		CountDownLatch ready = new CountDownLatch(1);
		AtomicReference<Boolean> otherHasOverride = new AtomicReference<>();
		AtomicReference<List<? extends PlanElement>> otherGet = new AtomicReference<>();

		Thread other = new Thread(() -> {
			otherHasOverride.set(RoutingOverrideManager.hasOverride());
			otherGet.set(RoutingOverrideManager.get());
			ready.countDown();
		});
		other.start();
		ready.await();

		assertFalse(otherHasOverride.get(),
				"Override set on main thread should NOT be visible on other thread");
		assertNull(otherGet.get(),
				"Other thread should see null override");

		// Main thread still has its override
		assertTrue(RoutingOverrideManager.hasOverride());
		assertSame(mainElements, RoutingOverrideManager.get());
	}

	@Test
	void testThreadIsolationWithDifferentOverrides() throws InterruptedException {
		CountDownLatch thread1Set = new CountDownLatch(1);
		CountDownLatch thread2Set = new CountDownLatch(1);
		CountDownLatch bothRead = new CountDownLatch(2);

		List<PlanElement> elements1 = createTestElements("drt", 100.0, 1000.0);
		List<PlanElement> elements2 = createTestElements("car", 200.0, 2000.0);

		AtomicReference<List<? extends PlanElement>> thread1Saw = new AtomicReference<>();
		AtomicReference<List<? extends PlanElement>> thread2Saw = new AtomicReference<>();

		Thread t1 = new Thread(() -> {
			RoutingOverrideManager.set(elements1);
			thread1Set.countDown();
			try { thread2Set.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
			// After both threads have set their overrides, read
			thread1Saw.set(RoutingOverrideManager.get());
			bothRead.countDown();
			RoutingOverrideManager.clear();
		});

		Thread t2 = new Thread(() -> {
			RoutingOverrideManager.set(elements2);
			thread2Set.countDown();
			try { thread1Set.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
			// After both threads have set their overrides, read
			thread2Saw.set(RoutingOverrideManager.get());
			bothRead.countDown();
			RoutingOverrideManager.clear();
		});

		t1.start();
		t2.start();
		bothRead.await();

		assertSame(elements1, thread1Saw.get(),
				"Thread 1 should see its own override, not thread 2's");
		assertSame(elements2, thread2Saw.get(),
				"Thread 2 should see its own override, not thread 1's");
	}

	@Test
	void testOverridableModuleThreadSafety() throws InterruptedException {
		List<PlanElement> overrideElements = createTestElements("drt", 300.0, 2000.0);
		List<PlanElement> delegateElements = createTestElements("car", 600.0, 5000.0);
		RoutingModule delegate = request -> delegateElements;
		OverridableRoutingModule module = new OverridableRoutingModule(delegate);

		// Main thread sets override
		RoutingOverrideManager.set(overrideElements);

		// Other thread should delegate (no override for it)
		CountDownLatch ready = new CountDownLatch(1);
		AtomicReference<List<? extends PlanElement>> otherResult = new AtomicReference<>();

		Thread other = new Thread(() -> {
			otherResult.set(module.calcRoute(null));
			ready.countDown();
		});
		other.start();
		ready.await();

		// Main thread gets override
		assertSame(overrideElements, module.calcRoute(null),
				"Main thread should get override elements");

		// Other thread got delegate
		assertSame(delegateElements, otherResult.get(),
				"Other thread should get delegate elements (no override on its thread)");
	}

	// ---- Helpers ----

	/**
	 * Create a simple list of plan elements with a single leg.
	 */
	private List<PlanElement> createTestElements(String mode, double travelTime, double distance) {
		Leg leg = PopulationUtils.createLeg(mode);
		leg.setTravelTime(travelTime);
		var route = RouteUtils.createGenericRouteImpl(
				Id.createLinkId("from"), Id.createLinkId("to"));
		route.setDistance(distance);
		route.setTravelTime(travelTime);
		leg.setRoute(route);

		List<PlanElement> elements = new ArrayList<>();
		elements.add(leg);
		return elements;
	}
}
