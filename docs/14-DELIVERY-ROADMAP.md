# Nexus execution roadmap — merge-first critical path

**Date:** 2026-11-26  
**Status:** approved scope mapped into executable engineering stages; **Android merge is NOT YET IMPLEMENTED**.  
**Main implementation source:** [Nexus canonical specification](../README.md); [file-level donor map](13-SOURCE-ADAPTATION-MAP.md), [acceptance and evidence](09-EXECUTION-AND-ACCEPTANCE.md).  
**Owner objective:** shortest path to one **real, functional, exceptionally premium** native Android Nexus product, preserving the functionality of Core, Downloader and Rounder and adding approved new abilities. No slogans anywhere in the user interface. No clone, faux demo, partial dashboard, dead buttons, or separate donor UIs.

## Working-time estimates, not delivery promises

These are rough **engineering effort/calendar planning ranges** assuming authenticated source+build access, sustained focused work, no major licensing incompatibility and available phone testing. A session cannot guarantee uninterrupted future work. Each phase has an evidence gate; a failed gate changes the estimate.

| Usable milestone | Optimistic planning range | Qualification |
|---|---|---|
| Audited donor import and first buildable co-installable three-way Nexus | **2–5 focused working days** | Assumes known branches compile together after adapting app lifecycles; Rounder archive reconstruction and native ABI conflicts can extend this |
| First true phone vertical slice: selected online media -> exact verified file -> indexed/searchable/openable Nexus item | **1–3 weeks total** | Real provider access and exact original extraction are external unknowns; start with an authorized reliable source and keep difficult providers open |
| Functional parity plus stabilized Core, repaired Downloader flows, retained Rounder media and **premium new UI** | **3–6 weeks total as an optimistic engineering target** | Each provider and each major UI/reader flow needs actual Samsung acceptance; blocked sites may not be recoverable in a fixed period |
| All Nexus-specific advanced abilities, with credible production-grade translation and intelligent episode cuts | **additional months of engineering and testing** | First-party offline CJK OCR/translation model and very low false-cut media intelligence are R&D, not copy/paste; possible longer if trained resources or evaluation corpus unavailable |

**Do not represent these as dates, verified remaining hours, background automation or commitment to future delivery.** The new conversation must execute immediately and report measured progress; elapsed time cannot replace acceptance. One session may complete only a subset, but should continue all independently actionable work.

## Do not build from zero

**Existing source authority and starting points** (refresh heads before copying):

1. **Downloader** `Azrazieliz/AsterionDownloader` `repair/downloader-global-search-20261121`: Kotlin/Gradle host, original source adapters, Kyora cosplay, Pixiv/X, VideoFlow, native torrent and job/transfer engines. **DO NOT** copy its old UI or assume existing provider tests passed.
2. **Core** `Azrazieliz/Asterion-Core` `recovery/core-v2100-gaps-20261118`, at/after `f453a9d5defd`: Fusion native SQLite, model/runtime, per-character tagging, validated feature-learning heads, corrections and jobs. Prefer latest source, do not regress to old Core `main`.
3. **Rounder** `Azrazieliz/AsterionRounder` `feat/control-recursive-inventory-20261122`: **reconstruct the actual assembled source** from six-part archive, CI patch order and additions; import the accepted TeraBox connector, index, readers, player, organizer and data/streaming. The owner accepts existing functional Rounder behavior apart from UI. Do not trigger a speculative rewrite.
4. **Control** `Azrazieliz/Asterion-Comander` / existing authenticated PC+Android bridge: external general assistant, browser and complex orchestrator. Do **not** replace the working bridge or block native Nexus use on its AI. Later add versioned signed API only.
5. **Partner repos:** Art, Sound, 3D, Writing, Game Pilot/Codex, KK and NoWorld; only project/asset integration, not a code merger. Adaptive Study excluded.

## Fastest sequence and concrete exit artifacts

### Gate 0 — Forensic source selection and reproducible assembly (before bulk copying)

**Actions:**
- Refresh donor branches/commits, compare with installed accepted app versions, capture license manifests, source/build identities, native libs and actual APK signing certificates (fingerprints only in public Git).
- Extract Rounder's exact assembled source through its **existing CI workflow**; if needed reproduce on Windows through the authenticated Asterion Control bridge, **not a new bridge**.
- Generate `spec/source-import-manifest.*` with source SHA, content hashes, dependencies and destination. Audit manifests and package/component collisions: SQLite DB names, ContentProvider authorities, jobs/WorkManager, coroutine/thread pools, SAF, JNI .so ABI, R8, Gradle, Kotlin and Android versions, file storage paths.
- Pick single host based on actual compatible build. Default working hypothesis: Downloader Kotlin Android host. Preserve all untouched donors and installed apps.

