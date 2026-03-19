package org.matsim.contrib.demand_extraction.scoring;

import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;

/**
 * Minimal {@link TripCandidate} for passing previous trip context to DMC/eqasim estimators.
 *
 * <p>Used by both {@link DmcMatSimTripAdapter} and {@link EqasimScoringAdapter} when
 * converting {@link PreviousTripContext} into the TripCandidate list expected by
 * the DMC TripEstimator API.
 */
record SimpleTripCandidate(String mode, double duration, double utility) implements TripCandidate {
	@Override
	public String getMode() {
		return mode;
	}

	@Override
	public double getDuration() {
		return duration;
	}

	@Override
	public double getUtility() {
		return utility;
	}
}
