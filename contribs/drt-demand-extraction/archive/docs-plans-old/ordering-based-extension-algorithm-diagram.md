# Ordering-Based Extension Algorithm — Diagram

## Algorithm Overview (per degree D → D+1)

```mermaid
flowchart TD
    subgraph INPUT["Input"]
        DR["Degree-D rides<br/>(1 per request set, from previous degree)"]
    end

    DR --> ENUM["Extract unique base sets<br/>(D request indices each)"]

    ENUM --> NEIGHBOR["For each base set:<br/>findCommonNeighborsSorted()<br/>→ candidate new requests"]

    NEIGHBOR --> NEWSET["Form degree-(D+1) candidate set<br/>[base indices + new request], sorted"]

    NEWSET --> DEDUP{"processedSetHashes<br/>.add(hash)?"}
    DEDUP -- "already seen" --> SKIP["Skip (dedup)"]
    DEDUP -- "new set" --> PAIRS

    subgraph CONSTRAINT_EXTRACTION["1. OrderingEnumerator.enumerate()"]
        PAIRS["Extract pairwise constraints<br/>Query graph for all C(D+1,2) pairs<br/>in BOTH directions"]
        PAIRS --> PAIRCHECK{"Any pair<br/>disconnected?"}
        PAIRCHECK -- "yes" --> INFEASIBLE["Skip (infeasible set)"]
        PAIRCHECK -- "no" --> PAIRINFO["PairInfo[] array:<br/>per pair: fwd/rev FIFO, fwd/rev LIFO"]
    end

    subgraph ORDERING_ENUM["2. Ordering Enumeration"]
        PAIRINFO --> ORIG_DAG["Build origin constraint DAG:<br/>• forwardOnly(A,B) → A before B<br/>• reverseOnly(A,B) → B before A<br/>• bidirectional → no edge"]
        ORIG_DAG --> ORIG_TOPO["Enumerate all topological sorts<br/>of origin DAG<br/>(backtracking search)"]
        ORIG_TOPO --> FOR_ORIG["For each origin ordering:"]
        FOR_ORIG --> DEST_DAG["Build dest constraint DAG:<br/>Origin order fixes pair direction →<br/>• FIFO only → same-direction dest edge<br/>• LIFO only → reverse-direction dest edge<br/>• both FIFO+LIFO → no edge"]
        DEST_DAG --> DEST_CHECK{"Direction has<br/>pair rides?"}
        DEST_CHECK -- "no rides for<br/>this direction" --> INVALID_ORIG["Skip origin ordering<br/>(invalid for this pair)"]
        DEST_CHECK -- "yes" --> DEST_TOPO["Enumerate all topological sorts<br/>of destination DAG"]
    end

    subgraph EVALUATION["3. Ride Evaluation (per ordering)"]
        DEST_TOPO --> ORDERING["Resolve int[] perms → DrtRequest[]<br/>requests = originsOrdered"]
        ORDERING --> SEQUENCE["Build stop sequence:<br/>[O₁, O₂, ..., Oₙ, D₁, D₂, ..., Dₙ]"]
        SEQUENCE --> ROUTE["Route all 2n−1 segments<br/>cumulative departure time<br/>(all cache hits from pair rides)"]
        ROUTE --> ROUTE_CHECK{"All segments<br/>reachable?"}
        ROUTE_CHECK -- "no" --> SKIP_ORD["Skip ordering"]
        ROUTE_CHECK -- "yes" --> METRICS["Calculate per-passenger metrics:<br/>travel time, distance, delay"]
        METRICS --> BUDGET{"Per-passenger<br/>maxTravelTime<br/>satisfied?"}
        BUDGET -- "no" --> SKIP_ORD
        BUDGET -- "yes" --> DELAY["Optimize delays<br/>(LP feasibility)"]
        DELAY --> DELAY_CHECK{"Delay<br/>feasible?"}
        DELAY_CHECK -- "no" --> SKIP_ORD
        DELAY_CHECK -- "yes" --> VALIDATE["BudgetValidator:<br/>check utility budgets"]
        VALIDATE --> BUDGET_CHECK{"Budget<br/>feasible?"}
        BUDGET_CHECK -- "no" --> SKIP_ORD
        BUDGET_CHECK -- "yes" --> OBJECTIVE["Compute objective value"]
    end

    subgraph SELECTION["4. Best Ride Selection"]
        OBJECTIVE --> COMPARE{"Better than<br/>current best<br/>for this set?"}
        COMPARE -- "yes" --> UPDATE["Update bestRide"]
        COMPARE -- "no" --> NEXT["Try next ordering"]
        UPDATE --> NEXT
    end

    NEXT --> |"more orderings"| ORDERING
    NEXT --> |"all orderings tried"| STORE

    STORE{"bestRide<br/>found?"} -- "yes" --> RESULT["Store in resultBySetHash"]
    STORE -- "no" --> NEXTSET["Next candidate set"]
    RESULT --> NEXTSET

    NEXTSET --> |"more candidates"| NEWSET
    NEXTSET --> |"all done"| OUTPUT["Output: List<Ride><br/>(1 per feasible set)"]

    style INPUT fill:#e1f5fe
    style CONSTRAINT_EXTRACTION fill:#fff3e0
    style ORDERING_ENUM fill:#f3e5f5
    style EVALUATION fill:#e8f5e9
    style SELECTION fill:#fce4ec
```

