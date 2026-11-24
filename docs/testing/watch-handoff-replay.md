# Watch ↔ iPhone hand-off replay (#173)

Debug simulator builds accept `--watch-handoff-replay`. On launch the app:

1. Opens a fresh, isolated store in `Documents/WatchHandoffReplay/` and an isolated
   `WatchHandoffReplay` defaults suite. The user's database and settings are not touched.
2. Seeds a synthetic iPhone recording: one point a minute from 09:00 to 09:10 today
   at a fixed place, one iPhone visit, and the iPhone's coverage for that span. Then the
   phone "dies" (coverage closes).
3. Imports a synthetic Apple Watch batch through the real `WatchLocationSyncReceiver`
   merge: one point a minute from 09:00 to 09:20 and one stay ending at 09:20. Then it
   delivers the same batch again, the way a lost acknowledgement would.
4. Records a Watch report (the Watch is recording, because the phone isn't).

Open Listening → Apple Watch. **Recording on** reads "Apple Watch", and
**Hand-off replay** reads `merged 11 · kept 10 · points 21 · visits 1`:

- The 11 Watch points from 09:00 to 09:10 were already recorded by the iPhone, so they aren't stored again.
- The 10 Watch points from 09:11 to 09:20 fill the time after the phone died.
- The Watch stay is folded into the iPhone visit and fills its departure.
- The redelivered batch adds nothing.

The hook is compiled only for Debug simulator builds (`#if DEBUG && targetEnvironment(simulator)`).
The saved device test is `.argent/e2e/watch-handoff-replay.yaml`.

What it does not cover: real Watch↔iPhone proximity, WatchConnectivity lease delivery, and
battery. Those need a paired physical iPhone and Apple Watch.
