package org.matsim.contrib.demand_extraction.algorithm;

import java.util.List;
import java.util.Map;

import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;

/**
 * Result of a Stage-1 algorithm run.
 *
 * <p>{@code diagnostics} is a loosely-typed bag so each strategy can publish
 * whatever runtime numbers it collects (enumeration stats for BAMAS, nothing
 * or timing-only for the reference ExMAS) without forcing a shared type. Keys
 * are stable per-strategy; callers that care about specific numbers should
 * look them up by string key.
 */
public record AlgorithmResult(List<Ride> rides, Map<String, Object> diagnostics) { }
