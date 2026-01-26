# DRT Demand Extraction - Planning Documents

This directory contains planning documents for future development of the drt-demand-extraction contrib.

## Current Phases

| Phase | Name | Status | Document |
|-------|------|--------|----------|
| Phase 8 | HyperPool Integration | **Planned** | [HYPERPOOL_INTEGRATION_PLAN.md](../docs/HYPERPOOL_INTEGRATION_PLAN.md) |

## Phase Overview

### Completed Phases (1-7)

The following phases have been completed as part of the initial ExMAS implementation:

- **Phase 1**: Core domain model (Ride, DrtRequest, TravelSegment)
- **Phase 2**: Single and pair ride generation
- **Phase 3**: Shareability graph construction
- **Phase 4**: Iterative ride extension
- **Phase 5**: Budget validation and scoring
- **Phase 6**: CSV output and statistics
- **Phase 7**: Post-processing (Shapley values, predecessors)

### Phase 8: HyperPool Integration (Planned)

**Goal**: Integrate stop-based ride-pooling (HyperPool algorithm) to enable shared pickup/dropoff points.

**Sub-phases**:
- **8.1**: Configuration & Domain Model Extensions (8 tasks)
- **8.2**: Stop Finding Algorithm (9 tasks)
- **8.3**: Stop-to-Stop Ride Generation (7 tasks)
- **8.4**: Budget Validation Extensions (5 tasks)
- **8.5**: Engine Integration - Stage 1 (4 tasks)
- **8.6**: Output & CSV Extensions (6 tasks)
- **8.7**: Hyper-Pooling Stage 2 (38 tasks across 8 sub-phases)

**Total**: 77 tasks

**Key Features**:
- Stop-based pooling with walking to shared pickup/dropoff points
- Per-person walk distance constraints from budget
- Multi-stop sequences for hyper-pooled rides (transit-like)
- MATSim walk router integration for accurate distance calculation

See [HYPERPOOL_INTEGRATION_PLAN.md](../docs/HYPERPOOL_INTEGRATION_PLAN.md) for full details.

## Design Decisions

Key design decisions for Phase 8 are documented in the plan:

| Decision | Choice |
|----------|--------|
| Output strategy | Both D2D and S2S variants |
| Single rides | Door-to-door only |
| Budget validation | Against best mode score |
| Walk distance | MATSim walk router |
| Stop format | MATSim Facilities XML |

## References

- [HyperPool Paper (arXiv)](https://arxiv.org/abs/2206.05940)
- [HyperPool Paper (Nature)](https://www.nature.com/articles/s44333-024-00006-4)
- [ExMAS Repository](https://github.com/RafalKucharskiPK/ExMAS)
