package org.matsim.contrib.demand_extraction.replanning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.algorithms.PermissibleModesCalculator;

import com.google.inject.Inject;

/**
 * PermissibleModesCalculator that respects both car AND bike availability.
 *
 * <p>MATSim's default {@link org.matsim.core.population.algorithms.PermissibleModesCalculatorImpl}
 * only checks car availability. Eqasim populations carry a {@code bicycleAvailability}
 * attribute (values "all" or "none") that the default calculator silently ignores,
 * causing ~17% of bike trips to be made by agents who do not have a bike.
 *
 * <p>This calculator filters {@code "bike"} out of the permissible modes for any
 * plan whose person has {@code bicycleAvailability="none"}. Car-availability
 * behavior is identical to {@code PermissibleModesCalculatorImpl}: car is removed
 * if {@code carAvail="never"} or {@code license="no"}, and the check is skipped
 * entirely when {@code subtourModeChoice.considerCarAvailability=false}.
 *
 * <p>Bind via Guice in your run class:
 * <pre>{@code
 * controler.addOverridingModule(new AbstractModule() {
 *     @Override public void install() {
 *         bind(PermissibleModesCalculator.class)
 *             .to(BicycleAwarePermissibleModesCalculator.class)
 *             .in(Singleton.class);
 *     }
 * });
 * }</pre>
 */
public final class BicycleAwarePermissibleModesCalculator implements PermissibleModesCalculator {

	private static final String BICYCLE_AVAILABILITY_ATTR = "bicycleAvailability";
	private static final String NONE = "none";

	private final List<String> all;
	private final List<String> noCar;
	private final List<String> noBike;
	private final List<String> noCarNoBike;
	private final boolean considerCarAvailability;

	@Inject
	public BicycleAwarePermissibleModesCalculator(Config config) {
		this.all = Collections.unmodifiableList(
				new ArrayList<>(Arrays.asList(config.subtourModeChoice().getModes())));
		this.noCar = without(this.all, TransportMode.car);
		this.noBike = without(this.all, TransportMode.bike);
		this.noCarNoBike = without(this.noCar, TransportMode.bike);
		this.considerCarAvailability = config.subtourModeChoice().considerCarAvailability();
	}

	private static List<String> without(List<String> modes, String exclude) {
		List<String> out = new ArrayList<>(modes);
		out.removeIf(exclude::equals);
		return Collections.unmodifiableList(out);
	}

	@Override
	public Collection<String> getPermissibleModes(Plan plan) {
		Person person;
		try {
			person = plan.getPerson();
		} catch (ClassCastException e) {
			throw new IllegalArgumentException("Need a Person to read availability attributes", e);
		}

		boolean carAvail = !considerCarAvailability
				|| (!"no".equals(PersonUtils.getLicense(person))
						&& !"never".equals(PersonUtils.getCarAvail(person)));

		boolean bikeAvail = !NONE.equals(person.getAttributes().getAttribute(BICYCLE_AVAILABILITY_ATTR));

		if (carAvail && bikeAvail) return all;
		if (carAvail) return noBike;
		if (bikeAvail) return noCar;
		return noCarNoBike;
	}
}
