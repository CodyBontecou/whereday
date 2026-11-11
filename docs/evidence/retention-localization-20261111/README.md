# Retention localization acceptance

Status: the repository owner accepted all current localizations with the
instruction “Accept all localizations.” Focused subscription visual checks are
complete. Spoken TalkBack verification remains open.
Broader Android acceptance is tracked in [issue #115](https://github.com/CodyBontecou/whereday/issues/115).
The automated checks supplement that acceptance.

`review-pack.json` contains the exact 51 English resource strings used by this
flow and their ten translation inputs at PR #113 head `980cabb2`. Input file
hashes identify the accepted copy. `owner-localization-acceptance.json` pins all
1,779 English inputs and ten translation packs to that owner acceptance. This
records the user’s decision and does not claim native-fluency review or TalkBack
verification. The screenshots document the focused runtime checks performed.

## Review context

| State | Meaning that the translated page must preserve |
| --- | --- |
| Renewing subscription | Google Play manages renewal and supplies its date; opening the summary does not cancel anything. |
| Ending subscription | A fresh Play snapshot confirms renewal is off; access continues through the paid period. |
| Offline subscription | Previously verified access remains available; current renewal is unknown. Do not call it canceled. |
| Offline Lifetime | Cached Lifetime access does not establish whether a separate subscription renews. Keep Play management available. |
| Lifetime plus subscription | Lifetime does not cancel an independently renewing subscription. |
| Lapsed | Saved history stays on this phone. Recording and paid exports require a plan; imports and previews remain free. |
| Trial | Mention a free week only when Play lists an eligible trial offer for this account. |

## Required evidence

- Copy acceptance is complete by the owner’s explicit instruction. Preserve the
  accepted input hashes; any later copy changes should identify their new scope.
- On the disposable mapQa build, inspect the real subscription page and summary
  in light/dark mode, Arabic RTL, and 1.8× text. Check wrapping, clipping,
  mirrored navigation, order of plan/status/date values and reachable actions.
- With TalkBack, traverse the page and summary, verify headings and action names,
  open/dismiss the summary, and confirm focus returns to the invoking control.
  Check continued scrolling and Back behavior at large text.
- Exercise Settings → plan card → subscription as well as the QA preview.
  The presentation fixture alone cannot prove Settings integration or Play behavior.

The mapQa-only `whereday-qa://subscription?state=MODE` preview supports
`renewing`, `ending`, `offline-subscription`, `offline-lifetime`,
`lifetime-renewing`, `lifetime`, and `lapsed`; `&dark=true` selects dark mode.
It never changes billing access. Use only the disposable QA package/emulator.
Avoid confirming actions in a real Google Play subscription during these checks.

## Current evidence

The final APK passes **13 native tests**: six `SubscriptionScreenTest` cases,
one real `SubscriptionSettingsIntegrationTest`, and six `WorkspaceDialogTest`
cases. The integration test opens Settings from the actual app, opens the plan
card and summary, returns through Back, and checks cached-access navigation
without changing real billing access. `native-ui-tests.json` preserves exact
cases, APK hashes, device identity and clean disposable-package removal.

Manual Argent checks on the dedicated API 35 emulator cover:

| Capture | Observed behavior |
| --- | --- |
| [Offline subscription](offline-subscription-en.png) | Neutral cached-access wording; Google Play management remains reachable. |
| [Offline Lifetime, dark](offline-lifetime-en-dark.png) | Does not claim nothing renews; management remains visible. |
| [English summary](retention-summary-en.png) | Factual consequences and equal-sized Keep/Continue actions; Keep dismisses the sheet. |
| [Arabic large text, top](retention-ar-large-top.png) | RTL text and icons wrap at 1.8× text in dark mode. System icons remain light. |
| [Arabic large text, actions](retention-ar-large-actions.png) | Scrolling reveals both stacked actions and the complete final explanation. |
| [After Back dismissal](renewing-ar-after-dismiss.png) | The subscription page returns with the owner's system-icon contrast intact. |
| [Spanish lapse, top](lapsed-es-large-top.png) | Saved-history and free-import/preview messages wrap at 1.8× text. |
| [Spanish lapse, actions](lapsed-es-large-actions.png) | See plans remains reachable; no free-week promise appears for this fixture. |

The visual pass found two issues: the standalone preview lacked the main app's
window setup, and the compact Material 3 sheet followed the OS theme even when
Whereday selected dark mode. The preview now matches the main Scaffold insets;
the sheet styles only its own window from `wd.dark`. Final captures use the
rebuilt APK, with file and APK hashes in `captures.json`.

The automation tree retained pre-scroll bounds during this pass. Taps used fresh
pre-scroll bounds; large-text sheet opening also exercised configuration-change
restoration. Post-scroll evidence is visual, and Back used the hardware action.
The original 1.0 font scale and empty app-locale override were restored, the
preview package was removed, and the task's QA services were scoped down before
native instrumentation resumed. All data in the captures is synthetic.

No real subscription was canceled. Native tests intercept the supplied Play URI;
manual checks do not establish actual Play cancellation or spoken TalkBack
behavior. All current localizations are accepted by the owner; spoken TalkBack
verification and the remaining runtime checks in #115 stay open.
