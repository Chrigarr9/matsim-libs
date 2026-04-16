package org.matsim.contrib.demand_extraction.replanning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;

/**
 * Unit tests for {@link BicycleAwarePermissibleModesCalculator}.
 *
 * <p>Verifies that the calculator filters bike for agents with
 * {@code bicycleAvailability='none'} (the eqasim attribute) while preserving
 * the car-availability behavior of MATSim's default calculator.
 */
public class BicycleAwarePermissibleModesCalculatorTest {

	private Config config;

	@BeforeEach
	public void setUp() {
		config = ConfigUtils.createConfig();
		config.subtourModeChoice().setModes(new String[] {"car", "pt", "bike", "walk", "ride"});
		config.subtourModeChoice().setConsiderCarAvailability(true);
	}

	private static Plan planFor(Person p) {
		Plan plan = PopulationUtils.createPlan(p);
		p.addPlan(plan);
		return plan;
	}

	private static Person person(String name) {
		return PopulationUtils.getFactory().createPerson(Id.create(name, Person.class));
	}

	private static Set<String> set(Collection<String> modes) {
		return new HashSet<>(modes);
	}

	@Test
	public void noAttributes_allModesPermissible() {
		BicycleAwarePermissibleModesCalculator calc = new BicycleAwarePermissibleModesCalculator(config);
		Person p = person("noAttrs");
		Collection<String> modes = calc.getPermissibleModes(planFor(p));
		assertEquals(Set.of("car", "pt", "bike", "walk", "ride"), set(modes));
	}

	@Test
	public void bicycleAvailabilityNone_excludesBike() {
		BicycleAwarePermissibleModesCalculator calc = new BicycleAwarePermissibleModesCalculator(config);
		Person p = person("noBike");
		p.getAttributes().putAttribute("bicycleAvailability", "none");
		Collection<String> modes = calc.getPermissibleModes(planFor(p));
		assertFalse(modes.contains("bike"), "bike must be excluded when bicycleAvailability=none");
		assertTrue(modes.contains("car"));
		assertTrue(modes.contains("walk"));
		assertTrue(modes.contains("pt"));
		assertTrue(modes.contains("ride"));
	}

	@Test
	public void bicycleAvailabilityAll_includesBike() {
		BicycleAwarePermissibleModesCalculator calc = new BicycleAwarePermissibleModesCalculator(config);
		Person p = person("hasBike");
		p.getAttributes().putAttribute("bicycleAvailability", "all");
		Collection<String> modes = calc.getPermissibleModes(planFor(p));
		assertTrue(modes.contains("bike"));
	}

	@Test
	public void carAvailNever_excludesCar() {
		BicycleAwarePermissibleModesCalculator calc = new BicycleAwarePermissibleModesCalculator(config);
		Person p = person("noCar");
		PersonUtils.setCarAvail(p, "never");
		Collection<String> modes = calc.getPermissibleModes(planFor(p));
		assertFalse(modes.contains("car"), "car must be excluded when carAvail=never");
		assertTrue(modes.contains("bike"));
	}

	@Test
	public void noCarAndNoBike_excludesBoth() {
		BicycleAwarePermissibleModesCalculator calc = new BicycleAwarePermissibleModesCalculator(config);
		Person p = person("nothing");
		PersonUtils.setCarAvail(p, "never");
		p.getAttributes().putAttribute("bicycleAvailability", "none");
		Collection<String> modes = calc.getPermissibleModes(planFor(p));
		assertFalse(modes.contains("car"));
		assertFalse(modes.contains("bike"));
		assertEquals(Set.of("pt", "walk", "ride"), set(modes));
	}

	@Test
	public void considerCarAvailabilityFalse_carAlwaysAvailable() {
		config.subtourModeChoice().setConsiderCarAvailability(false);
		BicycleAwarePermissibleModesCalculator calc = new BicycleAwarePermissibleModesCalculator(config);
		Person p = person("noCarButIgnored");
		PersonUtils.setCarAvail(p, "never");
		Collection<String> modes = calc.getPermissibleModes(planFor(p));
		assertTrue(modes.contains("car"), "car must be present when considerCarAvailability=false");
	}

	@Test
	public void considerCarAvailabilityFalse_bikeStillFilteredByAttribute() {
		// Even when car checking is off, the bike attribute filter is independent
		// and should still be applied — bike availability is not gated on the car flag.
		config.subtourModeChoice().setConsiderCarAvailability(false);
		BicycleAwarePermissibleModesCalculator calc = new BicycleAwarePermissibleModesCalculator(config);
		Person p = person("noBike");
		p.getAttributes().putAttribute("bicycleAvailability", "none");
		Collection<String> modes = calc.getPermissibleModes(planFor(p));
		assertFalse(modes.contains("bike"));
		assertTrue(modes.contains("car"));
	}

	@Test
	public void noLicense_excludesCar() {
		BicycleAwarePermissibleModesCalculator calc = new BicycleAwarePermissibleModesCalculator(config);
		Person p = person("noLicense");
		PersonUtils.setLicence(p, "no");
		Collection<String> modes = calc.getPermissibleModes(planFor(p));
		assertFalse(modes.contains("car"), "car must be excluded when licence=no");
	}
}
