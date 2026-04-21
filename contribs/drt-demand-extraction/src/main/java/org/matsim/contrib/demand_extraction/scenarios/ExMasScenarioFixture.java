package org.matsim.contrib.demand_extraction.scenarios;

import java.io.IOException;
import java.nio.file.Path;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;

/**
 * Scenario abstraction for ExMAS demand extraction. Lifts the per-scenario
 * setup (config loading, scoring, mode params, controler creation, output
 * validation) out of runners and tests so the same setup is used by both,
 * and so the algorithm-vs-scenario matrix can be expressed declaratively.
 *
 * <p>Used by Paper 1 R1/R2/R3 comparisons via
 * {@link org.matsim.contrib.demand_extraction.scenarios.AlgorithmProfile}.
 */
public interface ExMasScenarioFixture {

	/** Short label for output paths and test names (e.g. "kelheim", "lyon"). */
	String getName();

	/** Build a Config with this scenario's defaults; output directed at {@code outputDir}. */
	Config createConfig(Path outputDir) throws IOException;

	/** Apply an {@link AlgorithmProfile} to the config (algorithm + pruning knobs). */
	default void configureAlgorithm(Config config, AlgorithmProfile profile) {
		profile.apply(config);
	}

	/** Build the controler ready to run (population loaded, modules added). */
	Controler createControler(Config config) throws IOException;

	/** Run the controler. Default just calls {@link Controler#run()}. */
	default void runDemandExtraction(Controler controler) {
		controler.run();
	}

	/** Validate the demand-extraction outputs in {@code outputDir}. Throws on failure. */
	void validateOutput(Config config, Path outputDir) throws IOException;

	/**
	 * Convenience: run the full pipeline (createConfig, configureAlgorithm,
	 * createControler, run, validate) and return the loaded scenario for
	 * downstream assertions if any.
	 */
	default Scenario runFullPipeline(Path outputDir, AlgorithmProfile profile) throws IOException {
		Config config = createConfig(outputDir);
		configureAlgorithm(config, profile);
		Controler controler = createControler(config);
		runDemandExtraction(controler);
		validateOutput(config, outputDir);
		return controler.getScenario();
	}
}
