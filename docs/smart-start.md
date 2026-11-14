# Smart Start runtime slice (#60)

Smart Start is **off by default**. This implementation adds local persisted settings,
a motion-evidence module, a policy coordinator, and separate ambient monitoring.
It is not a completed or device-validated Smart Start feature. Settings now includes
an explicit-confirmation Smart Start page with permission statuses, activity/timing
choices and limitation/Shortcuts help. Optional onboarding, localized new copy and
physical acceptance remain incomplete. Do not advertise background reliability from
these injected tests or the simulator UI walkthrough.

## Required user-facing reliability copy

> iso.me can try to start tracking when iOS reports meaningful movement, but iOS may delay or suppress background triggers. Force-quitting the app prevents automatic relaunch until you open it again. Shortcuts automations are the most reliable way to start from CarPlay, workouts, Focus modes, Bluetooth, or NFC.

Native CarPlay entitlement/UI and HealthKit/workout background execution are not
MVP capabilities. Settings includes recipes using the retained Start/Stop shortcuts;
the Start intent now accepts an optional generic/CarPlay/workout Source label without
opting into Smart Start. Omission preserves the original simple action and active
sessions stay idempotent. Optional duration/profile parameters and actual
Shortcuts editor/automation/device acceptance remain future work. See the
[actual helper/manager verification](evidence/smart-start-shortcut-source-20261114.md).

## State and effects

- `smartStartSettings.isEnabled`: persisted explicit opt-in, initially false.
- `ambientMonitoringActive`: opted-in low-power visit/significant-change monitoring,
  requiring Always Location and current recording access. Arming does not start
  standard high-accuracy location updates or save route points/visits.
- `isTrackingEnabled`: full route recording. Manual tracking still owns its legacy
  visit/significant-change monitoring even with Smart Start off. Thus opting out
  during an active manual route does not turn off that route's monitors.
- Cached/asynchronously delivered location samples must have timestamps at or
  after the actual session start before distance ingestion, route sampling, received
  counts or persistence. Relaunch uses the restored real start, not launch time.
  Unknown session metadata admits no route points. A late visit cannot create a
  pre-session arrival; an in-session departure may close an already persisted open
  visit. Pre-session departures are ignored, and ordinary fresh arrivals remain valid.
- Stop After expiry work is owned by a scheduling generation and session start.
  Cancel/reschedule/stop invalidates the generation, including already-enqueued
  timer tasks. A current explicit session may expire; an old expiry cannot end a
  newer manual/shortcut session. Recovering an earlier session start reschedules
  its timer against that real start.
- Stopping an active route leaves opted-in ambient monitoring armed only with
  Always and current access. Opting out while idle disarms it. Permission/access
  downgrade cancels motion observation; late completions cannot start a route.
- Explicit/manual/shortcut sessions are never stopped by stationary inference.
  Policy auto-stop does not write `lastManualStopAt`; explicit Stop does. Stop After
  remains independent and retains its existing automatic-stop semantics.
- Daily distance statistics are not an auto-start input. Ambient location fixes do
  not enter route persistence or daily-distance ingestion in this runtime slice.

## Evidence and permission contract

Creating `ActivityDetectionManager` does not request Motion permission. Starting
its stream/query is allowed only after explicit persisted opt-in, Always Location
and current recording access. Motion unavailable/denied states are surfaced and
produce no automatic tracking. There is no attempt to request denied permissions.
A not-yet-determined Motion grant can be requested through the opted-in stream.
No new Location prompt is issued by the coordinator.

A live motion transition and a location wake only request recent CoreMotion
history. Neither starts tracking by itself. The adapter serializes queries and
rejects completions from canceled generations. A 30-second best-effort timer asks
for recent evidence while execution is available; it is not a background execution
guarantee or evidence of movement. Queries cover at most the latest 15 minutes.
Both movement and stationary thresholds must be finite, greater than zero, and
at most **900 seconds**, even if stationary stopping is currently off. The same
constant defines the real adapter query window and settings validation, so every
accepted threshold fits available evidence. `SmartStartStateStore.saveSettings`,
`AutoStartCoordinator.updateSettings`, and `LocationManager.updateSmartStartSettings`
throw on invalid input before persistence or observer changes; there is no silently
ignored/clamped update. The Settings intent model reloads actual state and reports
rejected/unapplied changes inline. Corrupt or legacy
unsupported persisted payloads load safe disabled defaults, not a shortened enabled
threshold. Cooldowns must be finite and nonnegative.

