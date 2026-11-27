# Additional Nexus-native intelligent library and media operations

The owner explicitly approved a wider platform **beyond** three-way merger. These capabilities are expected but **follow integration of real existing engines and the first source-to-library vertical slice**. Each operation must consume Nexus registry identities, produce independent evidence receipts and use conservative permissions.

## Visual semantic search using Fusion

- Search by text concepts, visual exemplars, recognized properties, series/character tags, image composition and similarity grounded in real visual embeddings or canonical Fusion evidence.
- Distinguish exact metadata/tag matches, learned semantic similarity and user preference relevance. Expose explanation/uncertainty when useful; never insert made-up labels from free-form prompts into canonical Fusion.
- Index images/videos still frames only when owner resource policy allows and files are actually readable; track model/checksum/index version, source provenance and per-subject vs image-wide features.
- Support filters: media type, date, source site/account, creator, character, verified file existence, book/series/project, quality/content rating, tag family, original vs derivative.
- Search across local, TeraBox and enabled web providers **simultaneously**, but never send local embeddings, private filenames or full-text book excerpts to a remote provider by default.
- For large libraries 300k+ images, use incremental vector/index updates, bounded hot cache, streaming pagination and invalidation on file/hash/taxonomy/model change.

## Smart collections and media relationships

- Auto-propose and optionally maintain collections by series, creator, character/version, gallery, episode/season, download source, genre/format, artwork style, source project, duplicates and related assets.
- Keep stable memberships and explain **why** an item belongs; owner edits/negative signals take precedence over automatic inference.
- Relationships represent evidence-backed edges between independent file items or specialist project IDs; optional linkage, not mandatory grouping or filename reorganization.
- Album/series follow-and-backfill dashboard: unified subscriptions across all supported creators and providers with independent Sync and Backfill states, last-success timestamp, cursors, missing episodes/album originals and actual failure reasons.
- Never automatically download from newly inferred subscriptions without an explicit owner rule.

## Recommendations grounded in private library

- Recommend existing content or next additions based on owner ratings, actual tags and verified relationships, not manufactured titles or arbitrary internet promotional content.
- Keep recommendations local by default; never upload private library embeddings, reading history, private story text or cloud account identity to an external recommendation service.
- Let users remove, refine or disable recommendations and retract preference signals; provide meaningful provenance and confidence limits.
- Recommendations may lead to a real federated online query only after the owner chooses the external scope and site/session.

## File repair, corruption detection and recovery

1. Validate actual file length, header/MIME, archive directory structure, metadata, decoder integrity and checksum when a trusted expected value exists.
2. Classify `READABLE|UNVERIFIED|PARTIAL|CORRUPT|MISSING|REPAIRABLE_FROM_VERIFIED_COPY|UNRECOVERABLE`; avoid "healthy" based on filename or checksum alone.
3. Prefer non-destructive recovery from exact verified byte-identical cloud/local copy, with explicit account/session and full final hash checks.
4. Where recovery is heuristic (truncated JPEG/MP4/MKV/index repair), write a **separate recovered candidate**, disclose lost ranges/unknown content, compare decoder results and do not silently overwrite original.
5. Keep original, repair journal and owner-visible undo/restore; irreversible cleanup needs confirmation.
6. Test interrupted write, corrupted zip central directory, bad headers, partially fetched TeraBox range, stale cloud metadata, duplicate file names and wrong-source checksum.

## Archive inspection and batch extraction

- Detect archive type from verified magic/header and appropriate decoder; handle ZIP/CBZ/CBR/RAR/7z/tar/gz when a real bundled supported implementation is available, not by renaming formats.
- Bound recursion, ratio, total expanded bytes, file count, names/encoding, nested-depth, symlinks and path traversal; never execute archives or imported binaries.
- Preview real contents, metadata, folder tree, target path, required storage and risk before batch extraction. Deduplicate exact objects reversibly and register extracted objects with provenance.
- Unsupported codec/encrypted archive/unavailable password must be reported, not simulated as empty.
- Independent persistent queue, pause/resume/cancel and exact verified output identities.

## Advanced audio/video technical analysis

- Per media: codec/container/profile, stream list, frame rate, timebase, orientation, dimensions, color/HDR metadata, bitrate, duration, keyframes, chapters, audio channels/language, subtitle tracks and loudness where supported.
- Decode actual representative frames/sections for technical integrity, corruption/black frames/silent intervals and media-specific validation. Treat scene/highlight extraction as model-derived interpretation with evidence and review.
- Use a single media parser/decoder abstraction reused by readers, automatic Episode Assembly, video conversion and integrity checks; do not instantiate competing decoders without justification.
- Produce media technical report with versioned parser/codec provenance and reproducible test file (synthetic/public-domain fixture).

## Image/video conversion and derivative generation

- Cropping, resizing, format conversion, recompression, thumbnails/posters, audio extraction, frame/contact sheet generation and validated video output profiles; all through independent, versioned generated files.
- Preserve orientation, aspect by default, alpha/color profile when supported, metadata/source provenance, reliable filesystem/MediaStore/remote upload semantics and sensible quality settings.
- Advanced edits may reuse video EDL and specialist Art/Sound only when owner-approved; conversion itself must not need Control.
- Rendering/encoding must respect actual phone codecs, RAM/thermal/battery/foreground limits and resume/cleanup behavior. Never assert hardware acceleration from capability flags alone.

## Cross-app project-aware asset suggestions

- Suggestions are scoped to actual partner manifests and authorized project metadata: potential Art reference image, Sound clip for episode assembly, Foundry texture, KK character/scene mod, NoWorld package reference or Codex image, with exact source ID, version and format.
- Suggestions **do not** imply automatic compatibility; target app must validate its own native requirements and produce a receipt. Never invent NoWorld skeleton/morph admission, game model support or KK streaming readiness.
- Control coordinates complex chains, Nexus can directly send an authorized concrete asset and receive a validated result, and the user's asset remains available independently.

## Independent acceptance

Each operation must have a real sample and expected result; verify all output bytes/receipts, background interruption, indexing semantics, owner corrections and no original modification. Distinguish `NEW_CAPABILITY_SPECIFIED`, `SOURCE_PRESENT`, `WORKING_ON_PHONE` and `OWNER_ACCEPTED`. No visual mock, proof-of-concept for only one file, or HTML-only test certifies universal media support.

## 2026-11-27 refinement

Use actual representative collection covers, continue reading/playback, canonical related-content exploration and one background activity indicator. Rating is subjective per-file metadata and feeds automatic preference learning and authorized recommendations; Review is correctness/automation confirmation and uses a separate learning objective. No standalone rating module or duplicate action/queue implementation. See [16](16-APPROVED-EXPERIENCE-AND-ORGANISATION.md).
