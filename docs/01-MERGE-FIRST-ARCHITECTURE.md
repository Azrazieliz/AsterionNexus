# Merge-first architecture, source adaptation and cohesion

**Objective:** consolidate functioning implementations and data into one maintained native Android app BEFORE treating Control bridges, optional services or new AI features as prerequisites. This is migration and adaptation, **not greenfield reimplementation**.

## Authority and architecture layers

```text
Nexus Android Application: single package / one native navigation
    Presentation: adaptive Library + contextual Discover / Transfers / Organize / Intelligence / Media actions
    Application domain: task coordinator, search federation, content intake, visual reviews, books, video editing
    Shared registry: persistent content IDs, locations, provenance, source identities, receipts, jobs, transactions
    Feature modules:
       Acquisition       <- Downloader provider and torrent sources
       CloudLibrary      <- Rounder assembled TeraBox/index/reader/player code
       FusionIntelligence<- latest Core Fusion SQLite, native vision, correction engine
       MediaProcessing   <- new editing, offline translation, extraction/repair
       Integrations      <- typed, signed optional Control/Art/Sound/3D/Writing/Game adapters
    Infrastructure: Android SAF/MediaStore, SQLite WAL, Android schedulers, verified cloud clients,
                    native media/AI runtimes, bounded cache, crash journal, release/update identity
```

The diagram is a **target responsibility layout**, not a claim that these named modules already exist. Prefer direct code extraction/adaptation and thin internal interfaces over one giant rewrite or nested legacy APKs.

## Donor source-selection rules

- Downloader donor: `Azrazieliz/AsterionDownloader`, presently inspect `repair/downloader-global-search-20261121` and any more recent verified branches. Bring Kotlin source, original gallery adapters, video routes, native torrent service, test fixtures, source index, job logic, hardened cookie isolation, TransferService and safe queue semantics. Preserve exact observed selected-media behavior, not just function names.
- Core donor: `Azrazieliz/Asterion-Core`, inspect `recovery/core-v2100-gaps-20261118` at/after `f453a9d5defd` and any subsequent advances. Bring authoritative Fusion SQLite/schema/migrations, native WD-ViT/subject detection, learned feature heads, image/subject-scope policies, correction history, human review, jobs, durable media evidence and tests. Its old `main` is not an acceptable stand-in.
- Rounder donor: `Azrazieliz/AsterionRounder`, inspect `feat/control-recursive-inventory-20261122` and current release. **Reconstruct the exact assembled Android source** from the six-part source archive, patches/overrides/additions and **ordered** workflow hotfixes, then compare its function-level implementation with the accepted installed APK. Do not import old `main` assets as proof of current behavior. Extract TeraBox connection/provider, index, progressive media reader, Office/EPUB/PDF/comic/archive handling, player, organizer, format conversion, bookmarks and accepted file behaviors.
- Preserve source commit SHA, file path, source provenance, third-party licenses, test IDs, package ID and donor build identity in an **import manifest** for every migrated component. No bulk folder copy without dependency closure and collision audit.
- The older `work/nexus-integration-20261114` branch is historical and does not independently establish a live three-way integration.

## Mandatory dependency and collision audit

Inventory every donor's Android manifest, application ID, receivers, ContentProviders/authorities, jobs/services, notification IDs, permissions, ProGuard/R8 rules, native ABIs, asset packaging, Gradle versions, dependency licenses, JNI bridge, storage roots, FileProvider authorities, database schemas/version numbers and migration order.

Before transferring source:
1. Establish a buildable **single-host project** from source with documented signing identity and no private data.
2. Trace each import to one owner capability and one source of truth. Delete/rewrite **nothing** in a donor repository.
3. Resolve duplicate class/package names, services, TeraBox clients, multi-process initialization, thread pools, image loaders, content URI grants, cached credentials and model runtime ownership explicitly.
4. Preserve unit/integration tests and augment them with cross-module semantics, real byte receipts and Android lifecycle checks.
5. Preserve Rounder's observed supported features, while replacing its poor UI without accidentally dropping reader/player actions.
6. Feature flags may isolate a broken new module, but must never turn a required inherited feature into a permanently absent UI placeholder.

