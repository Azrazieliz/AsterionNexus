# Execution roadmap, verification framework and release gates

**Purpose:** turn the owner's canonical Nexus spec into independently verifiable engineering work. This is **not a claim of implementation**. The decisive criterion is functioning real use, with user-accepted premium UI and preserved data—not passing compilation.

## Phases and exit gates

| Phase | Work to execute | Required exit evidence |
|---|---|---|
| **P0 — Donor forensic intake** | Pin latest three branches, exact assembled Rounder release, source licenses, manifests, native dependencies, signing/data/export boundaries, Control capability implementation, unresolved defects | Signed-off import matrix with source SHA+file lists, tests and no-secret checks; no donor modified |
| **P1 — Native three-way source merge** | Establish one Kotlin/Android host with actual Downloader, Core Fusion and Rounder accepted modules adapted and compiling; resolve permissions, services, database namespaces and classes, native ABIs, release identity | Build/unit/instrumentation tests, runtime smoke of one real feature from each donor, no missing package/resources |
| **P2 — Library and acquisition cohesion** | Unified registry, multi-account TeraBox, simultaneous local/cloud/web search, actual queues and receipts, independent sync/backfill, model job budgets; fix Downloader provider/cosplay/search/torrent defects **in Nexus** | Real source -> complete verified bytes -> TeraBox/local readback -> correctly indexed searchable/openable file, source-specific failure receipts |
| **P3 — Unified premium interface** | Implement one adaptive library-first shell, contextual actions, real media viewers, source search, Fusion, transfers; completely replace messy donor UI, preserve native reader/player behavior | Slogan-free audit, visual comparison, 48dp targets, stable scrolling, orientation/accessibility and owner visual acceptance on Samsung |
| **P4 — Fusion correctness and learning** | Bring latest Core tagging and review into coherent Nexus, evaluate all required families, stabilize and validate real image accuracy, preserve learning and identity readiness | Per-family coverage and real-image regression, migration/restart/backups, 25k+ knowledge/300k library tests; character identity remains unverified until reference data |
| **P5 — External cohesion** | Control signed capability/diagnostic API; direct Art/Sound/3D/Writing/Game/KK/NoWorld asset actions and deep project browsing, no Control hard dependency | Real target capability/permission/output receipts, offline Control fallback, no fake connected indicators |
| **P6 — New abilities** | Native CJK translation, full private book index, semantic search, recommendations, smart collections, repair/archive/media conversion, automatic episode assembly/montage editing | Domain-specific actual output tests, offline/no-egress validation, source preservation, checkpointed failure recovery and acceptance |
| **P7 — Full data migration and release** | Dry-run and transactional import from three apps, catalog and hash reconcile, source app unchanged, full regression suite, signing and rollback | Owner-confirmed signed Nexus APK on Samsung, all acceptance matrix items, proven migration, no legacy retirement until approval |

Phases are *dependency order*, not permission to leave features unfinished. Thin compatibility and tests may precede their final phase, but cannot be claimed as full feature acceptance. The first deliverable is **P2's end-to-end vertical slice**, even if P3 shell work is staged concurrently.

## Source/adaptation tasks (no rebuild for its own sake)

- `M001`: Verify latest donor heads, installed APK identity, read-only branch/PR/CI and exact Rounder assembled project; record SHA, import licensing and source manifest.
- `M002`: Establish new stable Nexus Android package/application, build config, dependency/version lock and signing/rollback plan; maintain co-installability.
- `M003`: Import/adapt Downloader provider adapters, source index, queue, transfer service, torrent isolated process and tests; mark each true provider state; no dead facade.
- `M004`: Import/adapt Core Fusion SQLite and visual learner with existing data migrations and tests; no loss of taxonomy or manual ratings.
- `M005`: Import/adapt assembled Rounder cloud client/index/document provider/readers/player/organizer plus tests, preserving accepted behavior rather than UI.
- `M006`: Establish Nexus registry and per-donor legacy aliases, authenticated multi-account cloud, verified bytes/destination/partial state semantics; test migration idempotence.
- `M007`: Implement one actual federated global search and search-source provenance/partial failures; prohibit private-to-public query leakage.
- `M008`: Repair required selected-provider originals and complete cosplay album/selected torrent, source and phone tests; no substitution/fake acceptance.
- `M009`: Rebuild premium UI against real flows, with no slogans/dead controls and all reader gestures; screenshot and insets/accessibility gates.
- `M010`: Complete applicable-family native visual tagging/recognition and train validated missing heads; consistent performance/ratings/learning.
- `M011`: Add native confidential books index, per-format quality CJK translation, independently verifiable outputs and network-off operation.
- `M012`: Add native automatic Episode Assembly and montage timeline/output, segment evidence and unique-scene protection; verified processing on representative actual episodes.
- `M013`: Add corruption detection/repair, archive processing, semantic visual search, smart collections, recommendations and media conversion.
- `M014`: Add Control/specialist signed typed IPC, direct action path and Control orchestration compatibility; avoid duplicate engines.
- `M015`: Export/import actual owner-approved legacy records with before/after diffs and rollback; do not move originals.
- `M016`: Complete final release with exact signed APK, installed phone proof and owner acceptance.

## Independent validation: real work vs reported work

Evidence classes are ordered, not interchangeable:

