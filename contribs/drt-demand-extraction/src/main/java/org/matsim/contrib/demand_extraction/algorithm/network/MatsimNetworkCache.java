package org.matsim.contrib.demand_extraction.algorithm.network;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculator.Path;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;

import com.google.inject.Inject;
import com.google.inject.Singleton;


/**
 * MATSim-native network implementation with time-binned lazy caching.
 * 
 * DESIGN NOTE: This implementation uses a separate cache layer on top of the router.
 * An alternative design would be to implement a caching LeastCostPathCalculator decorator
 * that wraps the base router (similar to caching patterns used elsewhere in MATSim).
 * The decorator approach would:
 * - Check cache before calling router
 * - Store results after router call
 * - Support mode-specific caching (limited to car mode for DRT)
 * - Be more modular and reusable
 * 
 * Current implementation is acceptable but could be refactored to use the decorator pattern
 * if similar caching is needed elsewhere in the codebase.
 * 
 * Uses MATSim's routing infrastructure (LeastCostPathCalculator) to compute
 * link-to-link travel times and distances. Results are cached with time binning
 * to balance accuracy vs memory/performance.
 * 
 * Time binning groups departure times into bins (e.g., 15-minute intervals),
 * allowing cache reuse for queries in the same bin while maintaining
 * time-dependent routing accuracy.
 */
@Singleton
public class MatsimNetworkCache {
	
	private final Network network;
	private final LeastCostPathCalculator router;
	private final TravelTime travelTime;
	private final TravelDisutility travelDisutility;
	private final int timeBinSize;
	
	// Cache: (originLinkId, destLinkId, timeBin) -> TravelSegment
	private final ConcurrentHashMap<CacheKey, TravelSegment> cache = new ConcurrentHashMap<>();
	
	@Inject
	public MatsimNetworkCache(
			Network network,
			LeastCostPathCalculator router,
			TravelTime travelTime,
			TravelDisutility travelDisutility,
			ExMasConfigGroup config) {
		this.network = network;
		this.router = router;
		this.travelTime = travelTime;
		this.travelDisutility = travelDisutility;
		this.timeBinSize = config.getNetworkTimeBinSize();
	}
	
	/**
	 * Get travel segment between links at specified departure time.
	 * Results are cached per time bin for efficiency.
	 * 
	 * @param originLinkId origin link
	 * @param destLinkId destination link
	 * @param departureTime departure time (seconds since midnight)
	 * @return travel segment with metrics, or infinity segment if unreachable
	 */
	public TravelSegment getSegment(Id<Link> originLinkId, Id<Link> destLinkId, double departureTime) {
		// Calculate time bin
		int timeBin = (int) (departureTime / timeBinSize);
		
		CacheKey key = new CacheKey(originLinkId, destLinkId, timeBin);
		
		// Check cache
		TravelSegment segment = cache.get(key);
		if (segment != null) {
			return segment;
		}
		
		// Compute and cache
		segment = computeSegment(originLinkId, destLinkId, departureTime);
		cache.put(key, segment);
		return segment;
	}
	
	/**
	 * Check if connection exists between links.
	 */
	public boolean hasConnection(Id<Link> originLinkId, Id<Link> destLinkId, double departureTime) {
		return getSegment(originLinkId, destLinkId, departureTime).isReachable();
	}
	
	// Node index to Link ID mapping for algorithm
	private final Map<Integer, Id<Link>> indexToLinkMap = new HashMap<>();
	
	/**
	 * Gets a sequential node index for a link ID.
	 * This is used by the algorithm for compact array-based storage.
	 * Returns a consistent index for the same link across calls.
	 */
	public int getNodeIndex(Id<Link> linkId) {
		// Simple hash-based indexing - could be optimized with a map if needed
		int index = Math.abs(linkId.hashCode());
		indexToLinkMap.put(index, linkId);
		return index;
	}
	
	/**
	 * Overload for node-index-based segment lookup (used by algorithm).
	 * Maps indices back to link IDs and delegates to main getSegment method.
	 */
	public TravelSegment getSegment(int fromNodeIndex, int toNodeIndex) {
		Id<Link> fromLinkId = indexToLinkMap.get(fromNodeIndex);
		Id<Link> toLinkId = indexToLinkMap.get(toNodeIndex);
		
		if (fromLinkId == null || toLinkId == null) {
			// Unknown indices - return unreachable segment
			return TravelSegment.unreachable();
		}
		
		// Use cached routing with standard departure time
		return getSegment(fromLinkId, toLinkId, 8.0 * 3600.0);
	}
	
	/**
	 * Gets network utility for a segment (time + distance based).
	 * This is a simplified utility calculation for the algorithm.
	 */
	public double getNetworkUtility(int fromNodeIndex, int destNodeIndex) {
		// Placeholder - algorithm doesn't actually use this in MATSim version
		// Real utility comes from TravelSegment.getNetworkUtility()
		return 0.0;
	}
	
	/**
	 * Pre-populate cache with specific O-D pairs only.
	 * Useful for filtering cache to only relevant connections.
	 * 
	 * @param connections list of origin-destination link ID pairs
	 * @param departureTime reference departure time for routing
	 */
	public void preloadConnections(List<Pair<Id<Link>, Id<Link>>> connections, double departureTime) {
		for (Pair<Id<Link>, Id<Link>> conn : connections) {
			getSegment(conn.getFirst(), conn.getSecond(), departureTime);
		}
	}
	
