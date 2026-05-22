# Next-Session Prompt (2026-04-13 → next)

## TL;DR

The `ForbiddenPrefixIndex` experiment is implemented, tested, and benchmarked on Bavaria 10% shadow — and it **does not work**. Cursor overhead dominates CPU (3× slower at deg 5, ~30× at deg 6) and `prunedByForbidden = 0` in the parallel-paths phase. A decision from Christoph is pending on one of four options.

Start by reading:
1. `.project-memory/forbidden-prefix-index-2026-04-13.md` — the current state, diagnosis, options.
2. `docs/plans/2026-04-13-forbidden-prefix-index-session-log.md` — full benchmark data.
3. `docs/plans/2026-04-13-forbidden-prefix-index-design.md` — the design we implemented.
4. `docs/plans/2026-04-13-forbidden-prefix-index-plan.md` — the implementation plan.

## Current branch state

- Submodule `matsim-libs` is on `feature/exmas-degree-graph` at commit `0243955`.
- Parent `Dissertation` is on `master` at commit `afa42c0`.
- 13 commits of forbidden-prefix work in the submodule (see project memory file for SHAs).
- All unit tests pass (21 in `ForbiddenPrefix*`).
- `ExMasKelheimE2ETest` passes with expected 703/243/451/8/1 ride counts.
- Reset point: submodule `a495af2ef3b`, parent `3fdebc0`.

## Pending decision (four options)

Full context in `.project-memory/forbidden-prefix-index-2026-04-13.md`. Summary:

1. **Cap `maxKeyLength` at 3 or 4.** Cheap try. Matches SSF scope. Likely still slower than SSF due to per-lookup `IntArrayList.wrap` overhead vs SSF's bitmask-and-shift.
2. **Revert Phase 4 wiring only (Tasks 11-12).** Keep index populated as dead infrastructure. SSF stays as hot-path. Minimal change.
3. **Revert Phases 3-4 entirely.** Roll back Phase 3 (recording) and Phase 4 (wiring). Keep Phase 1 (data structures + tests) on branch. SSF is the answer. Document as a negative result. **Claude's recommendation.**
4. **Re-brainstorm.** A third approach — must keep per-place cost bounded (SSF-style) while capturing more pruning power than today.

## On resume, Claude should

1. Read the project memory file and session log.
2. Verify git state:
   ```bash
   git log --oneline -5
   cd matsim-libs && git log --oneline -5 && git branch --show-current
   ```
3. Ask Christoph which option to take, or proceed directly if the prior conversation ended with a clear choice.
4. If option 1 (cap maxKeyLength): modify `ForbiddenPrefixCursor.ForbiddenPrefixCursor(...)` constructor, rebuild, re-run Bavaria 10% shadow.
5. If option 2 (revert Phase 4): `git revert ab4883c 57462ed` in submodule.
6. If option 3 (revert Phases 3-4): reset branch to `cc4f96f` or `git revert` commits 5 through 12 + fix. Keep Phase 1 data structures.
7. If option 4 (re-brainstorm): start from `.project-memory/forbidden-prefix-index-2026-04-13.md` "Key insight for future work" section.

## What was learned

Push-based forbidden-set pruning has a fundamental scalability issue: per-place lookup cost grows combinatorially with the maximum recorded prefix length. Unbounded prefix length (the design's selling point) is exactly what makes the cost explode. `SubSetOrderingFeasibility`'s cap at quints (size 5) is not a limitation — it's the thing making it fast.

`IntArrayList.wrap(...)` per lookup is ~30 ns of allocation + ~50 ns of content-based hashCode over the prefix. SSF's `long` hash lookup + bitmask test is ~10 ns. At ~5 B lookups per deg-5 pass, the ~70 ns/lookup difference adds ~350 seconds of CPU — which matches the observed slowdown.

Any future "more powerful sub-set pruning" idea needs to stay within SSF's per-operation cost budget or offer a fundamentally different mechanism (e.g., offline preprocessing, one-shot set-level checks rather than per-candidate).

## Don't re-learn

- Don't record into both structures in parallel and assume cursor counters will tell you anything — `prunedByForbidden` was 0 because `subsetFeasibility` ran first in the filter chain. For independent validation of a future approach, benchmark WITHOUT the old path active.
- Don't store unified origin/dest sequences without tracking the victim passenger. The triangle inequality argument only transfers when the victim is in-vehicle across the entire recorded sub-sequence, and the cursor has no way to check that from stop encoding alone.
- Don't ship content-equality map keys that hash only on content without testing for collision resilience at realistic key counts. Our 5M-key target needed the `IntArrayList` content keys; a polynomial-hash `long` key would have silently merged entries.

## Open questions for Christoph

Only one: which of the four options above. Everything else is downstream of that answer.