**Exit:** truthful import graph and one buildable donor host with dependency closure; source/asset rights and secrets scan. Cannot claim all donors merged yet.

### Gate 1 — Three-way native engine merge (highest priority)

**Actions:**
- Import/adapt **actual** Downloader provider/transfer/torrent engines, **latest** Core Fusion/schema/models/feedback and **assembled** Rounder TeraBox/index/readers/player/organizer into one native Android codebase.
- Rename namespaces/component authorities and unify app startup, Android 17 process/foreground services, SAF grants and lifecycle; keep donor package IDs and user data untouched.
- Initially retain thin internal compatibility adapters where rewriting would risk regressions; ensure genuine functional call paths, not empty stubs or cross-app "open Rounder" buttons.
- Move donor tests and make them run from Nexus; block build on broken tests rather than rewriting expectations. Do **not** invest in final visuals until engine import can start and operate.
- Establish reproducible CI for Android compilation, JUnit/unit tests, imported assets/natives, and a **co-installable** release candidate. No false signing claim.

**Exit:** one Nexus APK source tree with real callable features from each donor, compilable test build, no manifest/API collisions, demonstrated smoke execution per donor. The accepted Rounder functions are not sacrificed.

### Gate 2 — Cohesion and first true vertical slice

**Actions:**
- Establish single indexed `NexusRegistry` with stable content IDs, per-account locations, verified source provenance, distinct original vs derivative, optional relation edges, Fusion authority and typed receipts; keep migration aliases.
- Unify **one durable job/transfer ledger** and idempotent states; do not conflate queued/bytes complete/cloud verified/indexed.
- Build one **simultaneous federated search** across authorized local library, TeraBox accounts and enabled online providers, preserving query privacy and partial failure states.
- Implement a minimal **real** acquisition path from one selected exact source original, through complete bytes/verification, into actual Nexus library and reopen after restart. Do not defer it to mock data or a UI demonstration.
- Preserve independent per-provider Sync and Backfill, full-gallery enumeration, TeraBox multi-account isolation, cloud range reads, cache budget and owner-proposed local cleanup.

**Exit:** `ACQ-001`, `SRCH-001`, first `CLOUD-001` and actual installed-device proof. This is the **first meaningful product milestone**, not merely compilation.

### Gate 3 — Provider and Core repairs **inside Nexus**, parallel with UI work

**Downloader defect lane:**
- Resolve recorded real provider failures via actual source-specific evidence, original/cosplay album counts, search pages, auth and selected stream, not endless generic selectors.
- Restore **cosplay creator -> album -> ALL originals**, correct mirror identity and finish/retry semantics.
- Restore actual torrent magnet/descriptor transfer, piece verification, security review, publication and **stop seeding on verified completion**.
- Run source-specific Android acceptance with authorized media; distinguish external access/geo/account blocks from software defects. Never assert universal site support without proof.

**Core defect lane:**
- Preserve Nov24+ advanced implementation; complete all **applicable** family evaluation and record gaps honestly.
- Stabilize repeated runs, multi-subject consistency, correction persistence, model-head evaluation, reversible organization and resource budget.
- Leave character **identity** unverified until actual Character Knowledge/reference sheets permit an accuracy test. Other tagging/organization must work.

**Rounder baseline lane:**
- Treat currently accepted non-3D features as parity gates: TeraBox, local files, search, image/video/audio, EPUB/PDF/comics/Office supported formats, streaming, organizer, bookmarks, original aspect and player modes.
- Fix only integration regressions; replace poor UI, not engines.

**Exit:** real per-provider and real-image evidence, preserved accepted Rounder behaviors, no major crash/scan inconsistency.

### Gate 4 — Single exceptionally premium Nexus interface (parallel once services are callable)

**Absolute design rule:** **NO SLOGANS** anywhere—home, splash, empty/loading/error, notifications, onboarding, media player, settings or translated UI strings. No marketing/promotional copy, faux dashboard, decorative labels, tech jargon, useless buttons or dead affordances.

