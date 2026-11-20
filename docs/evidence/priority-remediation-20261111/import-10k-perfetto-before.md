# Android Perfetto Analysis

**Trace:** `native-profiler-20261111-183515.pftrace`  |  **Platform:** Android  |  **Analyzed:** 2026-11-11T18:35:34.476Z

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
| 1 | `art::gc::collector::MarkCompact::MarkingPhase` | HeapTaskDaemon | 330 | 4.78% | 33 | — | 🟡 |
| 2 | `art_jni_trampoline` | DefaultDispatch | 220 | 3.18% | 22 | — | 🟡 |

### `art::gc::collector::MarkCompact::MarkingPhase` (HeapTaskDaemon)

**Call chains:**
- (33×) `art::gc::collector::MarkCompact::MarkingPhase`

**Activity bursts:** 5 clusters
- 0.2s → 1.4s (6 samples)
- 2.4s → 3.6s (7 samples)
- 4.2s → 5.8s (11 samples)
- 6.6s → 6.9s (4 samples)
- 8.3s → 8.7s (5 samples)


### `art_jni_trampoline` (DefaultDispatch)

**Call chains:**
- (22×) `art_jni_trampoline`

**Activity bursts:** 5 clusters
- 0.1s → 0.2s (2 samples)
- 1.7s → 2.2s (3 samples)
- 4.0s → 4.0s (1 samples)
- 4.7s → 5.5s (7 samples)
- 7.2s → 7.9s (9 samples)


---

## RSS Growth — Weak Signal

> **Manual confirmation needed.** Resident-set size grew during the recording, but RSS growth is a weak proxy — it can be normal warm-up behaviour (JIT compilation, texture caches). Real Android leak detection lands in a later phase via heap-dump analysis.

| Start (MB) | Peak (MB) | Growth (MB) | Severity |
|---|---|---|---|
| 129.8 | 139.2 | 9.4 | 🟡 |

---

## Suggested Improvements

### CPU Hotspots

- 🟡 `art::gc::collector::MarkCompact::MarkingPhase` on HeapTaskDaemon (4.78%): High CPU in this function — inspect what calls it with `function_callers`, then move the heavy work off the main thread or memoize/throttle it.
- 🟡 `art_jni_trampoline` on DefaultDispatch (3.18%): High CPU in this function — inspect what calls it with `function_callers`, then move the heavy work off the main thread or memoize/throttle it.

---

## Next Steps

Ask the user which path to take:

1. **Investigate further** — use `profiler-stack-query` to drill into specific findings:
   - mode=`function_callers` function_name=`_ZN3art2gc9collector11MarkCompact12MarkingPhaseEv` — who calls this hot function
   - mode=`thread_breakdown` — CPU distribution across threads
2. **Implement fixes** — apply changes to address the findings above, then re-profile to measure improvement.
3. **Done for now** — save the report for reference.