# Android Perfetto Analysis

**Trace:** `native-profiler-20261111-183836.pftrace`  |  **Platform:** Android  |  **Analyzed:** 2026-11-11T18:38:57.760Z

---

## Summary

| Category | Count | Severity |
|---|---|---|
| CPU Hotspots | 2 | 🟡 2 |
| RSS Growth (weak signal) | 1 | 🟡 1 |

---

## CPU Hotspots

| # | Function | Thread | Weight (ms) | Weight % | Samples | During Hang? | Severity |
|---|---|---|---|---|---|---|---|
| 1 | `art::gc::collector::MarkCompact::MarkingPhase` | HeapTaskDaemon | 290 | 3.76% | 29 | — | 🟡 |
| 2 | `_ZN3art2gc9collector11MarkCompact18CompactMovingSpaceILin1EEEvPh` | HeapTaskDaemon | 240 | 3.11% | 24 | — | 🟡 |

### `art::gc::collector::MarkCompact::MarkingPhase` (HeapTaskDaemon)

**Call chains:**
- (29×) `art::gc::collector::MarkCompact::MarkingPhase`

**Activity bursts:** 7 clusters
- 0.4s → 1.1s (4 samples)
- 2.0s → 2.3s (2 samples)
- 3.0s → 3.6s (3 samples)
- 4.5s → 4.8s (2 samples)
- 5.3s → 5.8s (3 samples)
- 6.6s → 6.6s (1 samples)
- 7.1s → 9.7s (14 samples)


### `_ZN3art2gc9collector11MarkCompact18CompactMovingSpaceILin1EEEvPh` (HeapTaskDaemon)

**Call chains:**
- (24×) `_ZN3art2gc9collector11MarkCompact18CompactMovingSpaceILin1EEEvPh`

**Activity bursts:** 6 clusters
- 0.8s → 1.1s (2 samples)
- 1.9s → 3.0s (7 samples)
- 3.5s → 4.8s (7 samples)
- 6.5s → 7.6s (4 samples)
- 8.3s → 8.8s (3 samples)
- 9.9s → 9.9s (1 samples)


---

## RSS Growth — Weak Signal

> **Manual confirmation needed.** Resident-set size grew during the recording, but RSS growth is a weak proxy — it can be normal warm-up behaviour (JIT compilation, texture caches). Real Android leak detection lands in a later phase via heap-dump analysis.

| Start (MB) | Peak (MB) | Growth (MB) | Severity |
|---|---|---|---|
| 129.0 | 138.6 | 9.6 | 🟡 |

---

## Suggested Improvements

### CPU Hotspots

- 🟡 `art::gc::collector::MarkCompact::MarkingPhase` on HeapTaskDaemon (3.76%): High CPU in this function — inspect what calls it with `function_callers`, then move the heavy work off the main thread or memoize/throttle it.
- 🟡 `_ZN3art2gc9collector11MarkCompact18CompactMovingSpaceILin1EEEvPh` on HeapTaskDaemon (3.11%): High CPU in this function — inspect what calls it with `function_callers`, then move the heavy work off the main thread or memoize/throttle it.

---

## Next Steps

Ask the user which path to take:

1. **Investigate further** — use `profiler-stack-query` to drill into specific findings:
   - mode=`function_callers` function_name=`_ZN3art2gc9collector11MarkCompact12MarkingPhaseEv` — who calls this hot function
   - mode=`thread_breakdown` — CPU distribution across threads
2. **Implement fixes** — apply changes to address the findings above, then re-profile to measure improvement.
3. **Done for now** — save the report for reference.