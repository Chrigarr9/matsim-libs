package org.matsim.contrib.demand_extraction.scoring;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.BudgetToConstraintsCalculator;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.examples.ExamplesUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BudgetToConstraintsCalculator}'s iterative binary search.
 *
 * <p>Uses a {@link PlanCalcScoreAdapter} with known parameters so that we can
 * verify the binary search converges to the formula-based analytical result.
 *
 * <p>Loads the dvrp-grid example config for a valid DRT configuration, then
 * overrides scoring parameters with known values for deterministic testing.
 */
class BinarySearchConvergenceTest {

	// Known scoring parameters
	private static final double MARG_UTIL_TRAVELING_HR = -6.0;   // utils/hr for DRT
	private static final double MARG_UTIL_DISTANCE_M = -0.0001;  // utils/m for DRT
	private static final double MONETARY_DIST_RATE = -0.0002;     // EUR/m for DRT
	private static final double MARG_UTIL_MONEY = 1.0;            // utils/EUR
	private static final double PERFORMING_HR = 6.0;               // utils/hr
	private static final double MARG_UTIL_WAITING_PT_HR = -6.0;   // utils/hr
	private static final double WALK_MARG_UTIL_TRAV_HR = -3.0;    // utils/hr
	private static final double WALK_MARG_UTIL_DIST_M = -0.0005;  // utils/m

	private BudgetToConstraintsCalculator calculator;
	private BudgetValidator budgetValidator;
	private Person testPerson;

	@BeforeEach
	void setUp() throws MalformedURLException {
		// Load base config from dvrp-grid example to get a valid DRT configuration
		URL scenarioUrl = ExamplesUtils.getTestScenarioURL("dvrp-grid");
		Config config = ConfigUtils.loadConfig(
				new URL(scenarioUrl, "one_shared_taxi_config.xml").toString(),
				new MultiModeDrtConfigGroup(),
				new DvrpConfigGroup(),
				new ExMasConfigGroup());
		config.removeModule("otfvis");

		// Override scoring parameters with known values
		ScoringConfigGroup scoring = config.scoring();
		scoring.setMarginalUtilityOfMoney(MARG_UTIL_MONEY);
		scoring.setPerforming_utils_hr(PERFORMING_HR);
		scoring.setMarginalUtlOfWaitingPt_utils_hr(MARG_UTIL_WAITING_PT_HR);

		// DRT mode params
		ScoringConfigGroup.ModeParams drtParams = scoring.getOrCreateModeParams("drt");
		drtParams.setMarginalUtilityOfTraveling(MARG_UTIL_TRAVELING_HR);
		drtParams.setMarginalUtilityOfDistance(MARG_UTIL_DISTANCE_M);
		drtParams.setMonetaryDistanceRate(MONETARY_DIST_RATE);
		drtParams.setConstant(0.0);

		// Walk mode params (zero ASC to prevent interference with DRT trip scoring)
		ScoringConfigGroup.ModeParams walkParams = scoring.getOrCreateModeParams(TransportMode.walk);
		walkParams.setMarginalUtilityOfTraveling(WALK_MARG_UTIL_TRAV_HR);
		walkParams.setMarginalUtilityOfDistance(WALK_MARG_UTIL_DIST_M);
		walkParams.setMonetaryDistanceRate(0.0);
		walkParams.setConstant(0.0);

		// Walk speed
		config.routing().getOrCreateModeRoutingParams(TransportMode.walk).setTeleportedModeSpeed(1.0);

		// ExMAS config
		ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		exMas.setDrtMode("drt");
		exMas.setMaxDetourFactor(3.0);
		exMas.setMinDrtAccessEgressDistance(0.0);
		exMas.setIncludeOpportunityCost(false);
		exMas.setBaseModes(Set.of(TransportMode.car));

		// Build scoring parameters
		ScoringParameters scoringParams = new ScoringParameters.Builder(
				scoring,
				scoring.getScoringParameters(null),
				config.scenario()
		).build();

		ScoringParametersForPerson paramsForPerson = person -> scoringParams;
		PlanCalcScoreAdapter adapter = new PlanCalcScoreAdapter(paramsForPerson);

		testPerson = PopulationUtils.getFactory().createPerson(Id.createPersonId("test"));

		calculator = new BudgetToConstraintsCalculator(config, exMas, adapter);
		budgetValidator = new BudgetValidator(adapter, paramsForPerson, exMas, config);
	}

