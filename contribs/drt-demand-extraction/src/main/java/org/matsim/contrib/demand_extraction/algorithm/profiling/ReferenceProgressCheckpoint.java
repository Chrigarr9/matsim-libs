package org.matsim.contrib.demand_extraction.algorithm.profiling;

public record ReferenceProgressCheckpoint(
		String run,
		int degree,
		String status,
		String sampleKind,
		long setsProcessed,
		long setsTotal,
		long ridesRetained,
		long candidatesAdded,
		double heapUsedGb,
		double heapCommittedGb,
		double heapMaxGb,
		long elapsedMs,
		long gcMs,
		String note) {
}