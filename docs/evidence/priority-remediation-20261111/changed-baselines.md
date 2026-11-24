# Changed baseline writes — November 11, 2026

Review previously wrote every scratch baseline, including null-to-null values for new records. The native regression first failed because it observed both the existing visit and an unchanged new point written. Review now persists only hashes that differ, while still counting every existing identity and clearing stale non-null hashes when a local match disappears.

The regression then passed together with all 35 native import, Google planning, and resource-bound/control cases. All 149 platform unit tests passed. Both review axes found no concrete defect. Checksum verification, read snapshots, commit transactions, bounded pages, cancellation checks and the original million-record assertions/time budgets remain unchanged.

Perfetto was started, stopped and analyzed for the existing 10,000-point control before and after. It showed dispersed dispatcher/database/GC activity and did not identify managed Kotlin hotspots. The controls completed in 11,561 ms before and 13,892 ms after: no measured speed improvement is claimed. The deterministic benefit established here is avoiding unchanged baseline writes; the full-size gates must determine whether this materially helps performance. Reports and trace hashes are retained beside this note. RSS growth is not a confirmed leak.
