# Media readers, offline translation and private full-text search

## Functional baseline: accepted Rounder readers and players

Use exact **assembled Rounder release source** and accepted on-phone functionality. Preserve native SAF/MediaStore and TeraBox progressive access, robust streaming/range cache, EPUB, PDF, CBZ/CBR, ZIP/image archives, TXT, documents/spreadsheets where implemented, GIF/animated image, video/audio player, 3D/model/Koikatsu-card preview and existing conversion/editing as genuinely supported. The UI is replaced, not the working behavior.

- Images/comics: **Pages** = one fitted discrete page changed by taps/swipes; **Vertical** = true continuous vertical reading; **Horizontal** = true continuous horizontal progression without snap. No generic "webtoon" substitute that breaks these. Correct natural filename/chapter ordering; pinch and double-tap return to fit; no blank/blinking old pages during progressive loading; zoom never smaller than the fitted image.
- Video: original aspect ratio shown fully by default, no forced crop, real seekbar, Play Next/Replay/Shuffle, usable landscape/portrait controls, orientation/lock, smooth stream resume, accurate audio/subtitle tracks and safe overlay/inset handling. Grid must adapt rather than automatically overcrowd narrow screens.
- EPUB/PDF/comics: respect real markup, image positioning, cover, bookmarks, chapter boundaries, progressive read of available pages while other ranges load; support large cloud originals, retries and correct text decoding without downloading the whole library.
- Documents: preserve actual CSV/XLSX editing and reader conversion functions where validated; never claim unsupported Office or 3D format support.
- Archive safety: do not blindly extract unknown archives into trusted roots; bound decompression, recursion, file count, path traversal, nested children and memory. Offer batch archive inspection/extraction in a separate verified job.
- Reading progress, bookmarks and source positions migrate and persist; independent per-account/source locators, stable content IDs, proper resume after process death.

## Translation: owner-updated contract

The historical `ASTERION_NEXUS_INTERNAL_TRANSLATION_CONTRACT_2026-11-11.md` is partially superseded:
- **Still binding**: Japanese, Korean, simplified/traditional Chinese -> English; quality-first contextual translation with original preservation; self-contained **phone-native offline runtime**; internal OCR for books/comics when needed; embedded/installed validated first-party translation/OCR implementation, no required external app/service, no cloud translator or Windows acceleration; per-format translated edition with exact order, original art and a genuine output.
- **Changed**: translation is automatically scheduled for **every verified eligible CJK book/comic**, not merely selected collections. OCR use is limited to books/comics (not arbitrary gallery-image OCR). **Full private indexing of all imported books is now explicitly required**, superseding the old `NO INDEX` restriction. **No optional PC execution path is accepted for translation**.
- English/unsupported non-CJK books are never mislabeled translated. If there is no installed supported language model/lexical resource, return a visible `UNSUPPORTED_LANGUAGE` or `ENGINE_UNAVAILABLE` and preserve the original.
- Eligible supported inputs include EPUB, PDF, CBZ, CBR, ZIP books/comics and TXT; other document formats only after a specific real adapter passes its acceptance. Do not degrade mixed-script/vertical writing/furigana/dialogue handling silently.
- The user selected **independent derived output items with optional links**; maintain original and translated edition as separate content items. Link by stable related-edition ID if useful, but no forced aggregate card behavior.

## Native translation execution pipeline

```
verified original registered
  -> format/header and source verification
  -> language detection / supported text sampling
  -> native text extraction OR native document/comic OCR
  -> reading order and chapter/page/balloon association
  -> contextual translation and terminology consistency
  -> QA: fidelity, names, omissions, chronology, layout, text overflow
  -> non-destructive EPUB/PDF/comic/TXT reflow or typesetting
  -> independent output bytes+format validation
  -> output registration and optional link
  -> index both original and translated text privately
```

Queue as durable jobs independent of downloads. Stable dedupe key: source content ID+content hash+language+model/runtime+translation contract version. Use bounded segments/context windows and terminology memory, not concatenate millions of source words into one LLM context. Prioritize reader/playback and active transfers over heavy translation; translate in background with pause/retry/cancel/thermal pressure gates. The queued original must be fully readable/verified; listing-only cloud placeholders are not valid.

First-party native translation and OCR engines, packaged model artifacts and runtime must meet the old strict self-contained implementation rule. No compiled "stub", empty fake results, unvalidated re-encoding or remote service fallback will count as translation.

## Full private book indexing (approved override)

All imported books, including untranslated, source and translated editions, are eligible for **full private on-device text indexing**. This is intentionally different from the older contract's no-index direction and must be implemented as a versioned migration with a privacy gate:

- Locally persisted encrypted-at-rest index where feasible with OS protected key storage, per-document identifiers, offsets, page/chapter mapping, language, edition, book hash, last-indexed version and complete/partial coverage.
- Index all actual accessible text; OCR pages where within approved book/comic scope and device budget. If part of a remote file is unreadable, status `PARTIAL_INDEX`, not `INDEXED_COMPLETE`.
- The index must not synchronize to an external public website, third-party cloud search provider, Control remote server or GitHub without explicit separate authorization. Private search results and snippets must honor the local user/security boundary.
- Efficient incremental reindex/recovery, schema upgrade and index disposal upon removal of content/owner privacy reset, with no stale text search after revocation. Do not delete book originals when deleting an index.
- Imported/source full text and Writing's owner-authored fiction can be searched locally; provenance, permission boundaries and publication states are separate. Writing remains the authoring engine and Nexus the canonical archive/reading authority.

## Acceptance gates

Use representative real files from each major format, including large cloud EPUB, comic with vertical CJK text, multi-chapter Japanese source, mixed CJK/English, unknown encoding, text-layer PDF, raster PDF, progressive archive read, seekable cloud video, GIF and supported Office formats. Demonstrate genuinely private full-text search with exact chapter offsets and NO egress during local search. Validate translated output readability/typesetting against original page structure and names. Confirm offline translation with all external networks disabled, and restart/resume interrupted jobs without duplicate output.
