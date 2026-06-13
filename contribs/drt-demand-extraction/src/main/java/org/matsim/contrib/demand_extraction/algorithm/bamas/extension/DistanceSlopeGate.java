package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;

/**
 * Single owner of the BAMAS distance-savings gate formula.
 *
 * <p>Two gate shapes share one class, mirroring the single {@code if (cfg.hasLinearGate())}
 * branch in the original inlined computation:
 * <ol>
 *   <li><b>Linear gate</b> (active when {@code intercept} and {@code slope} are both
 *       finite): threshold = {@code max(1 - maxSaving, intercept + slope * degree) * sumDirectDistances}.
 *   <li><b>Log gate</b> (fallback when linear is not configured): threshold =
 *       {@code (1 - requiredSaving) * sumDirectDistances} where
 *       {@code requiredSaving = clamp(logScale * log2(degree), 0, min(0.99, maxSaving))}.
 *       When {@code logScale < 0} or {@code degree < minDegree} the gate is disabled and
 *       {@link #maxAllowedRideDistance} returns {@link Double#MAX_VALUE}.
 * </ol>
 *
 * <p>The formula body of {@link #maxAllowedRideDistance} is the verbatim former content of
 * {@code BamasRideExtender.computeMaxAllowedRideDistance(int, double, ExMasConfigGroup)} with
 * {@code cfg} field reads replaced by the gate's own fields. That static method now delegates
 * here, so the formula lives in exactly one place.
 */
public final class DistanceSlopeGate implements ExtensionGate {

	private final double linearIntercept;   // NaN → log gate
	private final double linearSlope;       // NaN → log gate
	private final double logScale;          // < 0 → gate disabled
	private final double maxSaving;         // capped at [0, 0.99]
	private final int    minDegree;         // log gate only; floor at 2

	/**
	 * Construct a gate with explicit constants, mirroring how {@link ExMasConfigGroup} stores
	 * them. Prefer {@link #fromConfig(ExMasConfigGroup)} for live wiring.
	 *
	 * @param linearIntercept  {@code cfg.getPruningGateLinearIntercept()} — {@link Double#NaN}
	 *                         disables the linear gate.
	 * @param linearSlope      {@code cfg.getPruningGateLinearSlope()} — {@link Double#NaN}
	 *                         disables the linear gate.
	 * @param logScale         {@code cfg.getPruningDistanceSavingsLogScale()} — a value
	 *                         {@code < 0} disables the log gate entirely.
	 * @param maxSaving        {@code cfg.getPruningDistanceSavingsMax()} — maximum fractional
	 *                         saving the gate may impose (default 0.75). Negative → 0;
	 *                         values above 0.99 are clamped to 0.99.
	 * @param minDegree        {@code cfg.getPruningDistanceSavingsMinDegree()} — log gate is
	 *                         bypassed for pools smaller than this (default 3). Floored at 2.
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
	 * Build a gate from live config, or a permanently-disabled gate (always
	 * {@link Double#MAX_VALUE}) when {@code cfg} is {@code null} — matching the original
	 * {@code cfg == null} short-circuit in the extender.
	 */
	public static DistanceSlopeGate fromConfig(ExMasConfigGroup cfg) {
		if (cfg == null) {
			// linear off (NaN) + logScale < 0 → maxAllowedRideDistance always returns MAX_VALUE.
			return new DistanceSlopeGate(Double.NaN, Double.NaN, -1.0, 0.0, 2);
		}
		return new DistanceSlopeGate(
				cfg.getPruningGateLinearIntercept(),
				cfg.getPruningGateLinearSlope(),
				cfg.getPruningDistanceSavingsLogScale(),
				cfg.getPruningDistanceSavingsMax(),
				cfg.getPruningDistanceSavingsMinDegree());
	}

	/**
	 * Maximum allowed ride distance for a pool at the given degree, or {@link Double#MAX_VALUE}
	 * when the gate is disabled or {@code sumDirectDistances <= 0}.
	 *
	 * <p>Body copied verbatim from the former
	 * {@code BamasRideExtender.computeMaxAllowedRideDistance(int, double, ExMasConfigGroup)}.
	 */
	@Override
	public double maxAllowedRideDistance(int degree, double sumDirectDistances) {
		if (!(sumDirectDistances > 0)) return Double.MAX_VALUE;
		double maxSavingEff = this.maxSaving;
		if (!(maxSavingEff >= 0)) maxSavingEff = 0.0;
		maxSavingEff = Math.min(0.99, maxSavingEff);

		if (Double.isFinite(linearIntercept) && Double.isFinite(linearSlope)) {
			// Linear gate branch (cfg.hasLinearGate())
			double gate = linearIntercept + linearSlope * degree;
			// Floor at (1 - maxSaving) to bound how aggressive the gate can get at
			// high degree. Gate > 1.0 is permitted (loose at low degree).
			gate = Math.max(1.0 - maxSavingEff, gate);
			return gate * sumDirectDistances;
		}

		// Log gate branch (fallback)
		if (logScale < 0) return Double.MAX_VALUE;            // gate disabled
		int minDegreeEff = Math.max(2, this.minDegree);
		if (degree < minDegreeEff) return Double.MAX_VALUE;   // degree below activation threshold
		double requiredSaving = logScale * (Math.log(degree) / Math.log(2.0));
		requiredSaving = Math.max(0.0, Math.min(Math.min(0.99, maxSavingEff), requiredSaving));
		return (1.0 - requiredSaving) * sumDirectDistances;
	}
}
