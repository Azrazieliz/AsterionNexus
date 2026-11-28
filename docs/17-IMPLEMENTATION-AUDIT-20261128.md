# Nexus implementation audit — 2026-11-28

Authority: docs/00–16, especially owner-confirmed docs/16, and spec/acceptance-cases.v1.json. This audit records implementation evidence and gaps; it does not change owner acceptance or assert device verification. Character completion is deliberately independent (owner currently at BL). The historical workbook is not a new complete character release.

## Evidence

- 6f36146bc746a53a13163adcf58cbd6ca152dda5: native Android compile/regression run 46092359885 passed; premium candidate compilation 46092354897 passed; specification and UI contract passed.
- 9e3c26b13fea617258f55344ab03c86d327738d7: exact owner archive JSON embedded; 16 files, 1,278 unique tags, 1,621 strict series entries. Source tests 46093280562 and 46093285948 passed. Native compilation failed on the malformed ZIP-path Kotlin escape; corrected at 8f94cb587b40255349e82473dc6c8473be275938.
- 8f94cb587b40255349e82473dc6c8473be275938: EPUB indexing constructor corrected, TXT support, GIF viewer, EPUB/audio covers, PDF/CBZ layout controls.
- 2bfe7b2e203e3b4d0e401dfc845a370e54ebc321: account-scoped cloud Books/Videos/Assets panes and native taxonomy parser test; source UI/spec checks 46093657250 and 46093662130 passed. Native compile/regression and premium compilation had not completed at the time of this audit.
- This audit commit extends PDF text-layer extraction and explicit partial coverage, TXT deterministic chunk indexing, archive directory viewing and correct JPEG response type. These are source changes, not device receipts.

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
| VID-001–002 | Native lossless compatible H264/AAC assembly with source hashes, journal and verified output, source retention | Reliable OP/ED/recap/preview segmentation and nonredundant scene preservation, unsupported codec conversion, durable restart and device playback |
| REP-001 | Existing archive integrity verification and bounded preview; real archive file listing | Safe batch extraction, actual corrupt-file repair outputs and verification |
| SEM-001 | Donor native Core model and intelligent library components retained | Complete real-embedding semantic search, smart collections and scale/quality evidence |
| ORG-027, BOOK-027 | Core naming policy and existing complete-title archive assembly paths retained | Full authorized cloud moves and rollback, all media naming and complete-title acquisition evidence |
| DATA-001, REL-001 | Additive schema changes and original-preserving registry | Actual transactional three-app migration/backup receipts and signed physical-device release with donor apps preserved |

**Release gate:** do not label Nexus complete, production-ready, fully premium, or issue-free on compilation/source tests alone. All acceptance cases remain owner-unaccepted until their required real evidence exists. No PC or phone operation was performed in this audit.
