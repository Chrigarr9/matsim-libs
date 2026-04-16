# Scoring-Agnostic Pipeline Diagrams

## Scenario 1: Standard MATSim (no DMC)

### Current Pipeline (ScoringFunction)

```mermaid
graph TD
    subgraph CONFIG["planCalcScore Config"]
        PC[margUtilMoney=1.0<br/>margUtilTraveling=-6.0<br/>monetaryDistRate=-0.0002<br/>constant=0.109<br/>dailyUtilConst=-3.0]
    end

    subgraph REPLANNING["MATSim Replanning"]
        SMC[SubtourModeChoice<br/>RANDOM mode selection<br/>no utility estimation]
    end

    subgraph SCORING_CHANNEL["MATSim Scoring Channel"]
        CN[CharyparNagel<br/>ScoringFunction]
    end

    subgraph DEMAND_EXTRACTION["Demand Extraction - CURRENT"]
        MRC_R[ModeRoutingCache<br/>TripRouter.calcRoute]
        MRC_S[calculateTripScore<br/>ScoringFunctionFactory<br/>.createNewScoringFunction]
        MRC_C[calculateTripCost<br/>distance x monetaryDistRate<br/>+ constant ASC]
        FIX[Daily constant<br/>correction hack]
        BV[BudgetValidator<br/>ScoringFunctionFactory<br/>.createNewScoringFunction]
        FIX2[Daily constant<br/>correction hack]
    end

    PC -->|same params| SMC
    PC -->|same params| CN
    PC -->|same params| MRC_S
    PC -->|same params| MRC_C
    PC -->|same params| BV

    MRC_R --> MRC_S
    MRC_S --> FIX
    FIX -->|score| MA[ModeAttributes<br/>travelTime, distance, cost, score]
    MRC_C -->|cost| MA

    MA -->|bestModeScore| BV
    BV --> FIX2
    FIX2 -->|budget = drtScore - bestModeScore| BUDGET[Budget]

    style PC fill:#4a9,color:#fff
    style MRC_S fill:#e74,color:#fff
    style BV fill:#e74,color:#fff
    style FIX fill:#e95,color:#fff
    style FIX2 fill:#e95,color:#fff
```

**Result: CORRECT.** All components read from the same `planCalcScore` params. The daily constant hack is ugly but produces correct trip-level utilities. Score and cost both derive from the same source.

---

### After Refactoring (TripEstimator)

```mermaid
graph TD
    subgraph CONFIG["planCalcScore Config"]
        PC[margUtilMoney=1.0<br/>margUtilTraveling=-6.0<br/>monetaryDistRate=-0.0002<br/>constant=0.109<br/>dailyUtilConst=-3.0]
    end

    subgraph REPLANNING["MATSim Replanning"]
        SMC[SubtourModeChoice<br/>RANDOM mode selection]
    end

    subgraph SCORING_CHANNEL["MATSim Scoring Channel"]
        CN[CharyparNagel<br/>ScoringFunction]
    end

    subgraph DEMAND_EXTRACTION["Demand Extraction - NEW"]
        direction TB
        CHECK{TripEstimator<br/>bound?}
        NO_TE[No TripEstimator bound]
        CREATE[Create MATSimTrip-<br/>ScoringEstimator<br/>from planCalcScore]
        MRC_R[ModeRoutingCache<br/>TripRouter.calcRoute]
        MRC_TE[TripEstimator<br/>.estimateTrip]
        MRC_C[cost = distance x<br/>monetaryDistRate]
        BV_TE[BudgetValidator<br/>TripEstimator<br/>.estimateTrip]
    end

    PC -->|same params| SMC
    PC -->|same params| CN
    PC -->|same params| CREATE

    CHECK -->|null| NO_TE --> CREATE
    CREATE --> MRC_TE
    CREATE --> BV_TE

    PC -->|same params| MRC_C

    MRC_R --> MRC_TE
    MRC_TE -->|score - no daily const hack needed| MA[ModeAttributes<br/>travelTime, distance, cost, score]
    MRC_C -->|cost| MA

    MA -->|bestModeScore| BV_TE
    BV_TE -->|budget = drtScore - bestModeScore| BUDGET[Budget]

    style PC fill:#4a9,color:#fff
    style MRC_TE fill:#4a9,color:#fff
    style BV_TE fill:#4a9,color:#fff
    style CREATE fill:#48d,color:#fff
```