**Approach:**
- Build a new design system and adaptive **Library** home (not old Core/Downloader/Rounder screens). Context-aware layout and always-accessible useful advanced actions; functions adapt to media/folder/project, no arbitrary permanent tabs.
- UI concepts worth considering (proposals, not unapproved requirements): (a) concise global command/search bar with local/cloud/web progressive results; (b) instant account/location control; (c) media-specific compact command rail; (d) series/episode view with assembly preview; (e) nonintrusive review queue and repair/status center.
- Establish cohesive typography, density, restrained palette, touch/keyboard/navigation and media content priority. Use real source thumbnails, never fake media tiles. Support 48dp touch targets, orientation/system insets/accessibility, 300k library paging and responsive background work.
- Present **working** Library, Acquire, Transfer, Fusion/review, Reader, Player, Edit and Settings contexts. No placeholders or externally launched old donor screens masquerading as Nexus.
- On each meaningful milestone inspect real SM-S948B Android 17 screenshots/recording and fix errors, jank, unresponsive controls and readability before accepting.

**Exit:** `UI-001`, actual user owner approval; every exposed control functions and no slogan anywhere.

### Gate 5 — Cross-app harmony

- Integrate **existing** Asterion Control signed bridge, without introducing a redundant bridge, replacing relay/watchdog or requiring the Control AI model to run.
- Nexus remains the canonical library/media/Fusion authority; Control orchestrates **complex** multi-app work and shared browser; ordinary Nexus use survives Control outage.
- Allow **direct owner-authorized scoped calls** from Nexus to Art, Sound, Foundry, Writing, Game Pilot/Codex, KK and NoWorld where APIs genuinely exist. Source/project identity, capability/version negotiation, signed Android permissions and output receipts mandatory.
- Deep project browsing and project-aware asset suggestions use actual partner manifests, not copied specialist code, fake links or optimistic target-format guesses.

**Exit:** real signed request -> actual target action -> verified output and revocation/failure tests, without regressing normal Nexus operations.

### Gate 6 — Additional Nexus-native capabilities, ordered by reuse

**6A (lower integration risk, share foundations):** smart collections, private recommendations from existing library, archive inspection/extraction, corrupt/missing file detection and safe recovery, technical media metadata, conversion/derivatives, creator/series following dashboards, visual semantic search grounded in Fusion. Require real outputs, bounded workload and privacy.

**6B (major video R&D):** automatic **episode assembly** in selected video collections. Order episode parts, keep first OP and last ED, remove **reliably identified** intermediate OP/ED, redundant recaps and previews, protect **all unique narrative** and uncertain segments, correctly concatenate output with audio/subtitle sync. Editable EDL, video preview, verified export, checkpoint/retry, rollback, cloud staging. Then general automatic montages/highlight/pacing/cuts, reusing the timeline. False-positive story removal is critical failure.

**6C (major offline language R&D):** genuinely **phone-native offline** first-party quality CJK->English translation of each eligible imported verified book/comic, book/comic OCR only, formatting/QA, distinct derivative edition and **full private text indexing of all imported books**. No fake dictionary, hosted API, companion app, Windows dependency or unsupported text represented as translated. Availability of legally usable weights/training resources, performance and quality evaluations are the main schedule risk.

Do not postpone essential 6A foundations solely because 6B/6C need R&D; these tracks can advance independently after Gate 2.

### Gate 7 — Non-destructive migration, sustained certification and old-app retirement

- Under authorized read/export paths migrate **only owner-approved** records: Downloader config/subscriptions/completed catalogs/safe queue history; Core Fusion/tags/ratings/corrections/history; Rounder account mappings/cloud indexes/bookmarks/reading positions.
- No bulk file moving, cloud credential copies into Git or "direct private app DB" assumptions. Preserve source apps and physical originals. Confirm repeatable hash/count/source-ID diffs, transactional import, interruption recovery, rollback and source app still functions.
- Test many files/accounts and active workload contention, model/runtime memory/thermals, process kill, network loss, insufficient storage, signer/update identity, original/translated book reads, video edited outputs and real partner actions.
- Deliver one actually signed, source-tied Nexus APK after each evidence-based release preflight; install and test on owner's phone; keep original apps until **full Nexus owner acceptance and explicit retirement approval**.

## Parallel lanes without duplicating engines

