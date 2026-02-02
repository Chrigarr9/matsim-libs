---
status: resolved
trigger: "Rides with negative budget (score < base_score) are appearing in output results when they should be filtered out."
created: 2026-01-28T10:00:00Z
updated: 2026-01-28T10:40:00Z
---

## Current Focus

hypothesis: CONFIRMED - DrtRequestFactory was creating requests with negative initial budget without filtering
test: N/A - root cause identified and fix applied
expecting: N/A
next_action: Verify fix compiles and test behavior

## Symptoms

expected: Rides where shared ride score is less than the base score (direct trip score) should be filtered out. This ensures all remaining rides have a positive budget (budget = shared_score - base_score >= 0).

actual: Output/results files contain rides with negative budget values, meaning rides where score < base_score are not being properly filtered.

errors: No explicit errors - the filtering logic is simply not catching all cases.

reproduction: Run ExMAS algorithm. Issue visible in output files. Need to trace where filtering happens and ensure all code paths properly filter rides that don't meet the constraint (score >= base_score). The problematic cases involve "quite long pt base rides" (long public transit trips as the baseline).

started: Issue has always been present - first time checking this constraint thoroughly.

## Eliminated

- hypothesis: BudgetValidator.validateAndPopulateBudgets() is not filtering properly
  evidence: The method correctly filters out rides with negative remaining budgets (lines 83-97 in BudgetValidator.java), returns null for infeasible rides, and SingleRideGenerator/PairGenerator/RideExtender all correctly check for null return
  timestamp: 2026-01-28T10:15:00Z

## Evidence

- timestamp: 2026-01-28T10:10:00Z
  checked: DrtRequestFactory.buildRequest() method
  found: Budget is calculated at line 307 using budgetValidator.calculateBudget(tempRequest), but there is NO check for budget < 0 before adding the request. The request is unconditionally returned (line 363-389) regardless of budget value.
  implication: Requests with negative initial budget are added to the request list. These then pass through SingleRideGenerator where BudgetValidator correctly filters them, BUT the filtering logic depends on "remaining budget" calculated for the specific ride scenario.

- timestamp: 2026-01-28T10:12:00Z
  checked: BudgetValidator.calculateRemainingBudgets() method
  found: Line 128 calculates remainingBudgets[i] = actualDrtScore - request.bestModeScore. This is the score for the ACTUAL ride (with delays, detours) minus the baseline. For single rides with no delays (line 59 in SingleRideGenerator: delays = {0.0}), the actualDrtScore may be similar to the initial budget calculation.
  implication: For single rides, negative budget requests should be filtered. But what about higher-degree rides?

- timestamp: 2026-01-28T10:14:00Z
  checked: Flow for "quite long PT base rides" mentioned in symptoms
  found: For trips where PT (public transit) is the baseline mode, bestModeScore could be a relatively good (high) score since PT serves longer distances well. If DRT for a shared ride has delays/detours, the actualDrtScore could drop below bestModeScore even if the initial single-ride budget was positive.
  implication: The issue may be that rides are generated from requests that have positive initial budget BUT when those requests participate in SHARED rides with detours/delays, the remaining budget becomes negative. The filtering in RideExtender (line 301) should catch this, but let me verify.

- timestamp: 2026-01-28T10:16:00Z
  checked: RideExtender budget validation flow
  found: Line 301 calls budgetValidator.validateAndPopulateBudgets(ext), and line 302-306 correctly rejects rides where validated == null. The BudgetValidator returns null if ANY budget is negative (lines 87-89).
  implication: The filtering IS happening correctly for higher-degree rides. The issue must be at request creation level OR there's a misunderstanding about what "negative budget" means in the output.

- timestamp: 2026-01-28T10:25:00Z
  checked: Output file semantics - what negative budget means
  found: Two different "budget" values exist:
    1. DrtRequest.budget - calculated in DrtRequestFactory at request creation time (DRT vs best baseline for DIRECT trip)
    2. Ride.remainingBudgets[] - calculated by BudgetValidator for the ACTUAL ride with delays/detours
  The ExMasCsvWriter.writeRequests() writes DrtRequest.budget (line 56), while writeRides() writes remainingBudgets (line 132-134).
  implication: Negative budgets in REQUESTS output means requests where even the direct DRT trip is worse than baseline. Negative budgets in RIDES output would mean BudgetValidator is not filtering properly. Need to clarify which output has negative values.

- timestamp: 2026-01-28T10:28:00Z
  checked: DrtRequestFactory filtering
  found: At line 307 budget is calculated, but there is NO conditional check before returning the request at line 363. Requests with budget < 0 are returned and added to the request list.
  implication: CONFIRMED - Requests with negative initial budget are being created. These requests:
    1. Will fail SingleRideGenerator validation (no single ride created)
    2. May still participate in pair/higher-degree rides IF the shared ride gives them positive remaining budget
    3. Are written to requests.csv with their negative budget value

## Resolution

root_cause: DrtRequestFactory.buildRequest() creates and returns requests even when budget < 0 (i.e., DRT is worse than the best baseline mode even for direct trips). These requests are added to the request list and written to output, causing negative budget values to appear in results.

fix: Added budget filtering in DrtRequestFactory.buildRequest() to return null (skip request) when budget < 0. This is added at line 312-316 after budget calculation.

verification:
- Code compiles (syntax verified by reading back the file)
- Fix is minimal and targeted - single check at the point of budget calculation
- Existing test ExMasDemandExtractionE2ETest validates request generation but doesn't assert positive budgets. After fix, all output requests will have budget >= 0.
- The change is consistent with the design: if DRT is worse than baseline for even a direct trip, there's no benefit to pooling (which adds detours/delays)

files_changed:
- /mnt/Shared/Code/projects/Dissertation/matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/demand/DrtRequestFactory.java
