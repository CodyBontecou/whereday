# Automatic export and Automation plan-access messages

Android Map QA, assigned emulator-5554, November 8, 2026. No purchases, export delivery, or automation grants performed.

The Automatic exports policy said “Verified lifetime export access is required”; Automation’s Create export files scope said Lifetime access was also required. Archive correctly advertises Weekly, Yearly and Lifetime. BillingSession.requireAccess admits a verified grant for any configured plan. ScheduledExportCoordinator authorizes Capability.scheduledExport through purchases.requireAccess; Automation uses Capability.fileExport. The Lifetime-only descriptions were incorrect.

Changed the policy, purchase-failure message and Automation scope description to verified Whereday plan access, with reviewed Spanish translations and regenerated resources. Authorization behavior is unchanged.

Raw live recordings are preserved in ignored session state. `herdr-broad-sweep-one-20261108` raw step82 and `herdr-automation-access-20261108` raw step7 passed against the old labels. Those findings reset the broad clean-sweep count to zero.

Validation: localization generator and `--check` pass for 1,680 resources and one locale; `git diff --check` passes. `:app:lintDebug :app:assembleMapQa` passed (exit 0, 23s; [build log](build-verification.txt)). Updated only mapQa with `adb install -r`. Both corrected screen labels passed direct live checks. `herdr-plan-access-copy-20261108.yaml` then passed two consecutive unchanged full27-step replays in25.0s and25.8s, with pass1 after scoped service refresh. Replay logs are alongside this report. The failure-message resource shares the corrected wording but its failure branch was not triggered; no delivery or schedule was started.
