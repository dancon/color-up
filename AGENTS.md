# Color Up agent guide

Android tablet coloring game for ages 5–12, branded 涂个彩 · Color Up!.
The active gallery has 36 illustrated PNGs and 108 crayons. The user explicitly requested removal
of the former SVG artwork, failed candidates and old-gallery UI. Do not restore those assets.
See [README](README.md), [design reference](docs/design-reference.md),
[engineering decisions](docs/engineering-decisions.md), and [verification](docs/verification.md).

## Commands and skill

- Environment: `./scripts/doctor.sh`
- Full local gate: `./scripts/check.sh`
- Palette check: `python3 tools/import_palette.py --check`
- Illustration import/check: `python3 tools/import_illustrations.py` / `python3 tools/import_illustrations.py --self-test --check`
- Core tests: `./scripts/gradle.sh :coloring-core:test`
- Device tests: `./scripts/gradle.sh :app:connectedDebugAndroidTest`
- Install: `./scripts/gradle.sh :app:installDebug`

Use [create-coloring-artwork](.agents/skills/create-coloring-artwork/SKILL.md) when creating themed
coloring drawings or implementing them in the game. It accepts one theme or a set, preserves the
requested audience and constraints, and includes generation, real-region QA and project integration.
The canonical QA scripts and pinned dependencies live with that skill; project tools are wrappers.

## Artwork and identity

- `artwork/catalog.json` is the content manifest. `expectedCount` declares the intended count;
  new authorized themes can change it. Gallery/test counts must not assume exactly36 or nine/series.
- `ArtworkCatalog.pages` contains only active drawings. Old IDs in `artwork/retired-ids.json`
  are blocked from reuse; this registry contains no old art. Existing current IDs/geometry are frozen.
  Never remap stored strokes to changed art. Unknown/retired local save files stay recoverable.
- Visible art is the original generated PNG, copied byte-for-byte to assets/illustrated. Use the
  built-in image tool for generation and edits, not procedural shapes. Keep final prompts/hashes.
- Prefer 10–16 broad regions, allow simpler scenes; <=20 including background is mandatory.
  Check actual components and dimensions, AND inspect the original plus numbered masks for gaps.
  Never truncate excess regions or hide meaningful tiny parts just to pass. Revise the drawing.
- Derived EVEN_ODD contours only constrain input and pigment in 1000×1000 coordinates. Display
  original PNG ink with Multiply over white/pigment; never display extraction polygons as the art.
  Keep the native image cache bounded, downsample thumbnails, preserve current image SHA.
- Strict import validates all declared entries, identity, hashes, geometry and region cap before
  writes. Never hand-edit GeneratedIllustrations.kt. Palette generation is independent of art.
- 108 stable crayon IDs: 81 solids +27 gradients, 18 pages of six; pages1–9 have5+1, others4+2.
  Keep ordered colors, gradient snapshots, loop arrows,18 dots and no numeric page label/color wheel.

## Drawing and storage

- Pure Kotlin core owns geometry/session/undo/progress; Android owns UI/rendering/coverage/storage.
- One down locks its closed region. Ignore blank/outside starts and secondary fingers. Crayon and
  white eraser clip to the same region; cancellation/background/rotation settle the accepted stroke.
- Fixed UI width40/1000. Keep deterministic two-layer paper grain and gradient/glitter in shared
  UI/thumbnail/coverage/PNG rendering. Mutable ShaderBrush caches belong to each drawing thread.
- Coverage uses actual pigment at160×160, excluding foreground masks; any RGB channel<250 counts.
  >=55% completes a region; page progress is the mean. All regions first complete -> celebration;
  >=6 used colors gives3 stars, >=3 gives2. Eraser adds no color; history survives clear/erasing.
- Clear is one undoable action; latest100 undo steps,300 strokes/page,1024 points/stroke. Commit
  measurements stay ordered; live measurements at most once/120ms and one pending. Stale commits
  can add history but cannot regress displayed current progress. Export settles active strokes first.
- Keep opaque ARGB, safe unknown crayon IDs and 2–5 gradient stops when restoring valid paint.
- Application-scoped serial repository; saves outlive ViewModels. Write immutable UUID page files,
  commit/verify small AtomicFile index last, reuse unchanged pages. GC preserves every current or
  recoverable index reference, including unknown retired pages. A corrupt index/page requires full
  original backup before replacement; failed backup blocks writes. Preserve format-v1 recovery source.
- Retired selected IDs must not show a false continue action for a blank replacement; fall back to
  actual current work if available, otherwise the first active drawing without claiming prior visits.

## UI and verification

Centered square on lavender, compact six-tool/title group14dp above, bottom tray, outside hint
(9s/first stroke). Parent tips on every screen, controls>=48dp, portrait hint and responsive windows.
Hide system bars only while painting. No background sound. No network permission/ads/accounts/
analytics/IAP/storage permission; use system picker for export. Preserve authorized brand/audio.

Test real clipping, texture/gradient/PNG, every active region's reachability, async completion,
current-save recovery and non-destructive handling of retired files. Run relevant device tests;
JVM tests do not prove Android rendering. Record actual new results, not configured or old CI passes.
