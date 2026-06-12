package org.matsim.contrib.demand_extraction.run;

import java.io.IOException;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.demand_extraction.algorithm.network.DeterministicTravelDisutility;
import org.matsim.contrib.demand_extraction.algorithm.network.OfflineTravelTimes;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

/**
 * Shared phase-2 routing setup: loads the network + offline travel times and
 * builds a SpeedyALT router with the {@link DeterministicTravelDisutility} wrap,
 * exactly as the demand extraction does. Used by route/detour export runners so
 * their routing is bit-for-bit the phase-2 routing.
 */
public final class Phase2RoutingSetup {

    public static final int TRAVEL_TIME_BIN_SIZE = OfflineTravelTimes.TRAVEL_TIME_BIN_SIZE;
    public static final int TRAVEL_TIME_END = OfflineTravelTimes.TRAVEL_TIME_END;

    public final Network network;
    public final LeastCostPathCalculator router;
    public final Person dummyPerson;
    public final Vehicle dummyVehicle;

    private Phase2RoutingSetup(Network n, LeastCostPathCalculator r, Person p, Vehicle v) {
        this.network = n; this.router = r; this.dummyPerson = p; this.dummyVehicle = v;
    }

    public static Phase2RoutingSetup load(String networkPath, String travelTimesPath) throws IOException {
        Config config = ConfigUtils.createConfig();
        // Atlantis CRS placeholder; the network file coords are already EPSG:2154.
        config.global().setCoordinateSystem("Atlantis");
        MutableScenario scenario = (MutableScenario) ScenarioUtils.createScenario(config);
        new MatsimNetworkReader(scenario.getNetwork()).readFile(networkPath);
        Network network = scenario.getNetwork();

        TravelTime travelTime = OfflineTravelTimes.load(travelTimesPath);
        // Identical wrap to MatsimNetworkCache's injected path (Lyon fixture binds
        // OnlyTimeDependentTravelDisutilityFactory for car), so route/detour exports
        // are bit-for-bit the phase-2 routing.
        TravelDisutility disutility = DeterministicTravelDisutility.wrap(
                new OnlyTimeDependentTravelDisutility(travelTime), travelTime, network);
        LeastCostPathCalculator router = new SpeedyALTFactory().createPathCalculator(network, disutility, travelTime);

        Person dummyPerson = PopulationUtils.getFactory().createPerson(Id.createPersonId("phase2_dummy"));
        VehicleType type = VehicleUtils.createVehicleType(Id.create("car", VehicleType.class));
        Vehicle dummyVehicle = VehicleUtils.createVehicle(Id.createVehicleId("phase2_dummy"), type);
        return new Phase2RoutingSetup(network, router, dummyPerson, dummyVehicle);
    }

}
