package org.matsim.contrib.demand_extraction.algorithm.stops;

import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.config.StopFindingStrategy;
import org.matsim.facilities.ActivityFacilities;

/**
 * Factory for creating StopFinder implementations based on configuration.
 *
 * Creates the appropriate stop finder based on the configured strategy:
 * - GEOMETRIC: GeometricStopFinder
 * - NETWORK_NODE: NetworkNodeStopFinder
 * - NETWORK_LINK: NetworkLinkStopFinder
 * - PREDEFINED: PredefinedStopFinder
 */
public class StopFinderFactory {

	private static final Logger log = LogManager.getLogger(StopFinderFactory.class);

	private final Network network;
	private final ActivityFacilities facilities;
	private final ExMasConfigGroup config;

	/**
	 * Creates a StopFinderFactory.
	 *
	 * @param network The MATSim network
	 * @param facilities Optional facilities for PREDEFINED strategy (can be null)
	 * @param config The ExMAS configuration
	 */
	public StopFinderFactory(Network network, ActivityFacilities facilities, ExMasConfigGroup config) {
		this.network = network;
		this.facilities = facilities;
		this.config = config;
	}

	/**
	 * Create a StopFinder based on the configured strategy.
	 */
	public StopFinder create() {
		String strategyStr = config.getStopFindingStrategy();
		StopFindingStrategy strategy;
		try {
			strategy = StopFindingStrategy.valueOf(strategyStr);
		} catch (IllegalArgumentException e) {
			log.warn("Unknown stop finding strategy '{}', defaulting to GEOMETRIC", strategyStr);
			strategy = StopFindingStrategy.GEOMETRIC;
		}

		return create(strategy);
	}

	/**
	 * Create a StopFinder for a specific strategy.
	 */
	public StopFinder create(StopFindingStrategy strategy) {
		double searchRadius = config.getStopSearchRadiusMeters();
		double beelineFactor = 1.0; // Could be made configurable

		log.info("Creating StopFinder with strategy: {}", strategy);

		switch (strategy) {
			case GEOMETRIC:
				return createGeometricStopFinder(searchRadius, beelineFactor);

			case NETWORK_NODE:
				return new NetworkNodeStopFinder(network, searchRadius, beelineFactor);

			case NETWORK_LINK:
				return createNetworkLinkStopFinder(searchRadius, beelineFactor);

			case PREDEFINED:
				if (facilities == null) {
					log.warn("PREDEFINED strategy requested but no facilities provided, falling back to GEOMETRIC");
					return createGeometricStopFinder(searchRadius, beelineFactor);
				}
				return new PredefinedStopFinder(facilities, searchRadius, beelineFactor);

			default:
				log.warn("Unhandled strategy {}, defaulting to GEOMETRIC", strategy);
				return createGeometricStopFinder(searchRadius, beelineFactor);
		}
	}

	private StopFinder createGeometricStopFinder(double searchRadius, double beelineFactor) {
		LinkCandidateFinder linkFinder = createLinkCandidateFinder();
		WalkingDistanceCalculator walkCalculator = new WalkingDistanceCalculator(beelineFactor);
		return new GeometricStopFinder(linkFinder, walkCalculator, searchRadius);
	}

	private StopFinder createNetworkLinkStopFinder(double searchRadius, double beelineFactor) {
		LinkCandidateFinder linkFinder = createLinkCandidateFinder();
		WalkingDistanceCalculator walkCalculator = new WalkingDistanceCalculator(beelineFactor);
		return new NetworkLinkStopFinder(linkFinder, walkCalculator, searchRadius);
	}

	private LinkCandidateFinder createLinkCandidateFinder() {
		Set<String> allowedModes = config.getDrtAllowedModes();
		double maxLinkLength = config.getMaxLinkLengthForStopMeters();
		return new LinkCandidateFinder(network, allowedModes, maxLinkLength);
	}

	/**
	 * Create a WalkingDistanceCalculator for use outside the factory.
	 */
	public WalkingDistanceCalculator createWalkingDistanceCalculator() {
		return new WalkingDistanceCalculator(1.0);
	}
}
