# Obsidian Markdown article verification

Reviewed October 26, 2026.

- Source-reviewed `ExportService.exportToMarkdown`: date headings, visit tables, optional columns, locale-based display times, escaping, and `.md` format metadata.
- Created `marketing/public/samples/synthetic-location-journal.md` by hand with two invented stops near zero on January 1, 2000. It is explicitly not a captured export.
- Copied the sample to `/private/tmp/isome-obsidian-demo/Location/2000-01-01-visits.md` and opened that folder as a separate vault in installed Obsidian 1.14.4.
- Opened the note in Reading view and observed both table rows, names, coordinates, date heading, and times. No plugins or cloud sync were configured; no existing notes were changed.
- Screenshot retained for review at `/private/tmp/isome-obsidian-render.png`.

## Limits

This verifies desktop rendering of the sample. It does not verify an export written by the iPhone app, a Files transfer, security-scoped access to a mobile vault, cloud sync, a scheduled run, or round-trip restoration. The broader `/integrations/obsidian/` concept remains deferred pending those checks. The article describes the limited manual workflow and states the boundary explicitly.

## Primary sources

- [Import Markdown files](https://obsidian.md/help/import/markdown)
- [How Obsidian stores data](https://obsidian.md/help/Files%2Band%2Bfolders/How%2BObsidian%2Bstores%2Bdata)
- [Sync notes across devices](https://obsidian.md/help/sync-notes)
