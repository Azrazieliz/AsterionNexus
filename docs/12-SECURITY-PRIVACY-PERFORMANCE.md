# Privacy, security, resource budgets and operational resilience

## Security model

Nexus is a powerful file/media system with cloud sessions, local private books, optional adult media and executable archives. Trust boundaries: untrusted provider pages/downloads; cloud account/API responses; local SAF grants; native codecs/parsers/archives; model artifacts; other Asterion app requests; user-approved file mutation. Treat all retrieved HTML, file metadata and model outputs as untrusted data rather than commands.

The repository is **public source only**. Asterion Nexus must not ship, commit or log personal library files, private TeraBox/cosplay credentials, tokens, signing keys, private story drafts, proprietary Koikatsu game files, paid media, full user books, original recordings or unreleased licensed model weights. Unit tests use synthetic fixtures, explicit known-license/public-domain files or owned test data locally off-Git.

## Access and permission policy

- Runtime least-privilege: Android SAF/MediaStore selected roots, scoped content-provider URIs and explicit user approval for legacy data import; no assumption of access to another package's private data or OS bypass.
- Each TeraBox/cloud account uses isolated session/credentials/index/cache; encryption at rest via Android OS-backed credential storage, bounded permissions and revocation. No cookies in debug logs or cross-provider HTTP clients.
- Cross-app API uses verified signer/UID, capability version, owner-scoped action authorization and expiry, idempotency, explicit permissions, provenance and auditable receipt. No arbitrary command execution via foreign app intents.
- Default file operations reversible; irreversible delete/overwrite/external publish requires owner confirmation, including when part of automation. Reversible exact-duplicate logical merge is allowed; automatic byte deletion is not.
- Nexus's own essential quarantine/preliminary archive checks must run when Control is offline. Control may independently deep-scan through an authenticated action; do not interpret antivirus unavailability as "clean".
- Handle untrusted binary/archives: limit recursive decompression, path escapes, sparse files, resource exhaustion, malicious decoders and dangerous filenames; never automatically run downloaded executable or game installer.
- Licensed media/source and provider account restrictions are respected; identity/login/age/anti-bot challenges are not assumed bypassable. Report blocked access truthfully.

## Private book and visual indexing

- Full-text imported books are indexed **locally and privately**, with authenticated settings to inspect/delete index and documented retention. Do not send text to web provider federated search, third-party LLM endpoint or public Git repository. Track per-book source and document/edition permission.
- Fine-grained image/tag/quality preference learning stays private by default; only explicit approved partner references may be transmitted, with clear content scope.
- A model or media asset staged from TeraBox has exact verified local bytes before runtime load; remote index entry alone is not model readiness.
- User can clear cache separately from retained file pins, undo/history and imported content. Deleting an index must not delete the source book or its user bookmarks.

## Authenticity and integrity

Every mutating side effect records: authorized actor/scope; source content ID, verified hash/size if available, destination, expected vs observed state, operation version, idempotency key, parent/child job, timestamp, explicit security verdict class and rollback metadata. Unknown result must not be retried as a new operation without checking remote/local state first.

When remote TeraBox has no reliable checksum API, use verified size/readback/ranges/locators and mark checksum **not available**, never forge it. A complete internal copy/upload receipt does not itself establish playback, remote access or end-to-end acceptance.

## Resources and Android target

- Primary target owner Samsung SM-S948B, Android 17, 1080x2340 screen and roughly 11.6GB physical RAM (historical snapshot; must re-measure at runtime). Current free RAM and thermal/battery conditions vary. No unvalidated fixed throughput/power assumption.
- Prioritize **foreground browsing, touch, readers and playback**, then active transfers, then background Fusion/translation/indexing/video analysis. Owner-selected active video assembly jobs may consume a larger resource budget when the user is not actively viewing.
- Central native resource governor for CPU thread pools, GPU/Vulkan and NPU/QNN runtime only where measured, memory allocations, codec instances, disk cache, network requests, native torrent service and phone thermal/charging state.
- Avoid two heavyweight models resident simultaneously unless hardware tests prove feasible. Use few shared specialized models plus validated bounded heads and unload inactive modules; no dependence on a "tiny generic AI" to replace Fusion.
- Android WorkManager/JobScheduler/foreground service rules for Android 17, explicit permissions and notifications, bounded concurrency, checkpoints, resume semantics, no permanent low-power wakelock or private permission bypass.
- No unbounded multi-account index query, full library scan, decrypted book text in log, complete cloud-library mirroring or expensive model initialization for ordinary file-opening.
- Declare limitations accurately: a benchmark on a PC does not establish Samsung performance; a registered source adapter does not establish a working provider; a compiler feature flag does not establish actual Vulkan/QNN execution.

## Recovery and failure budgets

Fault injection scenarios: app kill during download, cloud upload, tag database transaction, translation and media render; wrong/expired TeraBox account; storage full mid-copy; missing model; source original altered; network changes/permission revocation; checksum mismatch; native decoder crash; resumed work after reboot. Every failure must leave original data unchanged or recoverable, with accurate durable job state.

No false "complete" state on missing output, unsupported native media, stale account generation or skipped step. Degraded paths stay functional: offline local library and accepted cached content can be read when Control/web/TeraBox are unreachable; Control's independent assistant outage does not take down Nexus.

## Performance and UX verification

Measure launch-to-real-library, scroll frame time/jank on dense galleries, TeraBox first meaningful preview, 300k local index search with concurrent loads, decode start/seek, changing accounts, memory peak, available storage and sustained battery/thermal impact. Log redacted numeric metrics rather than user's filenames/contents. Set release quality targets based on a profiled baseline and actual owner-approved device capability, not invented guaranteed latency.

Privacy, security and performance tests are inseparable from premium UI: a pretty grid backed by unresponsive/unsafe operations is not accepted.
