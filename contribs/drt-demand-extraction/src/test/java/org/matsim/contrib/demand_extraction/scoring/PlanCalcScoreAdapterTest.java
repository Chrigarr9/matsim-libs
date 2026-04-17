package org.matsim.contrib.demand_extraction.scoring;

import java.util.List;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Route;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.config.groups.ScenarioConfigGroup;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scoring.functions.ModeUtilityParameters;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PlanCalcScoreAdapter}.
 *
 * Verifies that the adapter produces correct scores for different leg types,
 * handles walk-mode fallback, and reports correct metadata flags.
 */
class PlanCalcScoreAdapterTest {

	// Known scoring parameters for deterministic tests
	private static final double MARG_UTIL_TRAVELING_HR = -6.0;   // utils/hr
	private static final double MARG_UTIL_DISTANCE_M = -0.0001;  // utils/m
	private static final double MONETARY_DIST_RATE = -0.0002;     // EUR/m
	private static final double MARG_UTIL_MONEY = 1.0;            // utils/EUR
	private static final double CONSTANT_CAR = -1.5;              // utils (ASC)
	private static final double MARG_UTIL_WAITING_PT_HR = -3.0;   // utils/hr
	private static final double UTIL_LINE_SWITCH = -1.0;          // utils per switch

	private ScoringParameters scoringParams;
	private PlanCalcScoreAdapter adapter;
	private Person testPerson;

	@BeforeEach
	void setUp() {
		// Build ScoringParameters from ScoringConfigGroup (the standard MATSim way)
		ScoringConfigGroup scoringConfig = new ScoringConfigGroup();
		ScenarioConfigGroup scenarioConfig = new ScenarioConfigGroup();

		// Set global parameters
		scoringConfig.setMarginalUtilityOfMoney(MARG_UTIL_MONEY);
		scoringConfig.setMarginalUtlOfWaitingPt_utils_hr(MARG_UTIL_WAITING_PT_HR);
		scoringConfig.setUtilityOfLineSwitch(UTIL_LINE_SWITCH);

		// Configure car mode
		ScoringConfigGroup.ModeParams carParams = scoringConfig.getOrCreateModeParams(TransportMode.car);
		carParams.setMarginalUtilityOfTraveling(MARG_UTIL_TRAVELING_HR);
		carParams.setMarginalUtilityOfDistance(MARG_UTIL_DISTANCE_M);
		carParams.setMonetaryDistanceRate(MONETARY_DIST_RATE);
		carParams.setConstant(CONSTANT_CAR);

		// Configure walk mode (for fallback test)
		ScoringConfigGroup.ModeParams walkParams = scoringConfig.getOrCreateModeParams(TransportMode.walk);
		walkParams.setMarginalUtilityOfTraveling(-3.0);
		walkParams.setMarginalUtilityOfDistance(-0.0005);
		walkParams.setMonetaryDistanceRate(0.0);
		walkParams.setConstant(0.0);

		// Configure PT mode
		ScoringConfigGroup.ModeParams ptParams = scoringConfig.getOrCreateModeParams(TransportMode.pt);
		ptParams.setMarginalUtilityOfTraveling(-4.0);
		ptParams.setMarginalUtilityOfDistance(0.0);
		ptParams.setMonetaryDistanceRate(-0.0001);
		ptParams.setConstant(-0.5);

		// Build ScoringParameters
		scoringParams = new ScoringParameters.Builder(
				scoringConfig,
				scoringConfig.getScoringParameters(null),
				scenarioConfig
		).build();

		// Create a simple ScoringParametersForPerson that always returns our params
		ScoringParametersForPerson paramsForPerson = person -> scoringParams;

		adapter = new PlanCalcScoreAdapter(paramsForPerson);

		// Create test person
		testPerson = PopulationUtils.getFactory().createPerson(Id.createPersonId("test-person"));
	}

