package org.matsim.contrib.demand_extraction.scoring;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;

/**
 * Frozen-scalar twin of {@link EqasimScoringAdapter} that the low-memory two-phase
 * mode uses in Phase 2. Phase 2 has no Population, no eqasim DI graph, and no
 * {@code TripEstimator} — only the 6 ModeParameters scalars harvested at the end
 * of Phase 1.
 *
 * <p>Supports the two methods Phase 2 actually calls on the adapter:
 * <ul>
 *   <li>{@link #scoreTrip} for DRT candidate modes ({@code mode.startsWith("drt")}) —
 *       replicates {@code EqasimScoringAdapter.scoreDrtDirectly} exactly</li>
 *   <li>{@link #getMarginalUtilityOfMoney} — replicates
 *       {@code EqasimCostParameters.marginalUtilityOfMoney}</li>
 * </ul>
 *
 * <p>Baseline-mode scoring (the {@code scoreViaEstimator} branch in
 * {@link EqasimScoringAdapter}) is <b>unsupported</b>: any non-DRT mode throws
 * {@link UnsupportedOperationException}. Phase 2 never invokes it because the
 * algorithm only scores DRT candidates against the precomputed {@code bestModeScore}.
 */
public final class Phase2EqasimAdapter implements DemandExtractionScoringAdapter {

	private final double drtAlpha_u;
	private final double drtBetaTravelTime_u_min;
	private final double drtBetaAccessEgressTime_u_min;
	private final double betaCost_u_MU;
	private final double lambdaCostEuclideanDistance;
	private final double referenceEuclideanDistance_km;

	public Phase2EqasimAdapter(
			double drtAlpha_u,
			double drtBetaTravelTime_u_min,
			double drtBetaAccessEgressTime_u_min,
			double betaCost_u_MU,
			double lambdaCostEuclideanDistance,
			double referenceEuclideanDistance_km) {
		this.drtAlpha_u = drtAlpha_u;
		this.drtBetaTravelTime_u_min = drtBetaTravelTime_u_min;
		this.drtBetaAccessEgressTime_u_min = drtBetaAccessEgressTime_u_min;
		this.betaCost_u_MU = betaCost_u_MU;
		this.lambdaCostEuclideanDistance = lambdaCostEuclideanDistance;
		this.referenceEuclideanDistance_km = referenceEuclideanDistance_km;
	}

	@Override
	public String getName() {
		return "eqasim-phase2";
	}

	@Override
	public TripScoreResult scoreTrip(TripScoreRequest request) {
		String mode = request.candidateMode();
		if (!mode.startsWith("drt")) {
			throw new UnsupportedOperationException(
					"Phase2EqasimAdapter scores DRT candidates only; got mode=" + mode);
		}

		double travelTime_min = 0;
		double accessEgressTime_min = 0;
		for (PlanElement pe : request.routedElements()) {
			if (pe instanceof Leg leg) {
				double tt = leg.getTravelTime().orElse(0.0) / 60.0;
				if (leg.getMode().contains(TransportMode.walk)) {
					accessEgressTime_min += tt;
				} else {
					travelTime_min += tt;
				}
			}
		}
		double utility = drtBetaTravelTime_u_min * travelTime_min
				+ drtBetaAccessEgressTime_u_min * accessEgressTime_min;
		if (!request.excludeModeConstant()) {
			utility += drtAlpha_u;
		}
		return new TripScoreResult(utility, "eqasim-phase2:DrtDirect");
	}

	@Override
	public double getMarginalUtilityOfMoney(Person person, double euclideanDistance_km) {
		if (euclideanDistance_km <= 0 || referenceEuclideanDistance_km <= 0) {
			return Math.abs(betaCost_u_MU);
		}
		double interaction = Math.pow(
				euclideanDistance_km / referenceEuclideanDistance_km,
				lambdaCostEuclideanDistance);
		return Math.abs(betaCost_u_MU) * interaction;
	}

	@Override
	public boolean includesOpportunityCost() {
		return true;
	}

	@Override
	public boolean supportsDistanceSpecificMoneyUtility() {
		return true;
	}
}