**Result: CORRECT.** `MATSimTripScoringEstimator` reads from `planCalcScore` — same params as before. No daily constant hack needed (TripEstimator excludes them by design). Identical budgets, cleaner code.

---

## Scenario 2: DMC + MATSimTripScoring

### Current Pipeline (ScoringFunction)

```mermaid
graph TD
    subgraph CONFIG["planCalcScore Config"]
        PC[margUtilMoney=1.0<br/>margUtilTraveling=-6.0<br/>monetaryDistRate=-0.0002<br/>constant=0.109]
    end

    subgraph REPLANNING["DMC Replanning"]
        DMC_TE[DMC TripEstimator =<br/>MATSimTripScoringEstimator]
        DMC_SEL[MultinomialLogit<br/>or MaximumUtility]
    end

    subgraph SCORING_CHANNEL["MATSim Scoring Channel"]
        CN[CharyparNagel<br/>ScoringFunction]
    end

    subgraph DEMAND_EXTRACTION["Demand Extraction - CURRENT"]
        MRC_S[calculateTripScore<br/>ScoringFunctionFactory]
        FIX[Daily constant<br/>correction hack]
        BV[BudgetValidator<br/>ScoringFunctionFactory]
        FIX2[Daily constant<br/>correction hack]
    end

    PC -->|same params| DMC_TE
    DMC_TE --> DMC_SEL
    PC -->|same params| CN
    PC -->|same params| MRC_S
    PC -->|same params| BV

    MRC_S --> FIX -->|score| MA[ModeAttributes]
    MA --> BV --> FIX2 --> BUDGET[Budget]

    style PC fill:#4a9,color:#fff
    style DMC_TE fill:#48d,color:#fff
    style MRC_S fill:#e74,color:#fff
    style BV fill:#e74,color:#fff
```

**Result: CORRECT but inconsistent.** DMC uses `MATSimTripScoringEstimator` (reads `planCalcScore`), CharyparNagel reads `planCalcScore`, demand extraction reads `planCalcScore`. All same params. But demand extraction goes through ScoringFunction (with hack) while DMC goes through TripEstimator (clean). Same result, different paths.

---

### After Refactoring (TripEstimator)

```mermaid
graph TD
    subgraph CONFIG["planCalcScore Config"]
        PC[margUtilMoney=1.0<br/>margUtilTraveling=-6.0<br/>monetaryDistRate=-0.0002<br/>constant=0.109]
    end

    subgraph REPLANNING["DMC Replanning"]
        DMC_TE[DMC TripEstimator =<br/>MATSimTripScoringEstimator]
        DMC_SEL[MultinomialLogit<br/>or MaximumUtility]
    end

    subgraph SCORING_CHANNEL["MATSim Scoring Channel"]
        CN[CharyparNagel<br/>ScoringFunction]
    end

    subgraph DEMAND_EXTRACTION["Demand Extraction - NEW"]
        direction TB
        CHECK{TripEstimator<br/>bound?}
        FOUND[DMC's MATSimTrip-<br/>ScoringEstimator<br/>picked up via Inject]
        MRC_TE[TripEstimator<br/>.estimateTrip]
        BV_TE[BudgetValidator<br/>TripEstimator<br/>.estimateTrip]
    end

    PC -->|same params| DMC_TE
    DMC_TE --> DMC_SEL
    PC -->|same params| CN

    CHECK -->|bound!| FOUND
    FOUND --> MRC_TE
    FOUND --> BV_TE

    MRC_TE -->|score| MA[ModeAttributes]
    MA --> BV_TE --> BUDGET[Budget]

    style PC fill:#4a9,color:#fff
    style DMC_TE fill:#48d,color:#fff
    style MRC_TE fill:#4a9,color:#fff
    style BV_TE fill:#4a9,color:#fff
    style FOUND fill:#48d,color:#fff

    linkStyle 4 stroke:#48d,stroke-width:3
    linkStyle 5 stroke:#48d,stroke-width:3
```

