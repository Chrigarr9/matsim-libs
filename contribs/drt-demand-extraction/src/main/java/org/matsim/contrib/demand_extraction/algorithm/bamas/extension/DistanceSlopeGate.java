package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

/**
 * Verbatim extraction of the distance-savings gate previously inlined in
 * {@link BamasRideExtender#computeMaxAllowedRideDistance(int, double,
 * org.matsim.contrib.demand_extraction.config.ExMasConfigGroup)}
 * (lines 896–918 of {@code BamasRideExtender.java}).
 *
 * <p>Two gate shapes share one class, mirroring the single {@code if (cfg.hasLinearGate())}
 * branch in the original:
 * <ol>
 *   <li><b>Linear gate</b> (active when {@code intercept} and {@code slope} are both
 *       finite): threshold = {@code max(1 - maxSaving, intercept + slope * degree) * sumDirectDistances}.
 *   <li><b>Log gate</b> (fallback when linear is not configured): threshold =
 *       {@code (1 - requiredSaving) * sumDirectDistances} where
 *       {@code requiredSaving = clamp(logScale * log2(degree), 0, min(0.99, maxSaving))}.
 *       When {@code logScale < 0} or {@code degree < minDegree} the gate is disabled and
 *       every candidate is admitted.
 * </ol>
 *
 * <p>The {@link #admit} boundary uses {@code <=} (inclusive equality admitted), matching
 * {@code passesDistanceSavingsPruning} in both extenders.
 *
 * <p><b>Wiring-time note:</b> the live BAMAS extension loop ({@code processSet} →
 * {@code evaluateOrdering}) does not call {@code passesDistanceSavingsPruning} directly.
 * Instead it seeds {@code bestValidDist[0] = computeMaxAllowedRideDistance(...)} and keeps
 * a ride iff {@code dist < bestValidDist[0]} (strict {@code <}). A ride whose distance
 * equals the threshold exactly is therefore <em>rejected</em> by the live path but
 * <em>admitted</em> by this gate's {@code <=}. Verify the intended boundary direction when
 * wiring this type into the extension hot-path.
 */
public final class DistanceSlopeGate implements ExtensionGate {

    private final double linearIntercept;   // NaN → log gate
    private final double linearSlope;       // NaN → log gate
    private final double logScale;          // < 0 → gate disabled
    private final double maxSaving;         // capped at [0, 0.99]
    private final int    minDegree;         // log gate only; floor at 2

    /**
     * Construct a gate with explicit constants, mirroring how
     * {@link BamasRideExtender} reads them from
     * {@link org.matsim.contrib.demand_extraction.config.ExMasConfigGroup}.
     *
     * @param linearIntercept  {@code cfg.getPruningGateLinearIntercept()} — use
     *                         {@link Double#NaN} to disable the linear gate.
     * @param linearSlope      {@code cfg.getPruningGateLinearSlope()} — use
     *                         {@link Double#NaN} to disable the linear gate.
     * @param logScale         {@code cfg.getPruningDistanceSavingsLogScale()} — use
     *                         a value {@code < 0} to disable the log gate entirely.
     * @param maxSaving        {@code cfg.getPruningDistanceSavingsMax()} — maximum
     *                         fractional saving the gate may impose (default 0.75).
     *                         Negative values are treated as 0; values above 0.99
     *                         are clamped to 0.99.
     * @param minDegree        {@code cfg.getPruningDistanceSavingsMinDegree()} — log
     *                         gate is bypassed for pools smaller than this (default 3).
     *                         Floored internally at 2.
     */
    public DistanceSlopeGate(double linearIntercept, double linearSlope,
                              double logScale, double maxSaving, int minDegree) {
        this.linearIntercept = linearIntercept;
        this.linearSlope     = linearSlope;
        this.logScale        = logScale;
        this.maxSaving       = maxSaving;
        this.minDegree       = minDegree;
    }

    /**
     * Returns {@code true} if the candidate ride is within the gate threshold.
     *
     * <p>The body is copied verbatim from
     * {@link BamasRideExtender#computeMaxAllowedRideDistance(int, double,
     * org.matsim.contrib.demand_extraction.config.ExMasConfigGroup)},
     * with {@code maxRideDistance} inlined and the {@code candidateRideDistance <= maxAllowed}
     * comparison appended to produce the boolean admit decision.
     */
    @Override
    public boolean admit(double candidateRideDistance, double sumDirectDistances, int degree) {
        // --- verbatim copy of computeMaxAllowedRideDistance begins ---
        if (!(sumDirectDistances > 0)) return true;   // mirrors: cfg == null || !(sumDistances > 0)

        double maxSavingEff = this.maxSaving;
        if (!(maxSavingEff >= 0)) maxSavingEff = 0.0;
        maxSavingEff = Math.min(0.99, maxSavingEff);

        double maxAllowed;
        if (Double.isFinite(linearIntercept) && Double.isFinite(linearSlope)) {
            // Linear gate branch (cfg.hasLinearGate())
            double gate = linearIntercept + linearSlope * degree;
            // Floor at (1 - maxSaving) to bound how aggressive the gate can get at
            // high degree. Gate > 1.0 is permitted (loose at low degree).
            gate = Math.max(1.0 - maxSavingEff, gate);
            maxAllowed = gate * sumDirectDistances;
        } else {
            // Log gate branch (fallback)
            if (logScale < 0) return true;           // gate disabled
            int minDegreeEff = Math.max(2, this.minDegree);
            if (degree < minDegreeEff) return true;  // degree below activation threshold
            double requiredSaving = logScale * (Math.log(degree) / Math.log(2.0));
            requiredSaving = Math.max(0.0, Math.min(Math.min(0.99, maxSavingEff), requiredSaving));
            maxAllowed = (1.0 - requiredSaving) * sumDirectDistances;
        }
        // --- verbatim copy of computeMaxAllowedRideDistance ends ---

        return candidateRideDistance <= maxAllowed;
    }
}
