package org.matsim.contrib.demand_extraction.run;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

/**
 * Applies a Python-rendered {@code exmas}-module config XML onto an already-built
 * {@link Config}.
 *
 * <p>Applied AFTER the scenario fixture's own defaults, so the pipeline.yaml always
 * wins. ExMasConfigGroup rejects unknown params at parse time, so a typo fails here
 * rather than silently doing nothing -- which is what happened with the old CLI, where
 * {@code default -> log.warn("Unknown argument")} made a misspelled flag a no-op.
 */
public final class ExMasConfigOverlay {

	private static final Logger log = LogManager.getLogger(ExMasConfigOverlay.class);

	private ExMasConfigOverlay() {}

	public static void apply(Config config, String overlayPath) {
		Path path = Path.of(overlayPath);
		if (!Files.isReadable(path)) {
			throw new IllegalArgumentException("--exmas-config not readable: " + path);
		}
		ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		ConfigUtils.loadConfig(config, path.toString());
		ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		log.info("Applied exmas overlay from {} ({} params in effect)",
				path.toAbsolutePath(), exMas.getParams().size());
	}
}