## Canonical ownership and conflict prevention

| Domain | One authority | Imported donor responsibility | Conflict rule |
|---|---|---|---|
| Content/location/provenance | Nexus Registry | Rounder/Downloader existing records | Legacy IDs remain aliases, not second operational authorities |
| Canonical visual taxonomy/recognition | Fusion SQLite in Nexus | Latest Core | Import full corpus and corrections, preserve stable IDs |
| Remote cloud account session | Cloud connector per account | Rounder TeraBox | Do not combine sessions or trust mixed-account index generations |
| Acquisition source and provider state | Nexus Acquisition | Downloader + Kyora | Search hit != original != downloaded != remote verified |
| Reader/viewer | Nexus Media | Rounder | Preserve format and gesture semantics independent of redesigned UI |
| Video timeline/output | Nexus Media Processing | New feature | Original is never overwritten; output proof independently validated |
| General agent/shared browser | Control (external) | Signed capability bridge | Core Nexus features must work without Control |
| Cross-app creative execution | Each specialist; Control orchestrates graph | Asterion partners | Nexus issues scoped direct actions, receives receipts, no duplicated engines |

A file may have multiple cloud locations, alternate filenames and related derivatives. Registry identity is content-backed and provenance-backed; do not use raw filename, album title or a cloud object ID alone as global identity. Canonical Fusion tags never become lossy plain-text labels.

## Transitional phases (implementation order is binding)

**M0 Inventory & preservation:** capture current donor SHAs, exact builds, signed identities and data/export capabilities; create an evidence map; confirm one host and prevent credential transfer into public Git.

**M1 Native three-way consolidation:** migrate inherited functional source modules and schemas into one compileable Android product. Maintain distinct adapters and original library semantics, but unify lifecycle, permissions, navigation, model/resource budgeting and job ledger. First phone smoke: browse real cloud item, inspect real Fusion taxonomy and list a real source job. No new AI promises.

**M2 Cohesion:** one file registry, actual source-to-library receipts, concurrent global search, single queue semantics, verified cloud upload/download and unified local/remote access, complete sources/backfill and support for all accepted readers. Fix broken Downloader workflows **in Nexus**. Validate one real source-to-indexed-library path before further expansion.

**M3 Control/specialist integration:** signed adapters, diagnostics/events, direct authorized specialist actions and shared optional browser. Nexus is independent if Control is offline; Control orchestrates multi-step specialist workflows when available.

**M4 New capabilities:** semantic search, smart collections/recommendations, file integrity/repair, archive batch operations, full private book indexing, native translation, automatic video assembly and broader editing, media analysis/conversion; proceed through independent phone gates.

**M5 Full migration and retirement:** only after preserved-data diff, repeatable Android acceptance, owner approval and rollback test. No automatic uninstall of donor apps or deletion of source folders.

Engineering may build compatibility shims and tests earlier to protect the merge, but a shiny new feature must never displace the M1/M2 acceptance priority.

## Runtime engineering policies

- Single user-visible Android app identity. Internal modules can run in a service or bounded separate process only when justified (e.g., native torrent crash isolation, model memory). No three embedded app launchers.
- Independent durable jobs: acquisition, indexing, visual inference, translation, video editing, cloud operations. Stable idempotency keys, WAL/transaction boundaries, interruption recovery, cancellation and outcome-unknown reconciliation. Do not repeat irreversible operations on transport ambiguity.
- One resource governor for RAM, CPU/GPU/NPU, disk/cache, thermal state, battery and active media; do not load uncontrolled heavyweight models concurrently. Prefer measured cheap paths and appropriate staging.
- Fine-grained per-source capabilities and real error reasons; partial failure cannot falsely make the entire workflow `COMPLETE`.
- Internal UI state must come from authoritative job/registry records; no fabricated progress, fake connection lights or "everything ready" status.
- Native capabilities must be genuinely available on Android 17 and measured on Samsung SM-S948B, not inferred from compiled code.
