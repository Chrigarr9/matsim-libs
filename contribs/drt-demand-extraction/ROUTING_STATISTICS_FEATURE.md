# Network Routing Statistics Feature

## Overview
Added comprehensive tracking and summary logging for network routing attempts in the ExMAS algorithm. This addresses the verbose routing warnings from MATSim's SpeedyALT router by providing a concise summary of routing success/failure rates instead of individual warnings for each failed route.

## Problem
When the network has connectivity issues, MATSim's router generates verbose warnings like:
```
WARN SpeedyALT:99 No route was found from link -27172624#1 to link -37289122. Some possible reasons:
WARN SpeedyALT:100   * Network is not connected.  Run NetworkUtils.cleanNetwork(Network network, Set<String> modes).
WARN SpeedyALT:101   * Network for considered mode does not even exist.  Modes need to be entered for each link in network.xml.
WARN SpeedyALT:102   * Network for considered mode is not connected to starting or ending point of route.  Setting insertingAccessEgressWalk to true may help.
WARN SpeedyALT:103 This will now return null, but it may fail later with a NullPointerException.
```

These warnings can clutter logs significantly when many route pairs are attempted.

## Solution
Added routing statistics tracking in `MatsimNetworkCache`:

### New Features

1. **Automatic Failure Tracking**
   - Thread-safe counters track total routing attempts and failures
   - Counters increment during each `computeSegment()` call
   - No changes needed to existing code using the cache

2. **Statistics Summary Logging**
   - New method `logRoutingStatistics()` provides a concise summary
   - Called automatically at the end of ExMAS algorithm execution
   - Shows:
     - Total routing attempts
     - Successful routes (count and percentage)
     - Failed routes (count and percentage)
     - Cache size (number of unique cached segments)

3. **Smart Warning Thresholds**
   - If failure rate > 10%, logs warning about potential network issues
   - Suggests running `NetworkUtils.cleanNetwork()` or checking mode assignments
   - Helps users quickly identify network configuration problems

### Example Output
```
Network cache statistics:
  Total routing attempts: 25,000
  Successful routes: 23,500 (94.0%)
  Failed routes: 1,500 (6.0%)
  Cache size: 12,345 entries
```

Or with high failure rate:
```
Network cache statistics:
  Total routing attempts: 25,000
  Successful routes: 18,750 (75.0%)
  Failed routes: 6,250 (25.0%)
  Cache size: 9,876 entries
WARN High routing failure rate (25.0%). This may indicate network connectivity issues.
WARN Consider running NetworkUtils.cleanNetwork() or checking network mode assignments.
```

## Implementation Details

### Modified Files

#### `MatsimNetworkCache.java`
- Added imports: `AtomicInteger`, `LogManager`, `Logger`
- Added fields:
  ```java
  private static final Logger log = LogManager.getLogger(MatsimNetworkCache.class);
  private final AtomicInteger routingFailures = new AtomicInteger(0);
  private final AtomicInteger totalRoutingAttempts = new AtomicInteger(0);
  ```
- Modified `computeSegment()`:
  - Increments `totalRoutingAttempts` at start
  - Increments `routingFailures` when routing fails
  - Thread-safe for parallel processing
- Added methods:
  - `getRoutingStatistics()`: Returns array `[total, failures]`
  - `logRoutingStatistics()`: Logs formatted summary with warnings if needed
  - `resetStatistics()`: Resets counters for reuse scenarios

#### `ExMasEngine.java`
- Added call to `network.logRoutingStatistics()` at end of `run()` method
- Statistics logged after algorithm completion summary
- Works for both early termination (maxDegree <= 2) and full execution

## Benefits

1. **Reduced Log Clutter**
   - One summary instead of hundreds/thousands of individual warnings
   - Easier to spot actual issues in logs

2. **Better Visibility**
   - Clear percentage-based success/failure rates
   - Cache utilization metrics
   - Automatic warnings for high failure rates

3. **No Performance Impact**
   - Lightweight atomic counters
   - Summary logged only once at algorithm end
   - Thread-safe for parallel processing

4. **Backward Compatible**
   - No changes to existing API
   - Statistics tracking is automatic
   - Can be disabled by not calling `logRoutingStatistics()`

## Usage

The statistics are logged automatically at the end of ExMAS algorithm execution. No code changes needed in existing workflows.

For manual statistics access:
```java
MatsimNetworkCache cache = ... // injected
int[] stats = cache.getRoutingStatistics();
int totalAttempts = stats[0];
int failures = stats[1];
double successRate = 100.0 * (totalAttempts - failures) / totalAttempts;

// Or just log the summary
cache.logRoutingStatistics();
```

To reset statistics (e.g., between iterations):
```java
cache.resetStatistics();
```

## Future Enhancements

Possible improvements:
1. Track failure reasons (missing links, no path, exceptions)
2. Export detailed failure locations for debugging
3. Configurable failure rate warning threshold
4. Integration with MATSim's event system for monitoring

## Testing

Verify the feature by:
1. Run ExMAS algorithm on a scenario with network connectivity issues
2. Check log output for routing statistics summary
3. Verify failure rate matches expectations
4. Confirm warnings appear when failure rate > 10%
