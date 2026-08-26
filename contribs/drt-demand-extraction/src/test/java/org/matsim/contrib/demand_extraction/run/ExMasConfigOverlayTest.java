package org.matsim.contrib.demand_extraction.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

/**
 * {@link ExMasConfigOverlay} applies a Python-rendered {@code exmas}-module XML onto an
 * already-built {@link Config}, overriding whatever the scenario fixture set, and fails
 * loudly (never silently) on a bad param name or a missing file.
 */
class ExMasConfigOverlayTest {

	private static Path writeOverlay(Path dir, String body) throws IOException {
		Path file = dir.resolve("overlay.xml");
		Files.writeString(file,
				"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
				+ "<!DOCTYPE config SYSTEM \"http://www.matsim.org/files/dtd/config_v2.dtd\">\n"
				+ "<config>\n"
				+ "\t<module name=\"exmas\">\n"
				+ body
				+ "\t</module>\n"
				+ "</config>\n");
		return file;
	}

	@Test
	void overlayValueWinsOverTheFixtureDefault(@TempDir Path tmp) throws IOException {
		Config config = ConfigUtils.createConfig();
		ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		exMas.setMaxDetourFactor(1.2);

		Path overlay = writeOverlay(tmp, "\t\t<param name=\"maxDetourFactor\" value=\"1.8\" />\n");
		ExMasConfigOverlay.apply(config, overlay.toString());

		ExMasConfigGroup after = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		assertEquals(1.8, after.getMaxDetourFactor(), 1e-12,
				"the overlay's value must win over whatever the scenario fixture set");
	}

	@Test
	void spontaneousBookingHorizonRoundTripsThroughOverlay(@TempDir Path tmp) throws IOException {
		Config config = ConfigUtils.createConfig();
		ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

		Path overlay = writeOverlay(tmp, "\t\t<param name=\"spontaneousBookingHorizon\" value=\"1800.0\" />\n");
		ExMasConfigOverlay.apply(config, overlay.toString());

		ExMasConfigGroup after = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		assertEquals(1800.0, after.getSpontaneousBookingHorizon(), 1e-12,
				"the overlay's value must win over the class default");
	}

	@Test
	void spontaneousBookingHorizonDefaultsWhenAbsentFromOverlay(@TempDir Path tmp) throws IOException {
		Config config = ConfigUtils.createConfig();
		ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

		Path overlay = writeOverlay(tmp, "\t\t<param name=\"maxDetourFactor\" value=\"1.8\" />\n");
		ExMasConfigOverlay.apply(config, overlay.toString());

		ExMasConfigGroup after = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		assertEquals(0.0, after.getSpontaneousBookingHorizon(), 1e-12,
				"a param absent from the overlay must keep the class default");
	}

	@Test
	void spontaneousSingletonChainsRoundTripsThroughOverlay(@TempDir Path tmp) throws IOException {
		Config config = ConfigUtils.createConfig();
		ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

		Path overlay = writeOverlay(tmp, "\t\t<param name=\"spontaneousSingletonChains\" value=\"true\" />\n");
		ExMasConfigOverlay.apply(config, overlay.toString());

		ExMasConfigGroup after = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		assertTrue(after.isSpontaneousSingletonChains(),
				"the overlay's value must win over the class default");
	}

	@Test
	void spontaneousSingletonChainsDefaultsWhenAbsentFromOverlay(@TempDir Path tmp) throws IOException {
		Config config = ConfigUtils.createConfig();
		ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

		Path overlay = writeOverlay(tmp, "\t\t<param name=\"maxDetourFactor\" value=\"1.8\" />\n");
		ExMasConfigOverlay.apply(config, overlay.toString());

		ExMasConfigGroup after = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		assertFalse(after.isSpontaneousSingletonChains(),
				"a param absent from the overlay must keep the class default");
	}

	@Test
	void hubSyncWindowedRoundTripsThroughOverlay(@TempDir Path tmp) throws IOException {
		Config config = ConfigUtils.createConfig();
		ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

		Path overlay = writeOverlay(tmp, "\t\t<param name=\"hubSyncWindowed\" value=\"true\" />\n");
		ExMasConfigOverlay.apply(config, overlay.toString());

		ExMasConfigGroup after = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		assertTrue(after.isHubSyncWindowed(),
				"the overlay's value must win over the class default");
	}

	@Test
	void hubSyncWindowedDefaultsWhenAbsentFromOverlay(@TempDir Path tmp) throws IOException {
		Config config = ConfigUtils.createConfig();
		ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

		Path overlay = writeOverlay(tmp, "\t\t<param name=\"maxDetourFactor\" value=\"1.8\" />\n");
		ExMasConfigOverlay.apply(config, overlay.toString());

		ExMasConfigGroup after = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		assertFalse(after.isHubSyncWindowed(),
				"a param absent from the overlay must keep the class default");
	}

	@Test
	void hubTopKRoundTripsThroughOverlay(@TempDir Path tmp) throws IOException {
		Config config = ConfigUtils.createConfig();
		ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

		Path overlay = writeOverlay(tmp, "\t\t<param name=\"hubTopK\" value=\"5\" />\n");
		ExMasConfigOverlay.apply(config, overlay.toString());

		ExMasConfigGroup after = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		assertEquals(5, after.getHubTopK(),
				"the overlay's value must win over the class default");
	}

	@Test
	void hubTopKDefaultsWhenAbsentFromOverlay(@TempDir Path tmp) throws IOException {
		Config config = ConfigUtils.createConfig();
		ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

		Path overlay = writeOverlay(tmp, "\t\t<param name=\"maxDetourFactor\" value=\"1.8\" />\n");
		ExMasConfigOverlay.apply(config, overlay.toString());

		ExMasConfigGroup after = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		assertEquals(0, after.getHubTopK(),
				"a param absent from the overlay must keep the class default");
	}

	@Test
	void bookingParamsAppearInGetComments() {
		ExMasConfigGroup exMas = new ExMasConfigGroup();
		assertTrue(exMas.getComments().containsKey("spontaneousBookingHorizon"),
				"spontaneousBookingHorizon must be documented in getComments()");
		assertTrue(exMas.getComments().containsKey("spontaneousSingletonChains"),
				"spontaneousSingletonChains must be documented in getComments()");
		assertTrue(exMas.getComments().containsKey("hubSyncWindowed"),
				"hubSyncWindowed must be documented in getComments()");
		assertTrue(exMas.getComments().containsKey("hubTopK"),
				"hubTopK must be documented in getComments()");
	}

	@Test
	void unknownParamThrows(@TempDir Path tmp) throws IOException {
		Config config = ConfigUtils.createConfig();
		ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

		Path overlay = writeOverlay(tmp, "\t\t<param name=\"maxDetourFactr\" value=\"1.8\" />\n");
		assertThrows(Exception.class, () -> ExMasConfigOverlay.apply(config, overlay.toString()),
				"a typo'd param name must fail loudly, not be silently ignored");
	}

	@Test
	void missingFileThrowsIllegalArgumentException(@TempDir Path tmp) {
		Config config = ConfigUtils.createConfig();
		ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

		Path missing = tmp.resolve("does-not-exist.xml");
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> ExMasConfigOverlay.apply(config, missing.toString()));
		assertEquals("--exmas-config not readable: " + missing, ex.getMessage());
	}
}
