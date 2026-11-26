# Legacy migration, canonical registry, TeraBox and library

## Non-destructive migration contract

Nexus is **newly co-installable**, not an in-place upgrade of `com.ailm.android`, Downloader or Rounder's `com.openlocal.office`. Android app-private stores are sandboxed. No migration claim may rely on direct access to another package's private data. Use a legacy app's explicitly authorized export/provider, owner-granted SAF access, signed migration helper with permission, or independently accessible backup. If none exists, record `UNAVAILABLE_REQUIRES_SOURCE_APP_EXPORT` and preserve the source unchanged.

### Included and excluded by owner decision

| Donor | Migrate into Nexus | Never assume |
|---|---|---|
| Downloader | Provider config, source registration, creator follows/subscriptions, completed catalog/history, exclusions, safe backlog/cursors and safe queue history | Failed/in-flight side effects are safe to repeat; credentials may be copied into Git |
| Core | Fusion SQLite taxonomy, canonical IDs, aliases, multi-subject observations, ratings, owner corrections/retractions, learning evidence, processing/review history, validated character knowledge | Untested character identities are recognized; a stale model/head may classify accurately |
| Rounder | TeraBox indexes and cloud account mappings, bookmarks/reading positions and accepted library metadata | Cloud account tokens can be merged; indexed file remains readable without validating account/generation |
| Existing physical files | **Preserve in place**, attach verified URI/location references when accessible; move only under separate owner-requested action | Bulk moving/renaming original media or rewriting only copies is authorized |

Migration phases: (1) preflight catalog and source counts, (2) cryptographically verifiable backup/export where allowed, (3) deterministic ID mapping with source aliases, (4) target dry-run report, (5) transactional import per domain, (6) count/hash/relation diffs and representative reopen, (7) interruption-resume/repeat migration without duplicates, (8) rollback/export proof, (9) human acceptance. Missing a source does not wipe other work. Account sign-in is re-established securely where direct secret migration is unavailable. No passwords/tokens stored in migration logs.

## Registry data model and invariants

- `content_item`: stable Nexus ID, media class, verified metadata, optional content SHA-256 and physical bytes, existence state, origin timestamp. SHA identifies byte equality, but multiple logical copies/roles can still exist.
- `content_location`: item ID, `LOCAL_SAF|MEDIASTORE|TERABOX|OTHER_CLOUD|TRANSIENT`, account namespace, URI/remote ID, path, cache policy, byte-range/readability state, last-verified receipt and generation.
- `source_item`: canonical source provider, exact external source entity, creator/album/episode identifiers, URL identity, access provenance, selected-original matching evidence; never treat thumbnails or ad links as originals.
- `content_alias`: legacy app name, legacy key, Nexus ID; supports repeatable migration without ID loss.
- `content_relation`: OPTIONAL typed relationships (`same_bytes`, `episode_of`, `derived_from`, `translated_from`, `project_asset`, `related_character`, `same_creator`, `alternate_edition`), reversible and evidence-backed.
- `fusion_observation`: references Core canonical IDs and per-subject coordinates/scope; tags, ratings, corrections, model/version, acceptance evidence; keep underlying Fusion-owned tables authoritative, not a competing word-tag database.
- `transfer_receipt`: source, selected item, byte count, hash, destination, remote size/identity/existence and actual readback; queued/ACK is not transfer proof.
- `job` / `job_transition`: durable task IDs, lease/owner, dependencies, status/attempt/cancellation, failure taxonomy, output refs and verified postconditions.
- `book_text_index`: encrypted local full-text index for all imported books, versioned source/edition identity, page/chapter/offset map and access scope; never a public/global search upload.

Use indexed SQLite WAL with atomic versioned migrations and backups. Test 300,000+ media entries, multi-account source collisions, pagination and queries without linear full rescan of all content. Preserve provider-specific arbitrary metadata without sacrificing the canonical minimum contract.

## TeraBox-first and multi-account storage

- Support **multiple isolated TeraBox accounts**; each has distinct credential authority, root namespace, cache keys, indexes, refresh schedule and search result provenance. The local UI can aggregate metadata but never confuses account ownership or uses a token with the wrong namespace.
- Build on Rounder's *actual assembled* `FgTeraboxClient`, `FgTeraboxDocumentsProvider`, `FgTeraboxSearchIndex`, progressive media range source and consent-scoped Control inventory. Preserve SAF user grants, browsing, readers, cloud folder operations and metadata generation tracking.
- Account authentication and model/asset staging must not reveal tokens, content URLs or private file listings to another app without a signed bounded grant; requests to Control do not transfer cloud secrets.
- Primary operation is cloud streaming with adaptive previews/range cache; owner-selected items may be pinned offline. Maintain bounded per-media cache, eviction rules, and never silently evict deliberately pinned offline files.
- Revalidate remote originals for checksum when available, actual size, existence/readability, completed upload identity, bounded readback and account identity before recording `REMOTE_VERIFIED`. When a cloud provider cannot expose authoritative checksum, record the weaker exact evidence, not fake an SHA.
- **After real remote verification, present an explicit local cleanup proposal**. No automatic removal; no cleanup for sole remaining copies, incomplete cloud transfer or expired authentication.
- Credentials protected via OS keystore/encryption; per-account revocation and index invalidation. No raw sessions or customer file paths printed in public CI or repository fixtures.

## Global federated search and privacy

Run simultaneous bounded independent queries against:
1. Nexus local file and Fusion semantic/metadata/visual indexes;
2. every connected authorized TeraBox account or future cloud adapter;
3. enabled internet provider discovery adapters.

Show progressive results by source, relevance, confidence and availability, with sort/filter/media/source controls. One provider failure must not cause a false global "zero results" nor block other providers. Deduplicate only when identity evidence exists; label same-title distinct assets correctly. Local book query terms, user private text and filenames must not be sent to a public site without explicit owner selection/consent; federate by **query scope**, not by leaking a private full-text lookup to web. Search must stay responsive during incremental index rebuilds.

## Exact duplicates, versions and reversible actions

For exact verified byte-identical files, automatically consolidate duplicate **logical identity** and maintain a versioned set of independent locations/paths and all provenance. Reversible history permits restoration of prior catalog and file mappings; it does not imply silent deletion of bytes in cloud/local storage. Near-duplicates remain suggestions for manual review. Derived/edited/translated media are independent files by default; links optional. Every potentially irreversible deletion, overwrite, shared cloud action or export with destructive side effects requires confirmation. Archive originals/legacy databases remain safely recoverable.

## Recovery and repair

Periodic non-destructive integrity verification, corrupt-file detection, archive structure checks, missing range repair if exact trustworthy bytes can be obtained, safe fallback to another verified copy, incident reports and manual escalation when content is unrecoverable. No silent corrupted-file "repair" that changes meaning or metadata without an independent new output and validated diff. All file moves/copies/uploads use staging and postcondition verification, then bounded cleanup.