| Work lane | Can overlap with | Blocking dependency |
|---|---|---|
| Donor source/CI/assembly | Host prototype, UI design tokens, synthetic contract testing | Actual source SHA/rights and assembled Rounder |
| Native three-way source import | Registry schema, feature smoke, API collision tests | Core+Downloader+Rounder imports must be buildable |
| Downloader provider repair | Core stability, Rounder parity, UI implementation | Requires Nexus transfer/receipt semantics for acceptance |
| Core full tagging | Provider repair, library UI, book index | Needs latest Core runtime, sufficient real curated image observations |
| Premium UI | Repair lanes, source-backed API stabilization | Requires real services before shipping controls |
| Video assembly R&D | Indexing, Fusion, editing design, provider repair | Actual media input/render pipeline and reference clips |
| Offline translation R&D | Video editing, integrations, media indexing | Real first-party model/OCR assets and performance/quality proof |
| Cross-app actions | New media features after baseline bridge | Signed actually implemented partner capabilities |
| Final migration & retirement | No destructive parallelism | Requires signed tested Nexus and owner approval |

**Critical path:** reproducible donor code -> one buildable native app -> registry and true transfer -> verified phone library -> premium UI and functional parity -> advanced features -> final signed migration/acceptance. Do not advance a stage solely because CI compiled.

## Rules to maximize execution speed and avoid month-long loops

1. **Do not restart standalone Downloader repair cycles.** Import its latest implementation, diagnose failures with stage-specific real transfer evidence and resolve within Nexus.
2. **Do not rewrite accepted Rounder reader/cloud engines** unless proven incompatible; reconstruct current assembled source once with deterministic import.
3. **Do not replace latest Core with a generic general-purpose AI model.** Reuse its trained/validated visual feature architecture and close real missing-family gaps.
4. **No UI-only progress.** A premium design is useless if search/download/reader operations don't work. Build from actual data service contracts.
5. **No endless unmeasured retries.** For a failing gate, capture precise first failure, compare expected/actual artifacts, diagnose once, patch and retest narrowly before repeating full run.
6. **Do not claim that code presence, source CI, emulator or an APK file equals owner acceptance.**
7. **Avoid build storms.** Source/unit preflight first, then one signed artifact per deliberate release gate; real phone test on that exact SHA.
8. **Protect user time.** Do not ask questions already approved in `spec/owner-decisions.v1.json`. Resolve engineering unknowns independently. Surface only genuinely owner-dependent legal/account/visual decisions.
9. **No public secrets/licensed media.** Model weights, credentials, games, private datasets and user library contents must stay outside the public repository.
10. **Keep the single canonical progress ledger accurate** with requirement IDs and `SPECIFIED -> SOURCE_PRESENT -> BUILT -> DEVICE_EXECUTED -> OUTPUT_VERIFIED -> OWNER_ACCEPTED`; no inflated status.
11. Continue all independent executable work when one external provider or tool blocks; report the blocker, completed tests and next actionable transition precisely.
12. **Make small reversible source commits** tied to concrete behavior/tests, with preservation manifests and a rollback path. Never mutate donor apps, device or data solely for convenience.

## Suggested immediate first execution session

1. Read `README.md`, `docs/00`, `01`, `09`, `13`, `14`, `contracts/`, `spec/owner-decisions.v1.json`.
2. Inspect all donor HEADs and build branches; verify existing Control authenticated Windows bridge availability before any attempt to use the PC/phone. No substitute bridge.
3. Create Nexus `work/merge-core-downloader-rounder` branch from latest `main`. Audit current Android build and exact Rounder assembly. Do not start with documentation-only refactoring.
4. Establish import manifest, compileable initial host and then **actual** three-way engine imports. Preserve donor repositories.
5. Add CI compile/unit/instrumentation gates and progress evidence with exact commit. Build and test each source integration rather than declaring merge after copying directories.
6. Continue to the **selected-source -> real verified bytes -> searchable Library** gate without stopping at a skeleton or architecture diagram.
7. New conversation closes with truthful build/test/phone/source state and next unresolved gate. Do not promise asynchronous delivery.

## Product suggestions not automatically accepted as new scope

If the system supports it without reducing approved behavior, consider a personalized "continue reading/watching" strip (real progress only), a lightweight cross-source command search, collection-level space budget, a "repair/resolve" queue, and a single nonintrusive permission status view. These are **design ideas** requiring no separate UI slogan or unnecessary page, and should never delay proven working engines.
