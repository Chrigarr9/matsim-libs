package org.matsim.contrib.demand_extraction.demand;

public class ModeAttributes {
    public final double travelTime;
    public final double distance;
    public final double cost;
    public final double score;

    public ModeAttributes(double travelTime, double distance, double cost, double score) {
        this.travelTime = travelTime;
        this.distance = distance;
        this.cost = cost;
        this.score = score;
    }

    @Override
    public String toString() {
        return "ModeAttributes{" +
                "travelTime=" + travelTime +
                ", distance=" + distance +
                ", cost=" + cost +
                ", score=" + score +
                '}';
    }
}
