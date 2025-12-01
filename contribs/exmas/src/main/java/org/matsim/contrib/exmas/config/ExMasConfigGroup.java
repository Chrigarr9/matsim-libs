package org.matsim.contrib.exmas.config;

import org.matsim.core.config.ReflectiveConfigGroup;

import java.util.Map;

public class ExMasConfigGroup extends ReflectiveConfigGroup {
    public static final String GROUP_NAME = "exmas";

    private static final String BUDGET_CALCULATION_MODE = "budgetCalculationMode";
    private static final String DRT_MODE = "drtMode";

    public enum BudgetCalculationMode {
        TRIP_LEVEL,
        SUBTOUR_SUM
    }

    private BudgetCalculationMode budgetCalculationMode = BudgetCalculationMode.TRIP_LEVEL;
    private String drtMode = "drt";
    private Map<String, String> baseModes = Map.of("car", "pt", "walk", "bike"); 

    public ExMasConfigGroup() {
        super(GROUP_NAME);
    }

    @StringGetter(BUDGET_CALCULATION_MODE)
    public BudgetCalculationMode getBudgetCalculationMode() {
        return budgetCalculationMode;
    }

    @StringSetter(BUDGET_CALCULATION_MODE)
    public void setBudgetCalculationMode(BudgetCalculationMode budgetCalculationMode) {
        this.budgetCalculationMode = budgetCalculationMode;
    }

    @StringGetter(DRT_MODE)
    public String getDrtMode() {
        return drtMode;
    }

    @StringSetter(DRT_MODE)
    public void setDrtMode(String drtMode) {
        this.drtMode = drtMode;
    }
    
    public Map<String, String> getBaseModes() {
        return baseModes;
    }
    
    public void setBaseModes(Map<String, String> modes) {
        this.baseModes = modes;
    }

    @StringGetter("minDrtAccessEgressDistance")
    public double getMinDrtAccessEgressDistance() {
        return minDrtAccessEgressDistance;
    }

    @StringSetter("minDrtAccessEgressDistance")
    public void setMinDrtAccessEgressDistance(double minDrtAccessEgressDistance) {
        this.minDrtAccessEgressDistance = minDrtAccessEgressDistance;
    }

    private double minDrtAccessEgressDistance = 100.0;

    @Override
    public Map<String, String> getComments() {
        Map<String, String> map = super.getComments();
        map.put(BUDGET_CALCULATION_MODE, "Mode for calculating utility budget. Options: [TRIP_LEVEL, SUBTOUR_SUM]. Default: TRIP_LEVEL.");
        map.put(DRT_MODE, "The mode name of the DRT service to be optimized. Default: 'drt'.");
        return map;
    }
}
