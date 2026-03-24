package org.matsim.contrib.demand_extraction.upsampling;

import org.matsim.api.core.v01.population.Person;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;

import java.util.Random;

/**
 * Adapts eqasim Bavaria population attributes to match the 25% Kelheim (Senozon/TU Berlin) conventions.
 *
 * This is critical for correct scoring because:
 * - Income-dependent marginal utility of money scales budgets per person
 * - Car availability encoding determines which baseline modes are considered
 * - Missing attributes (subpopulation, MiD groups) cause scoring lookup failures
 *
 * The income derivation uses the same formula as matsim-kelheim's PreparePopulation.java.
 */
public final class AttributeAdapter {

    private AttributeAdapter() {} // utility class

    /**
     * Adapt a single person's attributes from eqasim format to Kelheim format.
     * Modifies the person in-place.
     *
     * @param person the person to adapt
     * @param householdSize household size from households CSV (NOT in person XML attributes)
     * @param rnd random source for income stochasticity (same as PreparePopulation.java)
     */
    public static void adapt(Person person, int householdSize, Random rnd) {
        adaptCarAvailability(person);
        adaptPtSubscription(person);
        adaptSubpopulation(person);
        adaptHouseholdSizeGroup(person, householdSize);
        adaptIncome(person, householdSize, rnd);
    }

    private static void adaptCarAvailability(Person person) {
        String carAvail = (String) person.getAttributes().getAttribute("carAvailability");
        if (carAvail == null) return;

        String mapped = switch (carAvail) {
            case "all" -> "always";
            case "none" -> "never";
            default -> carAvail;
        };

        PersonUtils.setCarAvail(person, mapped);
        person.getAttributes().putAttribute("sim_carAvailability", mapped);
    }

    private static void adaptPtSubscription(Person person) {
        Object ptSub = person.getAttributes().getAttribute("hasPtSubscription");
        if (ptSub == null) return;

        boolean hasPt = ptSub instanceof Boolean ? (Boolean) ptSub : Boolean.parseBoolean(ptSub.toString());
        person.getAttributes().putAttribute("sim_ptAbo", hasPt ? "full" : "none");
    }

    private static void adaptSubpopulation(Person person) {
        if (PopulationUtils.getSubpopulation(person) == null) {
            PopulationUtils.putSubpopulation(person, "person");
        }
    }

    private static void adaptHouseholdSizeGroup(Person person, int householdSize) {
        int hhSizeGroup = Math.min(householdSize, 5); // MiD caps at 5
        person.getAttributes().putAttribute("MiD:hhgr_gr", String.valueOf(hhSizeGroup));
    }

    private static void adaptIncome(Person person, int householdSize, Random rnd) {
        Object hhIncomeObj = person.getAttributes().getAttribute("householdIncome");
        if (hhIncomeObj == null) return;

        String hhIncome = hhIncomeObj.toString();
        double hhSize = Math.max(1, householdSize); // avoid division by zero

        // Map eqasim HH income band (EUR/month) -> MiD income group (1-10)
        // MiD groups: 1=<500, 2=500-900, 3=900-1500, 4=1500-2000, 5=2000-3000,
        //             6=3000-4000, 7=4000-5000, 8=5000-6000, 9=6000-7000, 10=7000+
        int incomeGroup = switch (hhIncome) {
            case "0-500" -> 1;
            case "500-1000" -> 2;
            case "1000-1250", "1250-1500" -> 3;
            case "1500-2000" -> 4;
            case "2000-2500", "2500-3000" -> 5;
            case "3000-3500", "3500-4000" -> 6;
            case "4000-5000" -> 7;
            case "5000+" -> 8; // conservative: 5000+ spans groups 8-10
            default -> 0;
        };

        person.getAttributes().putAttribute("MiD:hheink_gr2", String.valueOf(incomeGroup));

        // Derive per-person income using PreparePopulation.java formula
        double income = switch (incomeGroup) {
            case 1 -> 500 / hhSize;
            case 2 -> (rnd.nextInt(400) + 500) / hhSize;
            case 3 -> (rnd.nextInt(600) + 900) / hhSize;
            case 4 -> (rnd.nextInt(500) + 1500) / hhSize;
            case 5 -> (rnd.nextInt(1000) + 2000) / hhSize;
            case 6 -> (rnd.nextInt(1000) + 3000) / hhSize;
            case 7 -> (rnd.nextInt(1000) + 4000) / hhSize;
            case 8 -> (rnd.nextInt(1000) + 5000) / hhSize;
            case 9 -> (rnd.nextInt(1000) + 6000) / hhSize;
            case 10 -> (Math.abs(rnd.nextGaussian()) * 1000 + 7000) / hhSize;
            default -> 2364; // national average
        };

        PersonUtils.setIncome(person, income);
    }
}
