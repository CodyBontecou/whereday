# Whereday Android design

The Android UI follows Whereday 2.0 ("Reimagined") on iOS: navy ink on warm paper, amber ink on night navy, Newsreader for titles, IBM Plex Mono for captions and figures. The iOS source of truth is `IsoMe/TEDesign.swift` and the views in `IsoMe/Views/`.

Everything lives in [`WheredayDesign.kt`](app/src/main/kotlin/com/bontecou/whereday/WheredayDesign.kt). `WheredayTheme` maps the tokens onto Material 3, so stock components already render in paper and ink. Use the Whereday components wherever the iOS view uses its TE equivalent.

## Tokens

| iOS `TE` | Android | Use |
| --- | --- | --- |
| `TE.surface` | `wd.surface` | page background |
| `TE.card` | `wd.card` | grouped controls, floating chrome |
| `TE.border` | `wd.border` | 1dp hairlines |
| `TE.textPrimary` / `textMuted` | `wd.textPrimary` / `wd.textMuted` | text |
| `TE.accent` | `wd.accent` | primary ink, selection, filled buttons |
| `TE.inkRust` | `wd.inkRust` | selected tab, destructive actions, warnings |
| `TE.inkCard` / `onInkCard` | `wd.inkCard` / `wd.onInkCard` | the inverted "you're at" card, toasts |
| `TE.liveDot` | `wd.liveDot` | live recording / current place |
| `TE.placeInks` | `wd.placeInks` | per-place colors on dials and maps |

## Type

| iOS | Android |
| --- | --- |
| `TE.editorial(.largeTitle)` | `WdType.largeTitle` / `typography.headlineLarge` |
| `TE.editorial(.title)` / `.title2` / `.title3` | `WdType.title` / `title2` / `title3` |
| `TE.mono(.caption2)` + tracking | `WdType.eyebrow` (uppercase the string) |
| `TE.mono(.caption)` | `WdType.caption` |
| headline figures | `WdType.numeral` |
| body text | `typography.bodyLarge` / `bodyMedium` (system sans, as on iOS) |

## Components

| iOS | Android |
| --- | --- |
| page header (mono date caption + serif title + round buttons) | `WdPageHeader(title, eyebrow=…) { WdCircleButton(…) }` |
| pushed page with back | `WdPage(title, onBack) { … }` or `WdTopBar` (never `TopAppBar`) |
| `TESectionHeader` / `TESectionFooter` | `WdSectionHeader` / `WdSectionFooter` |
| `TECard` + `TERow` | `WdCard { WdRow { … } }`, `WdNavRow`, `WdToggleRow` |
| `TEToggleStyle` | `Switch(colors = wdSwitchColors())` or `WdToggleRow` |
| `TEActionButtonStyle(.primary/.secondary/.destructive)` | `WdButton(emphasis = WdEmphasis.…)` |
| segmented `Picker` | `WdSegmented` |
| filter capsules | `WdChip` |
| inverted current-place card | `WdInkCard` + `WdLiveDot` |
| empty / loading states | `WdEmptyState`, `WdLoading` |
| info / warning callouts | `WdNotice(warning = …)` |
| stat figures | `WdStat` |
| text fields | `OutlinedTextField(colors = wdTextFieldColors())` |
| `FieldNotesBackground` / `FieldNotesArtwork` | same names |
| floating tab bar / rail | `WdFloatingTabBar` / `WdNavigationRail` |

## Layout rules

- Pages are paper (`wd.surface`). Content groups sit in hairline cards with 10dp corners, inset 16–20dp from the edges.
- Primary actions are full-width `WdButton`s pinned to the bottom of forms and flows. Secondary actions use paper buttons.
- Titles are serif. Labels, captions, timestamps and figures are mono. Section headers are uppercase mono with wide tracking.
- Every state renders inside the same chrome, so loading, empty, error, disabled and busy states never fall back to bare Material defaults.
- Keep every `testTag`, semantics node, string resource and callback. The redesign changes presentation only.