	@Test
	void testScoreCarLeg() {
		// Create a car leg with known time and distance
		double travelTime = 600.0;  // 10 minutes
		double distance = 5000.0;    // 5 km

		Leg carLeg = PopulationUtils.createLeg(TransportMode.car);
		carLeg.setTravelTime(travelTime);
		Route route = RouteUtils.createGenericRouteImpl(
				Id.createLinkId("link1"), Id.createLinkId("link2"));
		route.setDistance(distance);
		route.setTravelTime(travelTime);
		carLeg.setRoute(route);

		List<PlanElement> elements = List.of(carLeg);

		Activity origin = PopulationUtils.createActivityFromCoord("home", new Coord(0, 0));
		origin.setEndTime(28800.0);
		Activity destination = PopulationUtils.createActivityFromCoord("work", new Coord(5000, 0));

		TripScoreRequest request = new TripScoreRequest(
				testPerson, TransportMode.car, elements,
				origin, destination, 28800.0, new AttributesImpl(), 0);

		TripScoreResult result = adapter.scoreTrip(request);

		// Expected score = constant + margUtilTraveling*time + margUtilDistance*dist + margUtilMoney*monetaryDistRate*dist
		// = -1.5 + (-6.0/3600)*600 + (-0.0001)*5000 + 1.0*(-0.0002)*5000
		// = -1.5 + (-1.0) + (-0.5) + (-1.0)
		// = -4.0
		double expectedScore = CONSTANT_CAR
				+ (MARG_UTIL_TRAVELING_HR / 3600.0) * travelTime
				+ MARG_UTIL_DISTANCE_M * distance
				+ MARG_UTIL_MONEY * MONETARY_DIST_RATE * distance;

		assertEquals(expectedScore, result.utility(), 1e-9,
				"Car leg score should match CharyparNagel formula (without daily constants)");
		assertEquals(-4.0, result.utility(), 1e-9,
				"Score should be -4.0 for the given parameters");
	}

	@Test
	void testWalkFallbackForNonNetworkWalk() {
		// non_network_walk has no explicit params -- should fall back to walk params
		double travelTime = 300.0; // 5 minutes
		double distance = 400.0;   // 400 meters

		Leg walkLeg = PopulationUtils.createLeg("non_network_walk");
		walkLeg.setTravelTime(travelTime);
		Route route = RouteUtils.createGenericRouteImpl(
				Id.createLinkId("link1"), Id.createLinkId("link1"));
		route.setDistance(distance);
		route.setTravelTime(travelTime);
		walkLeg.setRoute(route);

		Activity origin = PopulationUtils.createActivityFromCoord("home", new Coord(0, 0));
		origin.setEndTime(28800.0);
		Activity destination = PopulationUtils.createActivityFromCoord("shop", new Coord(400, 0));

		TripScoreRequest request = new TripScoreRequest(
				testPerson, "non_network_walk", List.of(walkLeg),
				origin, destination, 28800.0, new AttributesImpl(), 0);

		TripScoreResult result = adapter.scoreTrip(request);

		// Should use walk params: constant=0, margUtilTrav=-3.0/3600, margUtilDist=-0.0005, monetaryDist=0
		double expectedScore = 0.0
				+ (-3.0 / 3600.0) * travelTime
				+ (-0.0005) * distance
				+ MARG_UTIL_MONEY * 0.0 * distance;

		assertEquals(expectedScore, result.utility(), 1e-9,
				"non_network_walk should fall back to walk scoring parameters");
	}

