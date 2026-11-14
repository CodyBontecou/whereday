# Android phone launch gates — November 3, 2026

A checklist of what still blocks a phone release candidate. Detail and evidence live in [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) and [PARITY_RECONCILIATION.md](PARITY_RECONCILIATION.md). Nothing here is declared complete by passing selected tests.

## 1. Current-iOS parity (target is the Whereday repository, iOS 2.0)

- [x] Paper/ink theme, bundled Newsreader/IBM Plex Mono, TE components (stage 1)
- [x] Today/Atlas/Places/Archive shell; Trace and Timeline over Today; Settings behind Archive / on the rail
- [x] Today day dial screen; Atlas year grid
- [ ] Re-freeze the iOS baseline against `/Users/codybontecou/dev/whereday` and point `observe_ios_sources.py` and fixture `--check-source` scripts there
- [x] Saved-place kinds (Home), notes, Merge, inline rename, place rhythm/detail, Room 12→13 migration, Home on Today (stage 2)
- [x] Places library restyle: SAVED/VISITED header, Most visited/Recent/Unnamed/A–Z, Home pinned, unsaved-spot suggestions and their detail/save, two-pane Places (stage 3)
- [ ] Draggable pin inside the place editor; delete from a library row (Android deletes from the editor)
- [x] Month recap card with share, Atlas two-pane selected-day card (stage 4)
- [ ] Redesigned country detail; Atlas add-stay from an empty day (opens Country days today); recap city line (needs network locality lookup)
- [x] Three-step onboarding with recording-detail presets, photo matching and import shortcut (stage 5)
- [ ] Archive "export as a sentence" builder, Obsidian/Dawarich destinations, month/year presets
- [ ] Route redesign (moving time, climb, speed chart, GPX); log-a-stop search/preview/overlap trimming
- [ ] Shortcuts guide (Automate) and "How Whereday listens"
- [ ] Per-screen restyle against the 45 artboards (map, timeline, visit, places, country days, settings, webhook, import, logs, what's new, lifetime offer)
- [ ] Ongoing-notification redesign around the day dial
- [ ] Owner decision: Android home-screen widgets (iOS 2.0 ships two)
- [ ] Earlier frozen-baseline workflow reconciliation items

## 2. Adaptive, restoration and accessibility

- [ ] Today/Places/Atlas/Settings two-pane and cover layouts (iOS Duo)
- [ ] Broader process-death (inferred gallery/replay actual process kill still open), navigation, clear-data, live-window/hinge, keyboard
- [ ] Re-run `WorkspaceProcessDeathTest` on the new shell and place detail (its driver needs an owned private ADB server; not started while the owner's Pixel and another session's emulator were attached)
- [ ] Intermittent visit discard-after-restoration timeout — unresolved, not reproduced in isolation
- [ ] TalkBack, RTL, large text, motion, dark/light across the new shell
- [ ] Localization: Spanish is a draft; nine further iOS locales absent

## 3. Production services (need owner inputs)

- [ ] Geoapify production account, keys and quota for place search and routing; live consent/attribution/failure acceptance
- [ ] Production map tiles provider configuration
- [ ] Play Console lifetime product, Cloud project/service account, verifier deployment, RTDN topic; sandbox checkout, restore, pending, refund/revocation and every paid path
- [ ] Production webhook receiver acceptance; analytics receiver deployment, retention and privacy acceptance (default-off opt-in)

## 4. Physical-device acceptance

- [ ] Real calendar app Save/Cancel; installed Automate walkthrough
- [ ] Labeled field traces for recording and Visits only; Doze, battery, OEM, reboot, permission changes on Pixel plus a second OEM
- [ ] Photo full/partial grants, large library; release performance and storage budgets

## 5. Release

- [ ] Privacy/legal and dependency review (font notices to the in-app reader)
- [ ] Protected signing environment, remote CI acceptance
- [ ] Play declarations (background location, foreground service, photos), Data Safety, listing, staged field beta

## Owner request (bundled)

1. Geoapify production API key(s) and the quota/billing tier, stored in the protected build environment (not the repository).
2. Map tile provider choice for production (OpenFreeMap opt-in today) and any account.
3. Play Console access to create the lifetime product (price confirmed at $49.99), a Cloud service account for the Developer API, and approval to deploy the verifier.
4. Upload-key custody for protected signing and permission to run the remote release workflow.
5. A webhook receiver endpoint for production acceptance; analytics receiver deployment approval.
6. Physical-device time: Pixel 7 (owner device — QA only on the separate `mapQa` package) plus a second-OEM phone and a foldable for hinge acceptance; an installed Automate app.
7. Scope decision: whether Android ships home-screen widgets at launch to match iOS 2.0.