	/** Build a DrtRequest and attach a scoring context so the calculator's fast path works. */
	private DrtRequest buildRequestWithContext(DrtRequest.Builder builder) {
		DrtRequest request = builder.build();
		request.setScoringContext(budgetValidator.computeScoringContext(request, testPerson));
		return request;
	}

	@Test
	void testBudgetToMaxDetourTime() {
		double directTravelTime = 600.0;  // 10 min
		double directDistance = 5000.0;    // 5 km
		double speed = directDistance / directTravelTime; // ~8.33 m/s

		// Score the ideal DRT trip (direct route, no detour) to find the baseline score
		// ideal DRT: walk(0) + drt(tt=600, dist=5000) + walk(0)
		// drt leg score = 0 + (-6/3600)*600 + (-0.0001)*5000 + 1.0*(-0.0002)*5000
		//               = -1.0 + -0.5 + -1.0 = -2.5
		// opportunity cost (performing=6/hr): -(6/3600)*600 = -1.0
		// total ideal = -3.5
		double idealScore = -3.5;

		// bestModeScore = ideal_score - budget => budget = ideal_score - bestModeScore
		// We want a budget of 2.0 utils
		double budget = 2.0;
		double bestModeScore = idealScore - budget; // = -4.5

		DrtRequest request = buildRequestWithContext(DrtRequest.builder()
				.index(0)
				.personId(Id.createPersonId("test"))
				.groupId("g1")
				.tripIndex(0)
				.budget(budget)
				.bestModeScore(bestModeScore)
				.bestMode(TransportMode.car)
				.originLinkId(Id.createLinkId("link1"))
				.destinationLinkId(Id.createLinkId("link2"))
				.originX(0.0).originY(0.0)
				.destinationX(5000.0).destinationY(0.0)
				.requestTime(28800.0)
				.earliestDeparture(28500.0)
				.latestArrival(30600.0)
				.directTravelTime(directTravelTime)
				.directDistance(directDistance)
				.maxDetourFactor(3.0));

		double maxDetour = calculator.budgetToMaxDetourTime(budget, testPerson,
				directTravelTime, directDistance, request);

		// Verify binary search produces a reasonable result
		assertTrue(maxDetour > 0, "Max detour should be positive for positive budget");

		// Bounded by config: maxDetourFactor=3.0 → max additional = directTT * 2.0 = 1200s
		double configCap = directTravelTime * (3.0 - 1.0);
		assertTrue(maxDetour <= configCap + 5.0,
				"Max detour should be bounded by config cap. Got " + maxDetour + ", cap=" + configCap);

		// Monotonicity: larger budget → more detour
		double smallerBudget = 0.5;
		DrtRequest smallRequest = buildRequestWithContext(request.toBuilder().budget(smallerBudget)
				.bestModeScore(idealScore - smallerBudget));
		double smallDetour = calculator.budgetToMaxDetourTime(smallerBudget, testPerson,
				directTravelTime, directDistance, smallRequest);
		assertTrue(smallDetour <= maxDetour,
				"Smaller budget should produce less or equal detour. small=" + smallDetour + ", large=" + maxDetour);
	}

	@Test
	void testBudgetToMaxWaitingTime() {
		double directTravelTime = 600.0;
		double directDistance = 5000.0;

		double idealScore = -2.5; // same as above
		double budget = 1.5;
		double bestModeScore = idealScore - budget;

		DrtRequest request = buildRequestWithContext(DrtRequest.builder()
				.index(0)
				.personId(Id.createPersonId("test"))
				.groupId("g1")
				.tripIndex(0)
				.budget(budget)
				.bestModeScore(bestModeScore)
				.bestMode(TransportMode.car)
				.originLinkId(Id.createLinkId("link1"))
				.destinationLinkId(Id.createLinkId("link2"))
				.originX(0.0).originY(0.0)
				.destinationX(5000.0).destinationY(0.0)
				.requestTime(28800.0)
				.earliestDeparture(28500.0)
				.latestArrival(30600.0)
				.directTravelTime(directTravelTime)
				.directDistance(directDistance)
				.maxDetourFactor(3.0));

		double maxWait = calculator.budgetToMaxWaitingTime(budget, testPerson, request);

		// Waiting time penalty: margUtilWaitingPt_s * waitTime
		// Formula: maxWait = budget / |margUtilWaitingPt_s|
		double margUtilWaitPt_s = Math.abs(MARG_UTIL_WAITING_PT_HR / 3600.0);
		double expectedWait = budget / margUtilWaitPt_s;

		assertEquals(expectedWait, maxWait, expectedWait * 0.01,
				"Binary search max waiting time should match formula within 1%");
	}