	@Test
	void testPtTripWithWaitingAndLineSwitches() {
		// Simulate a PT trip: access_walk -> PT leg 1 -> PT leg 2 -> egress_walk
		// with waiting time and a line switch

		double departureTime = 28800.0; // 8:00

		// Access walk leg
		Leg accessWalk = PopulationUtils.createLeg(TransportMode.walk);
		accessWalk.setDepartureTime(departureTime);
		accessWalk.setTravelTime(120.0); // 2 min walk to stop
		Route accessRoute = RouteUtils.createGenericRouteImpl(
				Id.createLinkId("link1"), Id.createLinkId("link2"));
		accessRoute.setDistance(100.0);
		accessRoute.setTravelTime(120.0);
		accessWalk.setRoute(accessRoute);

		// PT leg 1: departs at 28800+120+60 = 28980 (60s waiting)
		Leg ptLeg1 = PopulationUtils.createLeg(TransportMode.pt);
		ptLeg1.setDepartureTime(28980.0); // 60s wait after walk
		ptLeg1.setTravelTime(600.0);      // 10 min in-vehicle
		DefaultTransitPassengerRoute ptRoute1 = new DefaultTransitPassengerRoute(
				Id.createLinkId("ptLink1"), Id.createLinkId("ptLink2"),
				Id.create("stop1", TransitStopFacility.class), Id.create("stop2", TransitStopFacility.class),
				Id.create("line1", TransitLine.class), Id.create("route1", TransitRoute.class));
		ptRoute1.setDistance(3000.0);
		ptRoute1.setTravelTime(600.0);
		ptLeg1.setRoute(ptRoute1);

		// PT leg 2: departs at 28980+600+120 = 29700 (120s transfer wait)
		Leg ptLeg2 = PopulationUtils.createLeg(TransportMode.pt);
		ptLeg2.setDepartureTime(29700.0); // 120s transfer wait
		ptLeg2.setTravelTime(300.0);      // 5 min in-vehicle
		DefaultTransitPassengerRoute ptRoute2 = new DefaultTransitPassengerRoute(
				Id.createLinkId("ptLink2"), Id.createLinkId("ptLink3"),
				Id.create("stop2", TransitStopFacility.class), Id.create("stop3", TransitStopFacility.class),
				Id.create("line2", TransitLine.class), Id.create("route2", TransitRoute.class));
		ptRoute2.setDistance(2000.0);
		ptRoute2.setTravelTime(300.0);
		ptLeg2.setRoute(ptRoute2);

		// Egress walk leg
		Leg egressWalk = PopulationUtils.createLeg(TransportMode.walk);
		egressWalk.setDepartureTime(30000.0);
		egressWalk.setTravelTime(90.0);
		Route egressRoute = RouteUtils.createGenericRouteImpl(
				Id.createLinkId("link3"), Id.createLinkId("link4"));
		egressRoute.setDistance(75.0);
		egressRoute.setTravelTime(90.0);
		egressWalk.setRoute(egressRoute);

		List<PlanElement> elements = List.of(accessWalk, ptLeg1, ptLeg2, egressWalk);

		Activity origin = PopulationUtils.createActivityFromCoord("home", new Coord(0, 0));
		origin.setEndTime(departureTime);
		Activity destination = PopulationUtils.createActivityFromCoord("work", new Coord(5000, 0));

		TripScoreRequest request = new TripScoreRequest(
				testPerson, TransportMode.pt, elements,
				origin, destination, departureTime, new AttributesImpl(), 0);

		TripScoreResult result = adapter.scoreTrip(request);

		// Compute expected score
		// Walk legs (using walk params: constant=0, margTrav=-3.0/3600, margDist=-0.0005, monetaryDist=0)
		double walkScore1 = 0.0 + (-3.0 / 3600.0) * 120.0 + (-0.0005) * 100.0;
		double walkScore2 = 0.0 + (-3.0 / 3600.0) * 90.0 + (-0.0005) * 75.0;

		// PT legs (using pt params: constant=-0.5, margTrav=-4.0/3600, margDist=0, monetaryDist=-0.0001)
		double ptScore1 = -0.5 + (-4.0 / 3600.0) * 600.0 + 0.0 * 3000.0 + MARG_UTIL_MONEY * (-0.0001) * 3000.0;
		double ptScore2 = -0.5 + (-4.0 / 3600.0) * 300.0 + 0.0 * 2000.0 + MARG_UTIL_MONEY * (-0.0001) * 2000.0;

		double legScores = walkScore1 + ptScore1 + ptScore2 + walkScore2;

		// PT waiting time: tracks departureTime progression through legs
		// After access walk: departureTime = 28800 + 120 = 28920
		// PT leg 1 departs at 28980 -> wait = 28980 - 28920 = 60s
		// After PT leg 1: departureTime = 28920 + 600 = 29520
		//   (note: time progresses by travelTime of the CURRENT leg after processing it,
		//    but for PT waiting, the wait is computed BEFORE the PT leg is processed)
		// Actually, looking at the code more carefully:
		// departureTime starts at request.departureTime() = 28800
		// Access walk: not PT, skip wait tracking. departureTime += 120 -> 28920
		// PT leg 1: wait = 28980 - 28920 = 60s. departureTime += 600 -> 29520
		// PT leg 2: wait = 29700 - 29520 = 180s. departureTime += 300 -> 29820
		// Egress walk: not PT. departureTime += 90 -> 29910
		// Total PT wait = 60 + 180 = 240s
		double ptWaitingTime = 240.0;
		double waitScore = (MARG_UTIL_WAITING_PT_HR / 3600.0) * ptWaitingTime;

		// Line switch penalty: 2 PT vehicular legs -> 1 switch
		double lineSwitchScore = UTIL_LINE_SWITCH * (2 - 1);

		double expectedScore = legScores + waitScore + lineSwitchScore;

		assertEquals(expectedScore, result.utility(), 1e-9,
				"PT trip score should include waiting time disutility and line switch penalty");

		// Verify waiting time and line switch contributions are present
		// (the total must be more negative than just leg scores)
		assertTrue(result.utility() < legScores,
				"PT trip should have additional penalties from waiting and line switches");
	}