**Result: CORRECT and CONSISTENT.** Demand extraction now uses the EXACT SAME TripEstimator instance as DMC replanning. Same code path, same params, no hack. Budgets reflect exactly how DMC evaluates modes.

---

## Scenario 3: eqasim

### Current Pipeline (ScoringFunction) — BROKEN

```mermaid
graph TD
    subgraph EQASIM_CONFIG["eqasim Config"]
        EQ[Real betas:<br/>betaCost_car=-0.126<br/>betaTravelTime=-0.045<br/>betaWaiting=-0.03<br/>...]
    end

    subgraph PLANCALCSCORE["planCalcScore Config - DUMMY"]
        PC[margUtilMoney = 0 !!!<br/>margUtilTraveling = 0<br/>monetaryDistRate = 0<br/>constant = 0]
    end

    subgraph REPLANNING["DMC Replanning"]
        EQ_TE[EqasimUtility-<br/>Estimator]
        DMC_SEL[MultinomialLogit]
    end

    subgraph SCORING_CHANNEL["MATSim Scoring - DISABLED"]
        CN[CharyparNagel<br/>all params = 0<br/>all plans score ~0<br/>keep-last-selected]
    end

    subgraph DEMAND_EXTRACTION["Demand Extraction - CURRENT"]
        MRC_S[calculateTripScore<br/>ScoringFunctionFactory]
        MRC_C[calculateTripCost<br/>distance x 0 = 0]
        BV[BudgetValidator<br/>ScoringFunctionFactory]
        GARBAGE[All scores ~ 0<br/>All costs = 0<br/>GARBAGE BUDGETS]
    end

    EQ -->|real betas| EQ_TE --> DMC_SEL
    PC -->|dummy params| CN
    PC -->|dummy params| MRC_S
    PC -->|dummy params| MRC_C
    PC -->|dummy params| BV

    MRC_S -->|~0| GARBAGE
    MRC_C -->|0| GARBAGE
    BV -->|~0| GARBAGE

    style EQ fill:#48d,color:#fff
    style PC fill:#e74,color:#fff
    style CN fill:#888,color:#fff
    style MRC_S fill:#e74,color:#fff
    style MRC_C fill:#e74,color:#fff
    style BV fill:#e74,color:#fff
    style GARBAGE fill:#c00,color:#fff
```

**Result: BROKEN.** Demand extraction reads from `planCalcScore` (dummy: everything=0). eqasim's real betas are in a completely separate config that demand extraction never sees. All budgets are ~0 → no meaningful DRT demand.

The cost field is ALSO broken: `distance × 0 = 0`. No monetary cost information.

---

### After Refactoring (TripEstimator) — FIXED

```mermaid
graph TD
    subgraph EQASIM_CONFIG["eqasim Config"]
        EQ[Real betas:<br/>betaCost_car=-0.126<br/>betaTravelTime=-0.045<br/>betaWaiting=-0.03<br/>...]
    end

    subgraph PLANCALCSCORE["planCalcScore Config - DUMMY"]
        PC[margUtilMoney = 0<br/>monetaryDistRate = 0<br/>...]
    end

    subgraph REPLANNING["DMC Replanning"]
        EQ_TE[EqasimUtility-<br/>Estimator]
        DMC_SEL[MultinomialLogit]
    end

    subgraph DEMAND_EXTRACTION["Demand Extraction - NEW"]
        direction TB
        CHECK{TripEstimator<br/>bound?}
        FOUND[eqasim's Estimator<br/>picked up via Inject]
        MRC_TE[TripEstimator<br/>.estimateTrip]
        MRC_C[cost = distance x<br/>monetaryDistRate<br/>= 0 still wrong]
        BV_TE[BudgetValidator<br/>TripEstimator<br/>.estimateTrip]
    end

    EQ -->|real betas| EQ_TE --> DMC_SEL
    EQ -->|same real betas| FOUND

    CHECK -->|bound!| FOUND
    FOUND --> MRC_TE
    FOUND --> BV_TE

    MRC_TE -->|real utility| MA[ModeAttributes<br/>score = meaningful!<br/>cost = still 0]
    MRC_C -->|0| MA
    MA --> BV_TE --> BUDGET[Budget = meaningful!]

    PC -.->|dummy, unused for score| MRC_C

    style EQ fill:#48d,color:#fff
    style PC fill:#888,color:#fff
    style FOUND fill:#48d,color:#fff
    style MRC_TE fill:#4a9,color:#fff
    style BV_TE fill:#4a9,color:#fff
    style MRC_C fill:#e95,color:#fff
    style BUDGET fill:#4a9,color:#fff

    linkStyle 2 stroke:#48d,stroke-width:3
    linkStyle 3 stroke:#48d,stroke-width:3
```