	@Test
	void testBudgetToMaxWalkDistance() {
		double directTravelTime = 600.0;
		double directDistance = 5000.0;

		double idealScore = -2.5;
		double budget = 1.0;
		double bestModeScore = idealScore - budget;

		DrtRequest request = buildRequestWithContext(DrtRequest.builder()
				.index(0)
				.personId(Id.createPersonId("test"))
				.groupId("g1")
				.tripIndex(0)
				.budget(budget)
				.bestModeScore(bestModeScore)
				.bestMode(TransportMode.car)
				.originLinkId(Id.createLinkId("link1"))
				.destinationLinkId(Id.createLinkId("link2"))
				.originX(0.0).originY(0.0)
				.destinationX(5000.0).destinationY(0.0)
				.requestTime(28800.0)
				.earliestDeparture(28500.0)
				.latestArrival(30600.0)
				.directTravelTime(directTravelTime)
				.directDistance(directDistance)
				.maxDetourFactor(3.0));

		double maxWalkDist = calculator.budgetToMaxWalkDistance(budget, testPerson, request);

		// The binary search increases BOTH access and egress equally.
		// A walk distance of `mid` means both access and egress are `mid` meters.
		// Just verify the result is reasonable (formula depends on DRT+walk interaction).
		assertTrue(maxWalkDist > 0,
				"Max walk distance should be positive for positive budget");
		assertTrue(maxWalkDist < 5000.0,
				"Max walk distance should be bounded");
	}

	@Test
	void testZeroBudgetReturnsZero() {
		DrtRequest request = buildRequestWithContext(DrtRequest.builder()
				.index(0)
				.personId(Id.createPersonId("test"))
				.groupId("g1")
				.tripIndex(0)
				.budget(0.0)
				.bestModeScore(-2.5)
				.bestMode(TransportMode.car)
				.originLinkId(Id.createLinkId("link1"))
				.destinationLinkId(Id.createLinkId("link2"))
				.originX(0.0).originY(0.0)
				.destinationX(5000.0).destinationY(0.0)
				.requestTime(28800.0)
				.earliestDeparture(28500.0)
				.latestArrival(30600.0)
				.directTravelTime(600.0)
				.directDistance(5000.0)
				.maxDetourFactor(3.0));

		assertEquals(0.0, calculator.budgetToMaxDetourTime(0.0, testPerson, 600.0, 5000.0, request),
				"Zero budget should return zero max detour time");
		assertEquals(0.0, calculator.budgetToMaxWaitingTime(0.0, testPerson, request),
				"Zero budget should return zero max waiting time");
	}

	/**
	 * Build a request with a scoring context using the given direct travel time/distance.
	 * Budget is set to 2.0 utils so that both the ideal and pooled walk caps are well below
	 * MAX_WALK_UPPER_BOUND_METERS (5000 m) — i.e. the binary search terminates meaningfully.
	 */
	private DrtRequest buildRequest(double directTT, double directDist) {
		double idealScore = -2.5; // DRT leg score at 600s/5km with no walk, no wait (see testBudgetToMaxWalkDistance)
		double budget = 2.0;
		double bestModeScore = idealScore - budget; // = -4.5
		return buildRequestWithContext(DrtRequest.builder()
				.index(0)
				.personId(Id.createPersonId("test"))
				.groupId("g1")
				.tripIndex(0)
				.budget(budget)
				.bestModeScore(bestModeScore)
				.bestMode(TransportMode.car)
				.originLinkId(Id.createLinkId("link1"))
				.destinationLinkId(Id.createLinkId("link2"))
				.originX(0.0).originY(0.0)
				.destinationX(directDist).destinationY(0.0)
				.requestTime(28800.0)
				.earliestDeparture(28500.0)
				.latestArrival(30600.0)
				.directTravelTime(directTT)
				.directDistance(directDist)
				.maxDetourFactor(3.0));
	}

