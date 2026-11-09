# Whereday November campaign review — 2026-11-04

**Do not schedule yet.** All 28 captions were corrected in Fastlane, saved and read back. All 28 rendered creatives were inspected (64 slideshow images and 18 videos, with first/middle/end video frames). Eight creatives still have visual or text work; exported audio also needs correction/approval. This report does not treat an editor save or render request as approval.

Campaign: https://app.usefastlane.ai/campaigns/plans/g5811pwmv7vj366xnwj7gk7p0h8hkcb7

## Accurate claim boundaries

Verified against released App Store version **1.8.3**, its English listing, and repository behavior. Whereday keeps detected visits and GPS routes the user chooses to record; a visit does not reconstruct a full path. Recording can start on iPhone or Apple Watch, with Watch routes synced back to iPhone. Supported exports include JSON, CSV, Markdown, GPX and KML, subject to the applicable purchase/plan. History is on device and no Whereday account is required. Avoid absolute claims about every turn, exact/comprehensive location capture, guaranteed automatic routes, or nothing ever leaving the device. Apple Maps, purchases and related system/network services qualify broad privacy claims.

New-user access requires the applicable Individual/Family plan or Lifetime purchase. Do not promise free-forever access or invent pricing. Eligible trials and legacy access differ. The campaign copy does not advertise an unverified price or offer.

## Post-by-post disposition

All captions now include an accurate Whereday CTA, their unique `web` shortlink, and three relevant hashtags. Planned replacement overlay text is in the manifest. It is not necessarily the current rendered text for held slideshows.

| Day | Format | Rendered visual/claims | Remaining action |
|---|---|---|---|
| 01 | slideshow | passed | None for visual/claims; hold campaign until remaining posts are corrected. |
| 02 | wall-of-text | passed | Export contains audible audio despite Studio Sound off; mute or deliberately review final soundtrack before approval. |
| 03 | green-screen | passed | Local speech detection found unrelated source dialogue; mute/replace and verify exported audio. |
| 04 | slideshow | passed | None for visual/claims; hold campaign until remaining posts are corrected. |
| 05 | wall-of-text | passed | Export contains audible audio despite Studio Sound off; mute or deliberately review final soundtrack before approval. |
| 06 | green-screen | passed | Export contains audible audio despite Studio Sound off; mute or deliberately review final soundtrack before approval. |
| 07 | slideshow | passed | None for visual/claims; hold campaign until remaining posts are corrected. |
| 08 | wall-of-text | passed | Export contains audible audio despite Studio Sound off; mute or deliberately review final soundtrack before approval. |
| 09 | green-screen | correction-required | Replace political reaction clip. Local speech detection found unrelated source dialogue; mute/replace and verify exported audio. |
| 10 | slideshow | passed | None for visual/claims; hold campaign until remaining posts are corrected. |
| 11 | wall-of-text | passed | Export contains audible audio despite Studio Sound off; mute or deliberately review final soundtrack before approval. |
| 12 | green-screen | passed | Export contains audible audio despite Studio Sound off; mute or deliberately review final soundtrack before approval. |
| 13 | slideshow | correction-required | Remove duplicated Whereday text boxes from slides. |
| 14 | wall-of-text | passed | Local speech detection found unrelated source dialogue; mute/replace and verify exported audio. |
| 15 | green-screen | correction-required | Replace clip with unrelated burned-in classroom/streamer subtitles. Export contains audible audio despite Studio Sound off; mute or deliberately review final soundtrack before approval. |
| 16 | slideshow | passed | None for visual/claims; hold campaign until remaining posts are corrected. |
| 17 | wall-of-text | passed | Export contains audible audio despite Studio Sound off; mute or deliberately review final soundtrack before approval. |
| 18 | green-screen | passed | Local speech detection found unrelated source dialogue; mute/replace and verify exported audio. |
| 19 | slideshow | correction-required | Finish all eight planned slides; rendered output still contains old completeness claims. |
| 20 | wall-of-text | passed | Export contains audible audio despite Studio Sound off; mute or deliberately review final soundtrack before approval. |
| 21 | green-screen | passed | Local speech detection found unrelated source dialogue; mute/replace and verify exported audio. |
| 22 | slideshow | correction-required | Apply planned six-slide rewrite; original overstatements still render. |
| 23 | wall-of-text | passed | Export contains audible audio despite Studio Sound off; mute or deliberately review final soundtrack before approval. |
| 24 | green-screen | passed | Export contains audible audio despite Studio Sound off; mute or deliberately review final soundtrack before approval. |
| 25 | slideshow | correction-required | Apply planned six-slide rewrite; original overstatements still render. |
| 26 | wall-of-text | passed | Export contains audible audio despite Studio Sound off; mute or deliberately review final soundtrack before approval. |
| 27 | green-screen | correction-required | Replace political reaction clip. Local speech detection found unrelated source dialogue; mute/replace and verify exported audio. |
| 28 | slideshow | correction-required | Apply planned seven-slide rewrite; original precision/completeness claims still render. |