Normalized intervals use CoreMotion start times and the history query's covered
end time, not app uptime or time since the last callback. Only contiguous matching
high-confidence intervals contribute to duration. Unknown/ambiguous activity,
confidence changes and gaps break sustained movement. Unsorted/overlapping,
future, empty, incomplete or more-than-30-second-old evidence is rejected. Repeated
or older query results and clock reversal cannot cause a start. Future persisted
cooldown timestamps conservatively retain cooldown until wall time catches up.

All accepted evidence goes through `AutoStartPolicy` with current settings,
permissions, entitlement, active source and persisted cooldowns. The final
source-aware LocationManager start also gates opt-in/Always/access and is
idempotent. Logs contain decision/reason enum names only, no coordinates or
activity history. No motion history is persisted or sent off-device.

## Ownership and next wiring surface

`LocationManager` owns one coordinator; the coordinator holds its tracking target
weakly. Its motion callbacks capture weakly and carry a generation. Mode/threshold
changes, opt-out, permission downgrade, and shutdown invalidate pending completions.
The real adapter has one stream and one current-generation history query in flight.
An invalidated Apple query may still finish after stop/restart; its result is ignored. Replacing the
shared LocationManager permanently shuts down the prior manager's coordinator,
so its foreground/access observers cannot restart a competing motion stream.
CoreMotion does not offer query cancellation, so completion rejection is mandatory.

`AppTrackingServices` now owns the process's tracking graph. AppDelegate boots it
for foreground/background launches before returning; ContentView and Start intents
reuse it. The real store is attached before webhook/export services, and the launch
guard runs before manager construction. A failed store creates no manager or service
attachments; Start intents report the failure. A crash-loop pause suppresses the
automatic runtime for that process without deleting the user's opt-in. Simulator
regressions exercise this seam; actual OS background launch/wake delivery remains a
physical-device verification requirement, not a guarantee.

Next slice must:

1. Verify actual background launch/lifecycle diagnostics on a device, including
   callbacks before a scene exists, permission/access resolution and webhook/export
   recovery. Preserve existing Watch sync and its acceptance boundaries.
2. Finish acceptance of the new Settings page: localized copy, real denied/authorized
   permission states, complete motion/interaction audit, and optional onboarding.
   The page already calls `try updateSmartStartSettings`, reports validation/save
   errors, requires explicit enable confirmation, and exposes status/activity/timing
   choices. See [actual simulator scope and open requirements](evidence/smart-start-settings-20261114.md).
   Do not silently enable existing users or prompt Motion during ordinary onboarding.
3. Keep `LocationManager.startTracking` as explicit/manual intent and feed inferred
   activity/location wakes only through the coordinator. Do not bypass policy
   based on significant-change distance, visit departure, steps, or a timer.

## Verification still required on a physical iPhone

Injected policy/monitor tests establish deterministic gates, state transitions and
persistence exclusion, not actual CLLocation/CoreMotion delivery or battery cost.
All issue #60 phase 11 acceptance remains: fresh install; When In Use vs Always;
Precise off; Motion denied/unavailable; Low Power Mode; Background App Refresh off;
force-quit/reboot; foreground/background/locked Shortcuts; CarPlay/Bluetooth/Focus/
workout automations; sustained walking/driving/stationary and manual cooldown;
poor signal; Stop After, Live Activity, export, Webhook and watch regressions.
False positives (passenger rides, trains, buses, short walks) and battery profiles
need device measurement before release. No automatic tracking quality or physical
QA approval is implied by a passing simulator suite.