	@Test
	void pooledRideWalkOverload_tightensWhenDetourPresent() {
		DrtRequest request = buildRequest(/* directTT */ 600, /* directDist */ 5000);

		double budget = 2.0;

		double idealCap = calculator.budgetToMaxWalkDistance(budget, testPerson, request);
		double pooledCap = calculator.budgetToMaxWalkDistance(
				budget, testPerson, request,
				/* actualTT */ 900,     // 300 s detour
				/* actualDist */ 6500,
				/* delay */ 60);        // 60 s wait

		assertTrue(pooledCap < idealCap,
				"Pooled cap should be tighter than ideal cap. pooled=" + pooledCap + ", ideal=" + idealCap);
		assertTrue(pooledCap >= calculator.getMinDrtAccessEgressDistance(),
				"Pooled cap should be >= floor. pooled=" + pooledCap + ", floor=" + calculator.getMinDrtAccessEgressDistance());
	}

	@Test
	void pooledRideWalkOverload_returnsFloorWhenBudgetNonPositive() {
		DrtRequest request = buildRequest(600, 5000);

		double cap = calculator.budgetToMaxWalkDistance(
				/* remainingBudget */ -10.0, testPerson, request, 900, 6500, 60);

		assertEquals(calculator.getMinDrtAccessEgressDistance(), cap,
				"Non-positive budget should return floor (minDrtAccessEgressDistance)");
	}

	@Test
	void testNegativeBudgetReturnsZero() {
		DrtRequest request = buildRequestWithContext(DrtRequest.builder()
				.index(0)
				.personId(Id.createPersonId("test"))
				.groupId("g1")
				.tripIndex(0)
				.budget(-1.0)
				.bestModeScore(-1.5)
				.bestMode(TransportMode.car)
				.originLinkId(Id.createLinkId("link1"))
				.destinationLinkId(Id.createLinkId("link2"))
				.originX(0.0).originY(0.0)
				.destinationX(5000.0).destinationY(0.0)
				.requestTime(28800.0)
				.earliestDeparture(28500.0)
				.latestArrival(30600.0)
				.directTravelTime(600.0)
				.directDistance(5000.0)
				.maxDetourFactor(3.0));

		assertEquals(0.0, calculator.budgetToMaxDetourTime(-1.0, testPerson, 600.0, 5000.0, request),
				"Negative budget should return zero max detour time");
		assertEquals(0.0, calculator.budgetToMaxWaitingTime(-1.0, testPerson, request),
				"Negative budget should return zero max waiting time");
	}

	@Test
	void testLargeBudgetHitsConfigCap() {
		DrtRequest request = buildRequestWithContext(DrtRequest.builder()
				.index(0)
				.personId(Id.createPersonId("test"))
				.groupId("g1")
				.tripIndex(0)
				.budget(10000.0)
				.bestModeScore(-10002.5)
				.bestMode(TransportMode.car)
				.originLinkId(Id.createLinkId("link1"))
				.destinationLinkId(Id.createLinkId("link2"))
				.originX(0.0).originY(0.0)
				.destinationX(5000.0).destinationY(0.0)
				.requestTime(28800.0)
				.earliestDeparture(25000.0)
				.latestArrival(40000.0)
				.directTravelTime(600.0)
				.directDistance(5000.0)
				.maxDetourFactor(3.0));

		double maxDetour = calculator.budgetToMaxDetourTime(10000.0, testPerson,
				600.0, 5000.0, request);

		// Max detour is capped by directTravelTime * (maxDetourFactor - 1.0) = 600 * 2.0 = 1200
		double configCap = 600.0 * (3.0 - 1.0);
		assertTrue(maxDetour <= configCap + 5.0,  // +5 for binary search tolerance
				"Max detour should be capped by config maxDetourFactor. Got " + maxDetour + ", cap=" + configCap);

		// With large budget, should max out at the cap
		assertEquals(configCap, maxDetour, 5.0,
				"Very large budget should hit config cap");

		// Waiting time capped at 3600s (1 hour upper bound in binary search)
		double maxWait = calculator.budgetToMaxWaitingTime(10000.0, testPerson, request);
		assertTrue(maxWait <= 3600.0 + 5.0,
				"Max waiting should be capped at 3600s upper bound");
	}
}
