# Nexus implementation audit — 2026-11-28

Authority: docs/00–16, especially owner-confirmed docs/16, and spec/acceptance-cases.v1.json. This audit records implementation evidence and gaps; it does not change owner acceptance or assert device verification. Character completion is deliberately independent (owner currently at BL). The historical workbook is not a new complete character release.

## Evidence

- 6f36146bc746a53a13163adcf58cbd6ca152dda5: native Android compile/regression run 46092359885 passed; premium candidate compilation 46092354897 passed; specification and UI contract passed.
- 9e3c26b13fea617258f55344ab03c86d327738d7: exact owner archive JSON embedded; 16 files, 1,278 unique tags, 1,621 strict series entries. Source tests 46093280562 and 46093285948 passed. Native compilation failed on the malformed ZIP-path Kotlin escape; corrected at 8f94cb587b40255349e82473dc6c8473be275938.
- 8f94cb587b40255349e82473dc6c8473be275938: EPUB indexing constructor corrected, TXT support, GIF viewer, EPUB/audio covers, PDF/CBZ layout controls.
- 2bfe7b2e203e3b4d0e401dfc845a370e54ebc321: account-scoped cloud Books/Videos/Assets panes and native taxonomy parser test; source UI/spec checks 46093657250 and 46093662130 passed. Native compile/regression and premium compilation had not completed at the time of this audit.
- f806f6c0ff29ad982d36ff487fbcfb6d1adcedd8 extends PDF text-layer extraction and explicit partial coverage, TXT deterministic chunk indexing, archive directory viewing and correct JPEG response type. These are source changes, not device receipts.

- 663f04ebbb8cac05b182ff934142491a201d8bd6 implements exact repeated edge detection across at least three episodes using video keyframe intervals and packet-level evidence from both audio and video. First opening and last ending are retained. The next revision preflights every proposed cut and falls back to full content when synchronization is uncertain. Unit fixtures are added; real-media verification is outstanding.

## Requirement review

| Requirement group | Implemented evidence | Outstanding acceptance / functionality |
| --- | --- | --- |
| M001–M006 | Three pinned donor source families; single Nexus Android package; native Fusion and Rounder modules; verified original and account-scoped registry | Complete donor parity, signed baseline migration, model release packaging and end-to-end account isolation on actual phone |
| ACQ-001–003, ACQ-027–028 | Retained native provider, torrent and direct acquisition engines, existing original receipts, shared cloud publication routing | Full selected-original/provider parity, complete album acquisition, actual transfer/reopen, live-sync/backfill cursor audit |
| SRCH-001, CLOUD-001, CLOUD-027 | Private local and scoped TeraBox metadata indexes, scoped cloud browsing/stream URIs, account-selected publication, library media panes | Real multi-account uploads and streaming, cache and pin pressure, cleanup recovery, unified gallery indexing across all scopes |
| UI-001, UI-027–028 | Single launcher and three permanent destinations; charcoal/silver, source-backed gallery/cover/video views, opened-file rating | Physical visual acceptance, every content type and adaptive layout, full swipe/back state, complete specialist submenu |
| FUS-001–002, FUS-027–028 | Donor Core native inference/classification, taxonomy/review/learning and ratings, migration fix, embedded exact source taxonomy, verified prior CPU model smoke | All native family coverage with real images, migration against user's database, no unsupported identity claims; current character knowledge intentionally unfinished |
| READ-001, IDX-001 | Rounder native archive, PDF, EPUB and playback; pages and continuous layout modes, GIF, actual cover, EPUB/DOCX/TXT and Android 15+ PDF text extraction | Actual progressive device reading, older OS PDF, scanned-page/comic OCR, CBR, 3D and spreadsheet parity, full index coverage/privacy and position tests |
| CTRL-001, ASSET-001 | Existing authenticated read-only Control provider; direct independent Nexus operation | Verified specialist capability discovery and signed scoped asset operations, revocation/recovery/receipts |
| TXT-001 | Private extraction and existing Core OCR runtime source | Validated packaged multilingual OCR/translation model and native execution; automatic durable translation, layout-preserving translated editions and offline quality acceptance remain incomplete |
| VID-001–002 | Native lossless compatible H264/AAC assembly with source hashes, journal and verified output, source retention, conservative exact audio/video repeated edge detection | General OP/ED/recap/preview segmentation beyond exact shared edge packets and nonredundant scene preservation, unsupported codec conversion, durable restart and device playback |
| REP-001 | Existing archive integrity verification and bounded preview; real archive file listing | Safe batch extraction, actual corrupt-file repair outputs and verification |
| SEM-001 | Donor native Core model and intelligent library components retained | Complete real-embedding semantic search, smart collections and scale/quality evidence |
| ORG-027, BOOK-027 | Core naming policy and existing complete-title archive assembly paths retained | Full authorized cloud moves and rollback, all media naming and complete-title acquisition evidence |
| DATA-001, REL-001 | Additive schema changes and original-preserving registry | Actual transactional three-app migration/backup receipts and signed physical-device release with donor apps preserved |