## Pair Ride Semantics

```mermaid
flowchart LR
    subgraph PAIR_RIDES["What pair rides encode"]
        direction TB
        FIFO_AB["pair(A,B) FIFO:<br/>O_A → O_B → D_A → D_B<br/>A picked up first, A dropped off first"]
        LIFO_AB["pair(A,B) LIFO:<br/>O_A → O_B → D_B → D_A<br/>A picked up first, B dropped off first"]
        FIFO_BA["pair(B,A) FIFO:<br/>O_B → O_A → D_B → D_A<br/>B picked up first, B dropped off first"]
        LIFO_BA["pair(B,A) LIFO:<br/>O_B → O_A → D_A → D_B<br/>B picked up first, A dropped off first"]
    end

    subgraph CONSTRAINTS["Constraints derived"]
        direction TB
        ORIG_C["Origin order:<br/>pair direction →<br/>who is picked up first"]
        DEST_C["Destination order:<br/>FIFO/LIFO kind →<br/>who is dropped off first"]
    end

    FIFO_AB --> ORIG_C
    LIFO_AB --> ORIG_C
    FIFO_BA --> ORIG_C
    LIFO_BA --> ORIG_C
    FIFO_AB --> DEST_C
    LIFO_AB --> DEST_C
    FIFO_BA --> DEST_C
    LIFO_BA --> DEST_C

    style PAIR_RIDES fill:#fff3e0
    style CONSTRAINTS fill:#e8f5e9
```

## Old vs New Algorithm Comparison

```mermaid
flowchart LR
    subgraph OLD["Old: Decomposition-Based"]
        direction TB
        O1["For each degree-D ride"] --> O2["Find common neighbors"]
        O2 --> O3["For each candidate set:<br/>Try D+1 decompositions"]
        O3 --> O4["For each decomposition:<br/>Look up base rides"]
        O4 --> O5["For each base ride:<br/>Try all 2^D FIFO/LIFO combos"]
        O5 --> O6["tryExtend():<br/>compute insertion position"]
        O6 --> O7{"Valid?"}
        O7 -- "many nulls" --> O8["Wasted work"]
        O7 -- "valid" --> O9["Keep ride"]
    end

    subgraph NEW["New: Ordering-Based"]
        direction TB
        N1["For each degree-D ride"] --> N2["Find common neighbors"]
        N2 --> N3["For each candidate set:<br/>OrderingEnumerator.enumerate()"]
        N3 --> N4["Build origin DAG<br/>→ topological sorts"]
        N4 --> N5["Per origin ordering:<br/>Build dest DAG<br/>→ topological sorts"]
        N5 --> N6["Route with cumulative time<br/>(all cache hits) + validate"]
        N6 --> N7["Keep best ride"]
    end

    style OLD fill:#ffebee
    style NEW fill:#e8f5e9
```

## Topological Sort Enumeration (Backtracking)

```mermaid
flowchart TD
    START["depth = 0, all nodes available"] --> LOOP["For each candidate node c"]
    LOOP --> CHECK{"All predecessors<br/>of c already placed?"}
    CHECK -- "no" --> LOOP
    CHECK -- "yes" --> PLACE["Place c at position depth"]
    PLACE --> RECURSE{"depth+1 == n?"}
    RECURSE -- "yes" --> EMIT["Emit permutation<br/>(valid topological sort)"]
    RECURSE -- "no" --> DEEPER["Recurse: depth+1"]
    DEEPER --> LOOP
    EMIT --> BACKTRACK["Backtrack: unplace c"]
    BACKTRACK --> LOOP

    style START fill:#e1f5fe
    style EMIT fill:#e8f5e9
```

## Concrete Example: Degree 3 (Requests A, B, C)

```
Shareability graph edges:
  pair(A,B) FIFO ✓  LIFO ✓     → bidirectional origin, no dest constraint
  pair(B,C) FIFO ✓  LIFO ✗     → B before C (origin), D_B before D_C (dest)
  pair(C,A) FIFO ✗  LIFO ✓     → C before A (origin), D_A before D_C (dest)
  (reverse directions: none)

Origin DAG:
  B → C (forwardOnly)
  C → A (forwardOnly)
  A↔B (bidirectional, no constraint)

Valid origin orderings (topological sorts):
  [B, C, A]  ← only valid sort

For origin [B, C, A]:
  pair(A,B): B before A → reverse direction → check reverse FIFO/LIFO
  pair(B,C): B before C → forward direction → FIFO only → D_B before D_C
  pair(C,A): C before A → forward direction → LIFO only → D_A before D_C

Dest DAG:
  B → C  (from pair(B,C) FIFO)
  A → C  (from pair(C,A) LIFO: D_A before D_C... wait, C first, LIFO → D_A before D_C)

Valid dest orderings: depends on A↔B constraint from reverse pair rides

Stop sequence: [O_B, O_C, O_A, D_?, D_?, D_?]
  → Route all 5 segments (cache hits) → validate → keep best
```
