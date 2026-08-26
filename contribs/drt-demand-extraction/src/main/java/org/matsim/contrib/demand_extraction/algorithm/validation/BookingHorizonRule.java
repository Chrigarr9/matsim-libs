package org.matsim.contrib.demand_extraction.algorithm.validation;

import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Booking-time admissibility: no vehicle departs before all of its passengers
 * have booked. Mandatory members (commute or education) are prebooked
 * day-ahead and never bind; a spontaneous member k requires
 * rideStartTime >= k.requestTime - horizon.
 * Spec: docs/superpowers/specs/2026-08-25-booking-horizons-and-urban-whitelist-design.md
 */
public final class BookingHorizonRule {
    private BookingHorizonRule() {}

    public static boolean isMandatory(DrtRequest r) {
        return r.isCommute || r.isEducation;
    }

    public static boolean isAdmissible(double rideStartTime, DrtRequest[] members,
            double spontaneousHorizonSeconds) {
        if (spontaneousHorizonSeconds <= 0) return true;
        for (DrtRequest m : members) {
            if (!isMandatory(m) && rideStartTime < m.requestTime - spontaneousHorizonSeconds) {
                return false;
            }
        }
        return true;
    }

    public static boolean isAdmissible(double rideStartTime, DrtRequest a, DrtRequest b,
            double spontaneousHorizonSeconds) {
        if (spontaneousHorizonSeconds <= 0) return true;
        return (isMandatory(a) || rideStartTime >= a.requestTime - spontaneousHorizonSeconds)
            && (isMandatory(b) || rideStartTime >= b.requestTime - spontaneousHorizonSeconds);
    }
}
