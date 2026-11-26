# Acquisition engines, source providers, federated search and torrent repair

## Merger rule: preserve original engines, repair failures in Nexus

Import the actual Downloader Kotlin/Gradle implementation, not a new "generic everything" crawler. Include the original GalleryFlow/Kyora adapters from both `AsterionDownloader` and `Cosplay-Download`; restore functionality with provenance tests and selected-original verification. Preserve Pixiv follows, X following, creator profiles, galleries, novel/EPUB, games/VN/resource downloads, video flows, archives, card/asset intake, site subscriptions, sync/backfill and full transfer history. Retired/inaccessible providers are not silently replaced by an unrelated provider.

The owner explicitly declares **provider acceptance, cosplay downloads, global search and torrent downloading nonfunctional enough to require Nexus-level correction**. Unit tests, site-page HTTP 200, search result thumbnails, byte-range responses, downloaded advertisements, renamed HTML or "queued" status are **not acceptance**.

## Source adapter model

Each provider adapter exposes independently:
- Capability discovery: site/domain/URL ownership, source category, supported auth/recency/creator/galleries/pagination, expected media, evidence confidence and rate/terms restrictions.
- `resolveSource`: source-native identities and original canonical URL.
- `search(query, filters, cursor)`: actual results with stable result identity, provenance and incremental pages, not site-launch icons.
- `listCreator`, `listAlbums`, `enumerateAlbumOriginals`: exact creator -> albums -> original media with expected count and duplicate-aware pagination.
- `resolveSelectedOriginal`: selected exact item->original route, authorization requirements, expected media metadata; permit same-item authorized publisher mirror, never substitute a different title/video.
- `transfer`, `verify`, `resume` and `cancel`: real byte-level operations with normalized failure reason, hash/duration/media verification and unique completion receipt.
- `sync` and independent `backfill`: distinct persisted cursors, bounded retries, stop and pending work, no false completion when a gallery's last page is missing.
- `diagnostics`: exact stage, HTTP status where permitted, URL origin (redacted secrets), parser version, selected-media identity, retryability and owner-dependent access requirement.

A shared browser can be invoked through **Control's optional signed bridge** for JavaScript-heavy pages and site-authorized session navigation. However, Nexus must not require an always-running Control to browse its local/cloud library or perform supported native transfers. Maintain a first-party compatible internal browsing/HTTP acquisition path where necessary; do not copy Control's general-purpose assistant or claim that its current HTML-fetch helper is full browser automation.

## Provider inventory, failure hypotheses and acceptance

Treat each row as **open until real source-specific proof**, even if source code exists:

| Provider group | Required exact output | Known concern/acceptance |
|---|---|---|
| Pixiv / creator following | Selected original image set, correct versions/artist/cursor | Credited artwork, no previews substituted; Live sync and Backfill independent |
| Cosplay: Buondua, Kiutaku, FourKGirl, Everia, 4KHD, CosplayTele and supported approved additions | ALL actual original photos of selected complete gallery | HTTP403/auth/listeners, empty covers, pagination, original count, stable gallery identity; native six adapters remain, not missing |
| Adult/video catalog sources | Complete selected original video and right duration/codec, not player HTML/ad | JAVIDEO unresolved player, HelloPorn VOE access, MP4Hentai hoster/captcha, other sites with past partial/full PC proofs; confirm current behavior independently |
| General videos / video search | Search exact site plus full selected video | Eporner/HQPorner/FullPorner selected stream issues, preview vs original, session and referer requirements |
| Novel/EPUB/comic sources | Verified complete book/volume/archive | Source/volume ordering, filenames, cover and readers |
| Games/assets/Koikatsu resources | Correct selected package and identity | Licenses, source provenance, appropriate preview and dispatch |
| X creator following/media | Actual current follow state and selected media | Do not import unfollowed accounts as followed |
| Archivebate and related archives | Exact selected recording | Old user-reported wrong routes or auth gates must be re-proven |
| Torrents/magnets | Piece-verified selected file bytes, publication/consumption | Native libtorrent isolated service, descriptor/metadata bounds, quarantine/release logic and actual download failures |
| Global online search | Real matching results and working click-through to exact item | Pagination/provider isolation, no synthetic hit padding, truthful partial failures |

