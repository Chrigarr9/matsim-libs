package org.matsim.contrib.demand_extraction.run;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;

/**
 * Adjusts third-party log levels that are too verbose for production runs.
 * Called once at the start of each runner's {@code main()} so changes stay
 * inside this plugin and do not require touching MATSim core configs.
 */
final class LoggingSetup {

	private LoggingSetup() {}

	static void configure() {
		// NetworkImpl logs every node/link during network loading at INFO — too noisy.
		Configurator.setLevel("org.matsim.core.network.NetworkImpl", Level.WARN);
		// SpeedyALTData logs ALT landmark computation steps at INFO — too noisy.
		Configurator.setLevel("org.matsim.core.router.speedy.SpeedyALTData", Level.WARN);
	}
}