**Release gate:** do not label Nexus complete, production-ready, fully premium, or issue-free on compilation/source tests alone. All acceptance cases remain owner-unaccepted until their required real evidence exists. No PC or phone operation was performed in this audit.


## Continuation: archive recovery, native OCR indexing and visual retrieval

- 49890517c63d233a797aec54c2dad3b42fc6fbfa: prior native regression/compile and specification runs 46093972168 and 46093972165 passed.
- REP-001: ZIP/CBZ central-directory preflight, bounded staging, CRC/size/SHA verification and contextual extraction into verified originals with a durable provenance receipt. No arbitrary extraction paths, recursive archive expansion, or source destruction. The current bounded extractor intentionally refuses ZIP64, encryption, empty entries, special entries and unsupported archive methods. General archive-format parity is outstanding.
- REP-001: exact recovery against an existing verified SHA/length identity, with explicit confirmation, damaged bytes retained, cross-process publication lock, atomic replacement and readback receipt. This is exact-copy restoration, not arbitrary repair of irrecoverable damaged media.
- READ-001 / IDX-001: native existing-model OCR for scanned PDF and CBZ pages; private per-page evidence, coverage and model receipts; non-destructive index migration, historical book seeding, resumable bounded work, Unicode CJK substring indexing with exact verification. Model absence is reported as blocked, not successful OCR. General comic/archive formats and validated multilingual model release remain outstanding; translation is not implemented by indexing.
- SEM-001: persistent fingerprint-bound quantized whole-image WD encoder descriptors, same-encoder streamed cosine ranking, recovery of compatible cached vectors and a contextual similar-artwork action. No reclassification of subject crops as whole images. Semantic ranking no longer returns unscored fallback rows. Full-library reanalysis/backfill, approximate-nearest-neighbour performance at owner scale, text-to-image retrieval, smart collections and device evidence are outstanding.
- 21 additional JUnit test methods cover archive safety, recovery, CJK index terms and visual similarity math. These fixtures are committed, not certified executed.
- Specification runs 46133608268 and 46133945687 passed on intermediate heads. Native runs 46133608311 and 46133945681 were cancelled/superseded by subsequent commits. Final implementation head before this audit update: 151d20416f7c623291a36c454de63baf42b5fb60. Final-head native regression is not yet verified.
- No PC/phone work, physical acceptance, signed release, provider/account verification, or claims of complete implementation were made. PR #2 stays draft and unmerged.


## Media operations continuation

- Native media inspection: verified source hash, image geometry/color/animation metadata, track codecs/dimensions/audio properties, bounded packet probes and three representative video frame decode probes. JSON receipts explicitly distinguish representative inspection from complete decode validation.
- Native derived still images: bounded aspect-preserving no-upscale resize and PNG/WebP-lossless/JPEG output, independent decode/readback verification, deterministic operation identity and registered provenance. Animation flattening is rejected; JPEG is rejected when alpha may be lost. Derived stills are converted to sRGB; EXIF stays with the original. Poster extraction uses a requested position and closest sync frame, not exact-time decoding.
- Selected AAC extraction: one explicit audio track, lossless M4A remux, complete packet and relative timestamp hash comparison, codec configuration and registry readback verification. Unsupported codecs, encrypted or nonmonotonic samples fail closed. No general transcoding or codec conversion claim.
- Shared opened-file details expose these actions without adding navigation destinations. Output remains local and verified; no automatic cloud publication or source deletion by these operations. Six geometry/identity regression fixtures added. Android execution and full-format tests outstanding.
- Development batch staged separately to avoid cancelling the existing compile repeatedly, then promoted by fast-forward only. No user device operations or signed deployment.
