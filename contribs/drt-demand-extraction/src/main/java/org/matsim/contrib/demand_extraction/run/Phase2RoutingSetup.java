package org.matsim.contrib.demand_extraction.run;

import java.io.IOException;
import java.nio.file.Path;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.common.timeprofile.TimeDiscretizer;
import org.matsim.contrib.demand_extraction.algorithm.network.TimeDistanceTravelDisutility;
import org.matsim.contrib.dvrp.trafficmonitoring.DvrpOfflineTravelTimes;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.population.PopulationUtils;
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
 * builds a SpeedyALT router with the deterministic time-distance disutility,
 * exactly as the demand extraction does. Used by route/detour export runners so
 * their routing is bit-for-bit the phase-2 routing.
 */
public final class Phase2RoutingSetup {
    public static final int TRAVEL_TIME_BIN_SIZE = 900;   // 15 min
    public static final int TRAVEL_TIME_END = 36 * 3600;  // 36 h
    private static final double DET_TIME_COEF = 1.0;
    private static final double DET_DIST_COEF = 1e-9;

    public final Network network;
    public final LeastCostPathCalculator router;
    public final Person dummyPerson;
    public final Vehicle dummyVehicle;

    private Phase2RoutingSetup(Network n, LeastCostPathCalculator r, Person p, Vehicle v) {
        this.network = n; this.router = r; this.dummyPerson = p; this.dummyVehicle = v;
    }

    public static Phase2RoutingSetup load(String networkPath, String travelTimesPath) throws IOException {
        Config config = ConfigUtils.createConfig();
        config.global().setCoordinateSystem("Atlantis");
        MutableScenario scenario = (MutableScenario) ScenarioUtils.createScenario(config);
        new MatsimNetworkReader(scenario.getNetwork()).readFile(networkPath);
        Network network = scenario.getNetwork();

        TravelTime travelTime = loadOfflineTravelTimes(travelTimesPath);
        TravelDisutility disutility = new TimeDistanceTravelDisutility(travelTime, DET_TIME_COEF, DET_DIST_COEF);
        LeastCostPathCalculator router = new SpeedyALTFactory().createPathCalculator(network, disutility, travelTime);

        Person dummyPerson = PopulationUtils.getFactory().createPerson(Id.createPersonId("phase2_dummy"));
        VehicleType type = VehicleUtils.createVehicleType(Id.create("car", VehicleType.class));
        Vehicle dummyVehicle = VehicleUtils.createVehicle(Id.createVehicleId("phase2_dummy"), type);
        return new Phase2RoutingSetup(network, router, dummyPerson, dummyVehicle);
    }

    private static TravelTime loadOfflineTravelTimes(String ttFile) throws IOException {
        TimeDiscretizer td = new TimeDiscretizer(TRAVEL_TIME_END, TRAVEL_TIME_BIN_SIZE);
        java.net.URL ttUrl = Path.of(ttFile).toUri().toURL();
        double[][] matrix = DvrpOfflineTravelTimes.loadLinkTravelTimes(td, ttUrl, "\t");
        TravelTime baseTt = DvrpOfflineTravelTimes.asTravelTime(td, matrix);
        return (link, time, person, vehicle) ->
                baseTt.getLinkTravelTime(link, Math.min(time, TRAVEL_TIME_END), person, vehicle);
    }
}
