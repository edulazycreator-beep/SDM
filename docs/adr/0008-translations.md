# ADR-0008: Translations through a dictionary at the display layer

Status: accepted (2026-11-18).

## Context
The app was written with English texts directly in Compose code (about 800 texts across 60 files,
many with values inside: "Added 3 download(s)"). Users asked for Indonesian. Moving every text to
`strings.xml` would touch every call site at once and risk regressions in a single change.

## Decision
- English stays the source text in code. `core/common` holds `I18n` and the table
  `resources/sdm-i18n/id.tsv` (English → Indonesian).
- Text is translated where it is shown: the design system's `Text` and `Icon` (content
  descriptions for screen readers), the app snackbar, notifications, toasts and the widget.
- Texts with values are matched as patterns (`{}` = value); values that are known words are
  translated too; texts joined with " · " are translated part by part. Unknown text (file names,
  page titles, links) is shown unchanged. Results are cached.
- Language: device language by default; Settings › Appearance › Language overrides it
  (System / English / Bahasa Indonesia) and switches instantly. Android 13+ also lists SDM in the
  system's per-app language settings (`locales_config.xml`).

## Consequences
- New UI text must be added to `id.tsv` (a unit test covers the mechanism; missing entries simply
  show English).
- Event-log lines stored in the database are translated only when they match an entry.
- Android resources (`values-in/strings.xml`) are still used for texts the system shows
  (widget picker, Quick Settings tile, share target).