	@Test
	void testIncludesOpportunityCostReturnsFalse() {
		assertFalse(adapter.includesOpportunityCost(),
				"PlanCalcScoreAdapter should NOT include opportunity cost");
	}

	@Test
	void testGetMarginalUtilityOfMoney() {
		double result = adapter.getMarginalUtilityOfMoney(testPerson, 5.0);
		assertEquals(MARG_UTIL_MONEY, result, 1e-9,
				"Should return planCalcScore's marginalUtilityOfMoney");
	}

	@Test
	void testDoesNotSupportDistanceSpecificMoneyUtility() {
		assertFalse(adapter.supportsDistanceSpecificMoneyUtility(),
				"PlanCalcScoreAdapter should NOT support distance-specific money utility");
	}

	@Test
	void testGetName() {
		assertEquals("planCalcScore", adapter.getName());
	}

	@Test
	void testUnknownModeReturnsZeroScore() {
		// A mode with no scoring params and not containing "walk" should return 0
		Leg unknownLeg = PopulationUtils.createLeg("unicycle");
		unknownLeg.setTravelTime(300.0);
		Route route = RouteUtils.createGenericRouteImpl(
				Id.createLinkId("link1"), Id.createLinkId("link2"));
		route.setDistance(1000.0);
		route.setTravelTime(300.0);
		unknownLeg.setRoute(route);

		Activity origin = PopulationUtils.createActivityFromCoord("home", new Coord(0, 0));
		origin.setEndTime(28800.0);
		Activity destination = PopulationUtils.createActivityFromCoord("work", new Coord(1000, 0));

		TripScoreRequest request = new TripScoreRequest(
				testPerson, "unicycle", List.of(unknownLeg),
				origin, destination, 28800.0, new AttributesImpl(), 0);

		TripScoreResult result = adapter.scoreTrip(request);
		assertEquals(0.0, result.utility(), 1e-9,
				"Unknown mode with no params should score 0.0");
	}

	@Test
	void testLegWithNaNDistanceTreatedAsZero() {
		Leg carLeg = PopulationUtils.createLeg(TransportMode.car);
		carLeg.setTravelTime(600.0);
		Route route = RouteUtils.createGenericRouteImpl(
				Id.createLinkId("link1"), Id.createLinkId("link2"));
		route.setDistance(Double.NaN); // NaN distance
		route.setTravelTime(600.0);
		carLeg.setRoute(route);

		Activity origin = PopulationUtils.createActivityFromCoord("home", new Coord(0, 0));
		origin.setEndTime(28800.0);
		Activity destination = PopulationUtils.createActivityFromCoord("work", new Coord(5000, 0));

		TripScoreRequest request = new TripScoreRequest(
				testPerson, TransportMode.car, List.of(carLeg),
				origin, destination, 28800.0, new AttributesImpl(), 0);

		TripScoreResult result = adapter.scoreTrip(request);

		// With distance=0: score = constant + margUtilTraveling*time
		// = -1.5 + (-6.0/3600)*600 = -1.5 + (-1.0) = -2.5
		double expectedScore = CONSTANT_CAR + (MARG_UTIL_TRAVELING_HR / 3600.0) * 600.0;
		assertEquals(expectedScore, result.utility(), 1e-9,
				"NaN distance should be treated as 0");
	}

	@Test
	void testLegWithNoRouteTreatedAsZeroDistance() {
		Leg carLeg = PopulationUtils.createLeg(TransportMode.car);
		carLeg.setTravelTime(600.0);
		// No route set at all

		Activity origin = PopulationUtils.createActivityFromCoord("home", new Coord(0, 0));
		origin.setEndTime(28800.0);
		Activity destination = PopulationUtils.createActivityFromCoord("work", new Coord(5000, 0));

		TripScoreRequest request = new TripScoreRequest(
				testPerson, TransportMode.car, List.of(carLeg),
				origin, destination, 28800.0, new AttributesImpl(), 0);

		TripScoreResult result = adapter.scoreTrip(request);

		double expectedScore = CONSTANT_CAR + (MARG_UTIL_TRAVELING_HR / 3600.0) * 600.0;
		assertEquals(expectedScore, result.utility(), 1e-9,
				"Missing route should be treated as zero distance");
	}
}