The former seven-provider source gate recorded some **PC-only full-file successes** and remaining external access blocks; the November 26 Downloader emulator simulation failed. Current Android acceptance is a **separate** gate. A blocked login, host unreachable, CAPTCHA, policy/region or upstream inaccessible file must be named as such, not "fixed" via speculative HTML selectors or bypass promises.

## Verified transfer state machine

```
SOURCE_DISCOVERED -> SELECTED -> SOURCE_RESOLVING -> AUTH_REQUIRED?
  -> ORIGINAL_IDENTIFIED -> TRANSFER_QUEUED -> TRANSFERRING
  -> BYTES_COMPLETE -> CONTENT_VERIFIED -> SECURITY_REVIEWED?
  -> LOCAL_PUBLISHED -> CLOUD_UPLOAD_PENDING -> REMOTE_VERIFIED
  -> INDEXED -> AVAILABLE
```

Failure states are orthogonal: `USER_ACTION_REQUIRED`, `AUTH_DENIED`, `NETWORK_UNREACHABLE`, `RATE_LIMITED`, `PLAYER_UNRESOLVED`, `ORIGINAL_UNVERIFIED`, `UNSUPPORTED`, `CANCELLED`, `TIMED_OUT`, `INTEGRITY_FAILED`, `QUARANTINED`, `OUTCOME_UNKNOWN`. Never mark `AVAILABLE` from queue submission or UI selection. Partial/multipart archives maintain per-item and per-gallery expected/actual counts.

## Downloads and transfer operations

- Real pause/resume/stop, cancellation isolation per source, retries only after prior side effect reconciliation, persistence across process death/reboot as permitted by Android.
- Live sync and Backfill remain independent controls, including per-site scheduling, cursors and opt-in source scopes.
- Stable source/creator IDs, selected-original URLs, final byte counts/hashes, resume bytes, filename/content-type validation, duplicate checks, destination records, signed receipt and actual file reopen.
- Parallel independent providers must never share login cookies without explicit owner-granted same-provider session; race/resource budgets and fair scheduling prevent starvation.
- Support direct-to-TeraBox upload only after local verified source and remote integrity semantics; when streaming/copying through temporary storage, bound and safely clean the cache after remote evidence and owner cleanup policy. Never claim a remote upload from a dispatch ACK.
- File-security policy: safe source types, bound archive recursion, executable and package scan when possible, surface risk and uncertainty honestly; no implicit execution of downloaded binaries.

## Torrent completeness (distinct from media extraction)

Import `TorrentTransfers.kt`, `TorrentTransferService`, `TorrentMetadataBridge`, `TorrentQuarantineGate`, `TorrentVerifiedExport.kt`, `MultiTorrentSearch` and their tests from the current verified donor, then reconcile with file registry and Control optional scanning. Preserve selection of files within a torrent, extensions/MIME, magnet metadata, tracker and resume state, independent native-process recovery.

- Support legal/test torrents in automated acceptance. Verify metadata provenance, selected piece hashes, full per-file byte/length, storage availability, bounded descriptor/metadata fetch, cancellation and actual MediaStore/SAF output presence.
- Security review (Nexus essential checks even without Control) precedes safe publication; if review is insufficient, report incomplete/hold and request authorization rather than falsely claiming malware-free.
- User setting: **STOP seeding automatically after verified selected download completion**; prohibit silent background seeding. Mark `COMPLETED` only after native transfer+integrity and required publication/postconditions.
- Tests: magnet and .torrent inputs, one known-good seeded test source, selected-file subsets, extensionless thumbnails, metadata-timeout, slow peer, interrupted/resumed download, multi-file path safety, process crash, export retries, duplicate export and no unauthorized executable launch.

## Global search scope and online query privacy

One input can simultaneously search local titles/tags/visual evidence/books, each cloud account's metadata/index, and public source indexes **only with approved query scope and provider configuration**. Private book text, local paths, account identities, explicit personal notes and source credential data must never be submitted to an online provider by query federation. Show source badges/labels when materially informative (not fake status badges), progressive counts and per-provider failures. Results selected from web route through verified acquisition, never an unvetted download manager.

## Definition of done: acquisition

Provide trace for one exact selected source item showing source identity -> original resolution -> all expected bytes -> local or cloud readback -> searchable library item -> playback/open, then restart application and reopen the item. Repeat for each required failing provider with representative authorized sources; accept source-access blockers only with actual network/auth evidence, not inflated success claims. Validate cosplay album counts and a complete torrent separately. Require real phone evidence, not a generic JVM/CI green status.