	/**
	 * Export cache to CSV file.
	 * Format: originLinkId, destLinkId, timeBin, travelTime, distance, utility
	 */
	public void exportCache(String filepath) throws IOException {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(filepath))) {
			writer.write("originLinkId,destLinkId,timeBin,travelTime,distance,utility\n");
			
			for (Map.Entry<CacheKey, TravelSegment> entry : cache.entrySet()) {
				CacheKey key = entry.getKey();
				TravelSegment seg = entry.getValue();
				
				if (!seg.isReachable()) {
					continue; // Skip unreachable segments
				}
				
				writer.write(String.format("%s,%s,%d,%.2f,%.2f,%.4f\n",
						key.origin, key.destination, key.timeBin,
						seg.getTravelTime(), seg.getDistance(), seg.getNetworkUtility()));
			}
		}
	}
	
	/**
	 * Import cache from CSV file.
	 * Only imports entries for links that exist in current network.
	 */
	public void importCache(String filepath) throws IOException {
		try (BufferedReader reader = new BufferedReader(new FileReader(filepath))) {
			reader.readLine(); // Skip header
			
			String line;
			while ((line = reader.readLine()) != null) {
				String[] parts = line.split(",");
				if (parts.length != 6) continue;
				
				try {
					Id<Link> origin = Id.createLinkId(parts[0]);
					Id<Link> dest = Id.createLinkId(parts[1]);
					int timeBin = Integer.parseInt(parts[2]);
					double tt = Double.parseDouble(parts[3]);
					double dist = Double.parseDouble(parts[4]);
					double util = Double.parseDouble(parts[5]);
					
					// Only import if links exist in network
					if (network.getLinks().containsKey(origin) && network.getLinks().containsKey(dest)) {
						CacheKey key = new CacheKey(origin, dest, timeBin);
						TravelSegment seg = new TravelSegment(0, 0, tt, dist, util);
						cache.put(key, seg);
					}
				} catch (Exception e) {
					// Skip invalid entries
				}
			}
		}
	}
	
	/**
	 * Clear the cache. Useful for memory management or when network conditions change.
	 */
	public void clearCache() {
		cache.clear();
	}
	
	/**
	 * Get current cache size (number of cached segments).
	 */
	public int getCacheSize() {
		return cache.size();
	}
	
	/**
	 * Simple pair class for O-D connections.
	 */
	public static class Pair<A, B> {
		private final A first;
		private final B second;
		
		public Pair(A first, B second) {
			this.first = first;
			this.second = second;
		}
		
		public A getFirst() { return first; }
		public B getSecond() { return second; }
	}
	
	private TravelSegment computeSegment(Id<Link> originLinkId, Id<Link> destLinkId, double departureTime) {
		Link originLink = network.getLinks().get(originLinkId);
		Link destLink = network.getLinks().get(destLinkId);
		
		if (originLink == null || destLink == null) {
			// Links don't exist in network
			return createInfinitySegment();
		}
		
		if (originLinkId.equals(destLinkId)) {
			// Same link - zero travel
			return new TravelSegment(0, 0, 0.0, 0.0, 0.0);
		}
		
		try {
			// Route from end of origin link to start of destination link
			Path path = router.calcLeastCostPath(
					originLink.getToNode(),
					destLink.getFromNode(),
					departureTime,
					null, // person (null = generic routing)
					null  // vehicle (null = generic routing)
			);
			
			if (path == null || path.links.isEmpty()) {
				// No path found
				return createInfinitySegment();
			}
			
			// Calculate metrics
			double tt = path.travelTime;
			double dist = path.links.stream().mapToDouble(link -> link.getLength()).sum();
			
			// Add origin and destination link lengths
			tt += originLink.getLength() / originLink.getFreespeed();
			tt += destLink.getLength() / destLink.getFreespeed();
			dist += originLink.getLength() + destLink.getLength();
			
			// Network utility: negative of generalized cost (disutility)
			// This allows sorting by "best" routes (higher utility = better)
			double disutility = path.travelCost;
			double utility = -disutility;
			
			return new TravelSegment(0, 0, tt, dist, utility);
			
		} catch (Exception e) {
			// Routing failed
			return createInfinitySegment();
		}
	}
	
	private TravelSegment createInfinitySegment() {
		return new TravelSegment(0, 0, 
				Double.POSITIVE_INFINITY, 
				Double.POSITIVE_INFINITY, 
				Double.NEGATIVE_INFINITY);
	}
	
	/**
	 * Cache key for link-to-link travel at specific time bin.
	 */
	private static class CacheKey {
		private final Id<Link> origin;
		private final Id<Link> destination;
		private final int timeBin;
		
		CacheKey(Id<Link> origin, Id<Link> destination, int timeBin) {
			this.origin = origin;
			this.destination = destination;
			this.timeBin = timeBin;
		}
		
		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (!(obj instanceof CacheKey)) return false;
			CacheKey other = (CacheKey) obj;
			return timeBin == other.timeBin &&
					origin.equals(other.origin) &&
					destination.equals(other.destination);
		}
		
		@Override
		public int hashCode() {
			int result = origin.hashCode();
			result = 31 * result + destination.hashCode();
			result = 31 * result + timeBin;
			return result;
		}
	}
}
