package org.matsim.contrib.demand_extraction.algorithm.profiling;

@FunctionalInterface
public interface ReferenceProgressSink {
	void record(ReferenceProgressCheckpoint checkpoint);
}