## Audio finding and editing blocker

Local ffprobe/volume checks found audio tracks in all 18 video exports. Local Whisper tiny speech detection found clearly unrelated source dialogue in days 3, 9, 14, 18, 21 and 27; other clips include music or ambiguous detections. Automated transcripts are screening evidence, not authoritative transcriptions or final listening approval. Studio displayed **Sound off** for an inspected clip while its exported MP4 retained audible source audio. The safest intended correction is to mute source sound, rerender and verify the resulting export; alternatively deliberately choose/review a suitable soundtrack.

Fastlane repeatedly switched the active browser workspace from Whereday back to Health.md, including within seconds of opening a style drawer. Further mutations were paused after repeated interruptions to avoid applying edits to the wrong workspace. No changes were knowingly made to Health.md. Resolve the shared session/workspace switching before completing the held edits. A separate request to pause other Fastlane work is pending.

## Attribution shipped

A separate Cloudflare Worker is live only on `/v/*`, with 196 allowlisted links across 28 creatives and seven platforms. It follows Health.md's platform/angle/sequence convention, using **Whereday's own** app ID `6761960794` and verified provider token `126424970`. Each creative/platform maps to an exact Apple `ct` token. ASC's official campaign-link generator produced the same example URL. There are no Whereday custom product pages yet, so links use its default App Store page; Health.md's CPPs were not copied.

Eligible click requests are counted as daily aggregates in a separate D1 table. HEAD checks, known previews/prefetches and privacy opt-outs are excluded; privacy opt-outs also receive untagged App Store URLs. No app SDK, cookie, identifier, fingerprint or location upload was added. Incoming query parameters cannot alter the destination.

Production version: `282f95d4-0139-4cde-94f5-408b1e3035fb`. All **196** production HEAD redirects matched the expected app ID, provider token, campaign token and media type. Non-mutating GET checks covered a bio hub, opt-out redirect, preview bot and invalid path. **58 marketing tests passed**, including seven redirect tests; Worker bundle dry run passed. No iOS code changed.

A controlled production counter-write test was proposed but rejected by automatic approval review because it would inject a synthetic click into production and non-mutating alternatives were available. It was not executed. Local tests exercise the write path; actual live counting is not yet verified by a real eligible click. No synthetic campaign results were created.

## Scheduling prerequisites

1. Resolve Fastlane workspace switching, finish the eight held visual/text corrections and audio fixes, then inspect the new exports.
2. Connect the intended Whereday social accounts. They are not connected yet. The current MCP connector is scoped to Health.md, so Whereday work used the browser.
3. Replace each unscheduled caption's `web` link with the actual destination platform's link. Put that link in a clickable field where supported. For TikTok/Instagram, configure a per-post bio destination or use the numbered hub and tell viewers which entry to select. A shared generic bio alone cannot attribute a specific video.
4. Append actual published Fastlane post IDs/URLs and the final destination configuration to the reconciliation CSV at scheduling. Existing content IDs identify creatives, not published posts.
5. Reconcile aggregate clicks and Apple's identical `ct` campaign rows after genuine traffic. Apple applies first-time-download attribution rules, thresholds and reporting delay. An absent row means unavailable, not zero. Views, clicks, downloads and sales are distinct measures.

Nothing was scheduled or published. No end-to-end install or sales result is claimed before genuine traffic. See `marketing/campaign-redirects/README.md` for reporting/deployment commands and `campaign-links.csv` for the 196-link mapping.

Evidence: `live-link-checks.json`, `asc-generated-link.txt`, `reviewed-media-hashes.json`. Reviewed media and private ASC baseline/screenshot remain in `/tmp/whereday-campaign-final` and `/tmp/whereday-campaign-review`; temporary files are not durable storage. Private App Store financial data was excluded from the repository.

Apple campaign documentation: https://developer.apple.com/help/app-store-connect-analytics/acquisition/campaign-links
