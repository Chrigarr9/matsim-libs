package org.matsim.contrib.demand_extraction.algorithm.domain;

/**
 * Represents the type/kind of ride based on pickup/dropoff order.
 *
 * Python reference: src/exmas_commuters/core/exmas/rides.py
 * - SINGLE: degree 1 rides
 * - FIFO: First-In-First-Out (O_i -> O_j -> D_i -> D_j) - all origins before all destinations
 * - LIFO: Last-In-First-Out (O_i -> O_j -> D_j -> D_i) - nested pattern
 * - MIXED: Mixed order for degree 3+ rides
 */
public enum RideKind {
    SINGLE,  // Degree 1
    FIFO,    // First-In-First-Out (O_i -> O_j -> D_i -> D_j)
    LIFO,    // Last-In-First-Out (O_i -> O_j -> D_j -> D_i)
    MIXED    // Mixed order (degree 3+)
}