**Result: SCORE is FIXED, COST is still broken.** The `score` now comes from eqasim's TripEstimator (real betas) → meaningful budgets. But `cost` still reads from `planCalcScore` dummy params → always 0.

**This is the residual cost problem:** You cannot extract the monetary component from a TripEstimator's utility output without knowing the estimator's internal pricing model. The TripEstimator returns a single utility number — it doesn't decompose it into "time part + money part + comfort part".

---

## The Cost Problem — Visual Summary

```mermaid
graph LR
    subgraph SCORE_PATH["Score Path (FIXED by refactoring)"]
        direction TB
        TE[TripEstimator] -->|utility| S[score field]
    end

    subgraph COST_PATH["Cost Path (STILL reads planCalcScore)"]
        direction TB
        SP[ScoringParameters<br/>.modeParams] -->|monetaryDistRate| C[cost field]
    end

    subgraph STANDARD["Standard MATSim"]
        S1[planCalcScore has real params]
        S1 --> TE1[score = correct]
        S1 --> C1[cost = correct]
    end

    subgraph EQASIM["eqasim"]
        E1[planCalcScore has DUMMY params]
        E2[eqasim config has REAL betas]
        E2 --> TE2[score = correct via TripEstimator]
        E1 --> C2[cost = 0 WRONG]
    end

    style TE1 fill:#4a9,color:#fff
    style C1 fill:#4a9,color:#fff
    style TE2 fill:#4a9,color:#fff
    style C2 fill:#e74,color:#fff
```

---

## Decision: Remove Cost Field from ModeAttributes

`ModeAttributes.cost` was analytics-only (CSV output), never used in budget calculation, and broken (included ASC as monetary cost). With the scoring-agnostic refactoring, the TripEstimator's `score` field captures ALL utility components including monetary costs. The `cost` field is removed entirely.

---

## Full Pipeline: Budget to Constraints (The Heart of the Methodology)

### Current — Reads planCalcScore Directly (Broken in eqasim)

```mermaid
graph TD
    subgraph BUDGET_CALC["Budget Calculation"]
        TE[TripEstimator<br/>scoring-agnostic]
        BUDGET[budget = drtScore - bestModeScore<br/>in utils - CORRECT]
    end

    subgraph CONSTRAINT_CONV["Budget → Constraints - CURRENT"]
        SP[ScoringParametersForPerson<br/>reads planCalcScore]
        B2D[budgetToMaxDetourTime<br/>budget / margUtilTravel]
        B2C[budgetToMaxCost<br/>baseFare + budget / margUtilMoney]
        B2W[budgetToMaxWaitTime<br/>budget / margUtilWait]
        B2WD[budgetToMaxWalkDist<br/>budget / margUtilWalk]
    end

    TE --> BUDGET
    SP --> B2D & B2C & B2W & B2WD

    subgraph EQASIM_PROBLEM["eqasim: planCalcScore = DUMMY"]
        DUMMY[margUtilMoney = 0<br/>margUtilTravel = 0<br/>All conversions BROKEN]
    end

    SP -.-> DUMMY

    style TE fill:#4a9,color:#fff
    style BUDGET fill:#4a9,color:#fff
    style SP fill:#e74,color:#fff
    style DUMMY fill:#c00,color:#fff
```

