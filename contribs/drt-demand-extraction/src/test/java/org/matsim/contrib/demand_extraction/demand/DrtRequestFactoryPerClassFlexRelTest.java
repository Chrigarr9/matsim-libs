package org.matsim.contrib.demand_extraction.demand;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EXT-4 rel half: per-class relative-flexibility overrides ({@code flexRelativeByClass}).
 *
 * <p>The production wiring (DrtRequestFactory.buildRequest) resolves the request class via
 * {@link DrtRequestFactory#resolveClassFactor} with a {@code Double.NaN} sentinel, then
 * passes the override to the {@link FlexibilityCalculator} per-class overloads. Both halves
 * are covered here:
 * <ol>
 *   <li>NaN-sentinel resolution: absent tag → NaN (→ null override → legacy path).</li>
 *   <li>Calculator overloads: non-null override replaces the relative factor while the
 *       absolute component keeps its map default; null override is byte-identical to the
 *       legacy two-arg path.</li>
 *   <li>Config accessor contract (mirrors maxDetourFactorByClass).</li>
 * </ol>
 */
class DrtRequestFactoryPerClassFlexRelTest {

    private static final double MAX_DETOUR = 600.0; // s — the absolute detour cap fed to flex

    private static ExMasConfigGroup configWithClassRel() {
        ExMasConfigGroup cfg = new ExMasConfigGroup();
        cfg.setFlexRelativeByClass(Map.of(
                "rural_intra", 1.0,
                "urban_intra", 0.75,
                "connecting", 0.85
        ));
        return cfg;
    }

    // -----------------------------------------------------------------------
    // NaN-sentinel resolution (the buildRequest lookup)
    // -----------------------------------------------------------------------

    @Test
    void resolveClassFactor_returnsNaNSentinelForAbsentTag() {
        ExMasConfigGroup cfg = configWithClassRel();
        double hit = DrtRequestFactory.resolveClassFactor(cfg.getFlexRelativeByClass(),
                Double.NaN, "rural_intra", DrtRequest.HubLegRole.NONE);
        double miss = DrtRequestFactory.resolveClassFactor(cfg.getFlexRelativeByClass(),
                Double.NaN, "some_other_tag", DrtRequest.HubLegRole.NONE);
        double nullTag = DrtRequestFactory.resolveClassFactor(cfg.getFlexRelativeByClass(),
                Double.NaN, null, DrtRequest.HubLegRole.NONE);

        assertEquals(1.0, hit, 1e-12, "rural_intra must resolve to its calibrated rel");
        assertTrue(Double.isNaN(miss), "absent tag must return the NaN sentinel (legacy path)");
        assertTrue(Double.isNaN(nullTag), "null tag must return the NaN sentinel (Kelheim path)");
    }

    // -----------------------------------------------------------------------
    // FlexibilityCalculator per-class overloads
    // -----------------------------------------------------------------------

    @Test
    void overrideReplacesRelativeFactor_keepsAbsoluteDefault() {
        ExMasConfigGroup cfg = new ExMasConfigGroup();
        cfg.setNegativeFlexibilityAbsoluteMap("default:60");
        cfg.setPositiveFlexibilityAbsoluteMap("default:30");
        FlexibilityCalculator calc = new FlexibilityCalculator(cfg);

        // origin: abs 60 + 1.0 * 600 = 660; destination: abs 30 + 0.75 * 600 = 480
        assertEquals(660.0, calc.calculateOriginFlexibility(null, null, MAX_DETOUR, 1.0), 1e-9,
                "override 1.0 must yield absDefault + 1.0 * maxDetour");
        assertEquals(480.0, calc.calculateDestinationFlexibility(null, null, MAX_DETOUR, 0.75), 1e-9,
                "override 0.75 must yield absDefault + 0.75 * maxDetour");
    }

    @Test
    void nullOverrideMatchesLegacyTwoArgPath() {
        ExMasConfigGroup cfg = new ExMasConfigGroup();
        FlexibilityCalculator calc = new FlexibilityCalculator(cfg);

        // Legacy default: abs 0.0 + rel 0.5 * 600 = 300 on both sides.
        assertEquals(calc.calculateOriginFlexibility(null, null, MAX_DETOUR),
                calc.calculateOriginFlexibility(null, null, MAX_DETOUR, null), 0.0,
                "null override must be byte-identical to the two-arg origin path");
        assertEquals(calc.calculateDestinationFlexibility(null, null, MAX_DETOUR),
                calc.calculateDestinationFlexibility(null, null, MAX_DETOUR, null), 0.0,
                "null override must be byte-identical to the two-arg destination path");
        assertEquals(300.0, calc.calculateOriginFlexibility(null, null, MAX_DETOUR, null), 1e-9,
                "config default must remain abs 0.0 + rel 0.5");
    }

    // -----------------------------------------------------------------------
    // Config accessor contract
    // -----------------------------------------------------------------------

    @Test
    void flexRelByClassAccessors_behaviorIsCorrect() {
        ExMasConfigGroup cfg = new ExMasConfigGroup();

        assertTrue(cfg.getFlexRelativeByClass().isEmpty(), "default map must be empty");

        cfg.setFlexRelativeByClass(Map.of("rural_intra", 1.0, "urban_intra", 0.75));
        assertEquals(1.0, cfg.getFlexRelativeByClass().get("rural_intra"), 1e-12);
        assertEquals(0.75, cfg.getFlexRelativeByClass().get("urban_intra"), 1e-12);

        assertThrows(UnsupportedOperationException.class,
                () -> cfg.getFlexRelativeByClass().put("x", 1.0),
                "getter must return unmodifiable map");

        Map<String, Double> source = new java.util.HashMap<>();
        source.put("a", 0.5);
        cfg.setFlexRelativeByClass(source);
        source.put("b", 0.6);
        assertFalse(cfg.getFlexRelativeByClass().containsKey("b"),
                "setFlexRelativeByClass must copy defensively");

        cfg.clearFlexRelativeByClass();
        assertTrue(cfg.getFlexRelativeByClass().isEmpty(), "after clear, map must be empty");
    }
}
