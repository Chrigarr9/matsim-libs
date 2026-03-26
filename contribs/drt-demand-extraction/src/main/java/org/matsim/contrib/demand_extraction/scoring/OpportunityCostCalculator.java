package org.matsim.contrib.demand_extraction.scoring;

import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.OpportunityCostModel;
import org.matsim.core.scoring.functions.ActivityUtilityParameters;
import org.matsim.core.scoring.functions.ScoringParameters;

/**
 * Computes opportunity cost for trips using either a constant rate (LINEAR)
 * or MATSim's exact log-utility formula (LOG).
 *
 * <p>LOG model: the rational traveler shortens whichever adjacent activity has
 * the lower marginal utility of time. The exact utility loss from shortening
 * activity A by delta seconds is:
 * <pre>
 *   loss = beta_perf * t_typ * ln(t_actual / (t_actual - delta))
 * </pre>
 * The opportunity cost is the minimum of this over origin and destination.
 */
public final class OpportunityCostCalculator {

    private OpportunityCostCalculator() {}

    /**
     * Compute opportunity cost (positive value to subtract from score).
     *
     * @param model           the opportunity cost model (LINEAR or LOG)
     * @param params          scoring parameters for the person
     * @param travelTime      total travel time of the trip (seconds)
     * @param originActivity  the origin activity (for LOG: type lookup)
     * @param destActivity    the destination activity (for LOG: type lookup)
     * @param originDuration  actual duration of origin activity in seconds (for LOG)
     * @param destDuration    actual duration of destination activity in seconds (for LOG)
     * @return opportunity cost in utils (always >= 0)
     */
    public static double compute(OpportunityCostModel model, ScoringParameters params,
            double travelTime, Activity originActivity, Activity destActivity,
            double originDuration, double destDuration) {

        if (model == OpportunityCostModel.NONE || travelTime <= 0) {
            return 0.0;
        }

        if (model == OpportunityCostModel.LINEAR) {
            return travelTime * params.marginalUtilityOfPerforming_s;
        }

        // LOG model: exact log-utility with activity-aware durations
        double betaPerf = params.marginalUtilityOfPerforming_s;
        double originLoss = logUtilityLoss(betaPerf, params, originActivity, originDuration, travelTime);
        double destLoss = logUtilityLoss(betaPerf, params, destActivity, destDuration, travelTime);

        return Math.min(originLoss, destLoss);
    }

    /**
     * Compute the exact log-utility loss from shortening an activity by delta seconds.
     *
     * <p>Formula: beta_perf * t_typ * ln(t_actual / (t_actual - delta))
     *
     * <p>Falls back to linear (beta_perf * delta) if activity params are missing
     * or typicalDuration is not set.
     */
    private static double logUtilityLoss(double betaPerf, ScoringParameters params,
            Activity activity, double actualDuration, double delta) {

        if (actualDuration <= delta) {
            // Can't shorten below 0 — this activity can't absorb the travel time
            return Double.MAX_VALUE;
        }

        ActivityUtilityParameters actParams = params.utilParams.get(activity.getType());
        if (actParams == null || actParams.getTypicalDuration() <= 0 || !actParams.isScoreAtAll()) {
            // Fallback to linear
            return betaPerf * delta;
        }

        double tTyp = actParams.getTypicalDuration();
        return betaPerf * tTyp * Math.log(actualDuration / (actualDuration - delta));
    }

    /**
     * Compute actual activity durations from a person's selected plan.
     *
     * <p>Walks through plan elements with a clock. First activity starts at t=0,
     * last activity ends at t=86400 (end of day).
     *
     * @param plan the person's selected plan
     * @return array of activity durations in seconds, indexed by activity position
     *         (activity 0 = first, activity N = last). Trip i has origin = index i,
     *         destination = index i+1.
     */
    public static double[] computeActivityDurations(Plan plan) {
        // Count activities
        int numActivities = 0;
        for (PlanElement pe : plan.getPlanElements()) {
            if (pe instanceof Activity) numActivities++;
        }

        double[] durations = new double[numActivities];
        double clock = 0.0;
        int actIdx = 0;

        for (PlanElement pe : plan.getPlanElements()) {
            if (pe instanceof Activity act) {
                double startTime = clock;
                double endTime;
                if (act.getEndTime().isDefined()) {
                    endTime = act.getEndTime().seconds();
                } else {
                    endTime = 86400.0; // last activity: assume end of day
                }
                durations[actIdx] = Math.max(0.0, endTime - startTime);
                clock = endTime;
                actIdx++;
            } else if (pe instanceof Leg leg) {
                clock += leg.getTravelTime().orElse(0.0);
            }
        }

        return durations;
    }
}