### After Refactoring — Perturbation-Based Extraction

```mermaid
graph TD
    subgraph BUDGET_CALC["Budget Calculation"]
        TE[TripEstimator<br/>scoring-agnostic]
        BUDGET[budget = drtScore - bestModeScore<br/>CORRECT in all scenarios]
    end

    subgraph RATE_SOURCE["ConstraintParameterProvider"]
        CPP[For each parameter:<br/>1. ExMasConfig override?<br/>2. planCalcScore value?<br/>3. CRASH or WARN]
    end

    subgraph CONSTRAINT_CONV["Budget → Constraints - NEW"]
        B2D[budgetToMaxDetourTime<br/>budget / margUtilTravel]
        B2C[budgetToMaxCost<br/>baseFare + budget / margUtilMoney]
        B2W[budgetToMaxWaitTime<br/>budget / margUtilWait]
        B2WD[budgetToMaxWalkDist<br/>budget / margUtilWalk]
    end

    TE --> BUDGET
    CPP --> B2D & B2C & B2W & B2WD

    subgraph MONEY["margUtilMoney Resolution"]
        CFG{"exmas.marginal-<br/>UtilityOfMoney<br/>set in config?"}
        YES_CFG[Use config value]
        NO_CFG{"planCalcScore<br/>value > 0?"}
        USE_PCS[Use planCalcScore value]
        FAIL["FAIL: TripEstimator active<br/>but margUtilMoney=0<br/>→ tell user to set config"]
    end

    CFG -->|yes| YES_CFG --> B2C
    CFG -->|no| NO_CFG
    NO_CFG -->|yes| USE_PCS --> B2C
    NO_CFG -->|no, and estimator active| FAIL

    style TE fill:#4a9,color:#fff
    style BUDGET fill:#4a9,color:#fff
    style CPP fill:#48d,color:#fff
    style B2D fill:#4a9,color:#fff
    style B2WD fill:#4a9,color:#fff
    style B2C fill:#4a9,color:#fff
    style B2W fill:#4a9,color:#fff
    style YES_CFG fill:#4a9,color:#fff
    style USE_PCS fill:#4a9,color:#fff
    style FAIL fill:#e74,color:#fff
```

### What Each Scenario Gets

```mermaid
graph LR
    subgraph STD["Standard MATSim"]
        S_B[Budget: correct] --> S_D[maxDetour: correct]
        S_B --> S_C[maxCost: correct]
        S_B --> S_W[maxWait: correct]
        S_B --> S_WD[maxWalk: correct]
    end

    subgraph DMC["DMC + MATSimTripScoring"]
        D_B[Budget: correct] --> D_D[maxDetour: correct]
        D_B --> D_C[maxCost: correct]
        D_B --> D_W[maxWait: correct]
        D_B --> D_WD[maxWalk: correct]
    end

    subgraph EQ["eqasim (set marginal overrides in exmas config)"]
        E_B[Budget: FIXED] --> E_D[maxDetour: FIXED via override]
        E_B --> E_C[maxCost: FIXED via override]
        E_B --> E_W[maxWait: FIXED via override]
        E_B --> E_WD[maxWalk: FIXED via override]
    end

    style S_B fill:#4a9,color:#fff
    style S_D fill:#4a9,color:#fff
    style S_C fill:#4a9,color:#fff
    style S_W fill:#4a9,color:#fff
    style S_WD fill:#4a9,color:#fff
    style D_B fill:#4a9,color:#fff
    style D_D fill:#4a9,color:#fff
    style D_C fill:#4a9,color:#fff
    style D_W fill:#4a9,color:#fff
    style D_WD fill:#4a9,color:#fff
    style E_B fill:#4a9,color:#fff
    style E_D fill:#4a9,color:#fff
    style E_C fill:#4a9,color:#fff
    style E_W fill:#4a9,color:#fff
    style E_WD fill:#4a9,color:#fff
```