```
SPECIFIED -> SOURCE_PRESENT -> SOURCE_TESTED -> BUILT -> PACKAGE_SIGNED
  -> INSTALLED -> DEVICE_EXECUTED -> OUTPUT_VERIFIED -> OWNER_ACCEPTED
```

For each feature, record actual source branch/commit, test case/fixture, platform/device, action, expected vs observed results, output file hash/size where applicable, elapsed time/resource budgets, failure details and owner acceptance decision. A source-level test is not a real network provider acceptance or real edited-video proof. Never show "done" from UI smoke alone.

## Release gates with failure consequences

**Gate A: security/source provenance.** All imported code and binaries have auditable license/origin; no secrets/media/keystores in public repo; no library data committed; negative permission tests pass.

**Gate B: build and package integrity.** Correct actual native Android release source, reproducible dependency versions, released ABI/model/codec files present and hash verified, one signer identity, no accidental old package conflict, no arbitrary debug key. Keep original APKs.

**Gate C: migration and data safety.** Backups/external references verifiable, no lost source IDs/owner corrections/cloud account distinctions/bookmarks, repeatable migration, rollback, originals unchanged; cross-account/duplicate evidence tests.

**Gate D: engine functional.** Every inherited working Rounder feature smoke-retested; Downloader exact provider originals, cosplay completeness, online search pagination and torrent publication tested; Core realistic tagging, resume, review and corrections; no fake online provider.

**Gate E: new capabilities.** Actual offline CJK outputs and private indexes, episode media assembly preserving unique scenes, independent output files/repeatable timeline, archive/repair/semantic search and creative integrations tested.

**Gate F: UI premium.** Absolutely no slogans anywhere in UI/screens/notifications; every visible action operational; consistent hierarchy/typography/color; no tiny/overlapping controls or unreadable text; real previews; all normal paths intuitive and accessible; reviewer watches real phone interactions and screenshots. A merely themed legacy UI fails.

**Gate G: sustained reliability.** Background resource limits under active playback/download, 300k+ indexed media, 25k+ knowledge, multi-account cloud, process-death recovery, WAN loss, storage pressure, thermal throttling, cancellation and no duplicated irreversible operations.

**Gate H: release/retirement.** Sign after evidence-based preflight, distribute **one signed Nexus APK per release** (multiple deliberate later releases allowed with fresh gates), verify installer/signing and run real phone acceptance. Original apps remain installed until **full Nexus phone acceptance** and explicit safe retirement approval.

## Concrete acceptance scenarios

| ID | Scenario | Evidence of success |
|---|---|---|
| `ACQ-001` | Source result -> selected exact original -> local verified | Correct content identity, readable true bytes, hash/length/duration |
| `ACQ-002` | Cosplay creator -> gallery -> originals | Expected distinct originals fully downloaded, no previews/ad substitution |
| `ACQ-003` | Torrent selected-file download | Peer transfer, piece integrity, safe final publication, seeding stopped |
| `SRCH-001` | Federated local/cloud/web query | Correct independent progressive results, provider outages displayed, no private query leakage |
| `CLOUD-001` | Two TeraBox accounts | Correct account isolation, previews/streams, verification and permission revocation |
| `DATA-001` | 3-app migration | Counts, ratings/tags/cursors/bookmarks, no lost originals, reversible import |
| `FUS-001` | Full visual family/subject tagging | Every applicable family evaluated; exact evidence and uncertain/unsupported reported |
| `FUS-002` | Owner correction -> training -> same file reprocess | Correction persists, retracts, no unwanted change to unrelated subjects |
| `READ-001` | EPUB/PDF/comic and cloud video | Accepted Rounder mode semantics, bookmarks, streaming, aspect and seek |
| `TXT-001` | Every supported CJK book/comic auto-translate offline | Quality output, source intact, no network access, accurate edition/index |
| `IDX-001` | Imported book local full-text query | Complete correct chapter/page text retrieval and no public egress |
| `VID-001` | 3-episode continuous assembly | First OP/last ED retained, redundant intermediate OP/ED/recap/previews cut, all unique scenes retained |
| `VID-002` | 10+ episodes, unusual end cards, interrupted render | Correct sorting, story preservation, verified seek/play, resumption |
| `ASSET-001` | Direct signed Art/Sound/3D/Writer asset action | Target actually executes, output hash and project identity verified |
| `CTRL-001` | Control offline while Nexus runs | Local/cloud library, supported native acquisition, Fusion/media, phone-native translation and episode assembly remain functional |
| `UI-001` | Premium first use/normal workflows | Slogan-free every screen, responsive, accessible, no dead actions, owner visual acceptance |
| `REL-001` | Signed co-installable APK on phone | Existing apps/data untouched; Nexus real functional regression/rollback |

A failing mandatory test blocks release of the affected feature and any overall "fully complete" claim. Other independent tested features may continue under truthful feature flags; avoid repeated fake full-release claims.

## Operational status as of specification commit

- **Specified:** this repo documents owner-approved scope.
- **Source integrated:** NO (Nexus was empty before this work).
- **Android build:** NOT RUN.
- **Nexus APK signed/installed:** NO.
- **Migration:** NOT RUN.
- **Full regression and owner acceptance:** NOT RUN.

Do not increment those states without actual evidence. Preserve original apps and their development branches throughout.
