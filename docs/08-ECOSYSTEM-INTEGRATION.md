# Asterion ecosystem responsibilities and signed interfaces

## Top-level boundary

**Nexus** owns acquisition, file identity and provenance, multi-account library, media reading/editing/translation, Fusion visual understanding, private book indexes, asset relations and direct user-authorized asset handoff.

**Asterion Control** owns the independent general assistant, automated developer and system workflows, permissioned shared browser, device/PC tools, cross-app diagnostics, resource coordination and **complex multi-app orchestration**. Control's latest independent/tailored AI ambition is broader than a tiny on-device LLM; Nexus must not copy or constrain that roadmap. The Control specs are evolving independently: consult current `Azrazieliz/Asterion-Comander` implementation and newer owner-approved Control architecture before invoking an unverified endpoint. Its V3–V5 November 15 contracts describe tool registry, tasks/cycles, signed IPC, shared browser, deeper file security, model laboratory and no duplicate partner engines; a design contract is not proof of an installed service.

**Specialist apps** own production/analysis engines and project authority. Nexus must never "implement" those engines by duplicating code or stealing responsibility just to show a connected icon.

## Two connection modes

1. **Direct Nexus -> specialist**: one owner-authorized, signed, versioned request with a finite scope and receipt; available independently from Control. Examples: open a selected image in Art, request a defined preview/asset variant, write an approved Nexus output to a project through a verified partner provider.
2. **Control-orchestrated multi-app workflow**: Control forms a multi-step plan with execution/diagnostic authority, requests Nexus file references and stages, coordinates Art/Sound/Foundry/etc. generation, verifies each step and returns result. Nexus **never needs Control** to fulfill its own ordinary gallery, downloader, torrent, translation, editor or cloud operations.

Control's browser is the shared **general browsing tool**. Nexus retains adapter-specific extraction/search/download/rate/cursor/verification authority. When Control is unavailable, native acquisition modules and supported library functions must remain usable; enhanced JS-heavy session features report actual unavailability, not fictitious success. Control's deeper malware analysis enhances but is not the sole Nexus safety check; no purported clean verdict on incomplete scans.

## Versioned capability and safety contract

Every partner interface exposes a signed/app-certified capability descriptor with method versions, media types, permission scopes, build/runtime availability, size budget and failure reasons. Every invocation includes:

```json
{
  "contract_version": "asterion.asset-action.v1",
  "request_id": "stable-unique-id",
  "caller": "signed-package-identity",
  "target_capability": "capability-id",
  "owner_approval_scope": "scoped-existing-approval-id",
  "expires_at": "ISO-8601",
  "asset_refs": [{"nexus_id": "opaque", "content_hash": "sha256-if-known"}],
  "parameters": {"versioned": "typed"},
  "idempotency_key": "stable-key"
}
```

Results carry `RECEIVED|RUNNING|VERIFIED|FAILED|PARTIAL|UNSUPPORTED|PERMISSION_DENIED|OUTCOME_UNKNOWN`, immutable typed output references, expected vs observed checks, timestamp, source/version and trace id. This is an illustrative logical shape; use schemas in `contracts/` for validation, not arbitrarily force an existing app to implement a fake method. Reject unknown breaking versions, authenticate caller UID/signing cert and owner session, give least-privilege temporary URI access, never transmit raw credentials/keystores/private logs. Reconcile uncertain side effects instead of repeating a dangerous request.

Android Binder/ContentProvider handles small signed control messages; large media uses time-bounded SAF/ContentProvider/secure stream access, bounded chunks with real byte receipts, not multi-MB IPC extras. Allow per-app revocation, audit and permission expiry. Support backpressure, reconnect, cancellation and idempotent output destinations. Shared trace IDs connect evidence without exposing private file paths to inappropriate consumers.

## Integration-specific responsibilities

| Partner | Owner authority | Nexus views/actions | Required boundary |
|---|---|---|---|
| **Art** | Image/video creation, character/style sheets, edits/inpainting/upscaling | Rich reference/project browsing, send approved source assets, retrieve generated versions/metadata, index outputs and launch verified action | No Nexus image generator clone; do not confuse source references with produced art |
| **Sound** | Music, voices/dialogue and SFX creation, editable audio outputs | Browse tracks, reuse voice/sound/project assets, request bounded soundtrack/voice operation and reference output in video timeline | Nexus video editor owns timeline; Sound owns synthesis |
| **3D Foundry** | 2D->3D reconstruction, geometry, material/rig editing, quality and export | Browse 3D assets, references, textures/GLB/manifest versions, request verified conversion and attach outputs | No fake rig/NoWorld exact-identity claims |
| **Writing** | Story planning, original text inference, chapter revisions, canon editing/quality | Persistent private text/books/chapter library, story projects, ISBN/EPUB, reading and local search, approved publish/download receipts | Nexus owns books/archive, Writing owns creative AI; no auto-publish |
| **Game Pilot** | Direct live gameplay and task mastery | Game resources, per-title media packs, allowed asset recommendations and scoped import | No game-playing engine inside Nexus |
| **Game Codex** | Game mechanics, roster analysis, provenance, rankings and metadata | Project-aware indexed game knowledge/media, correct visual asset references, browse/read accepted packs | Cannot rewrite Codex authority or rankings |
| **KK** | Actual installed Koikatsu runtime/game/assets and mods | Versioned character/scene/mod inventories, card preview, verified TeraBox staging and reference handoff | Basic startup files remain in KK where required; other assets stream when supported; never copy commercial game content into public Git |
| **NoWorld** | Unreal world state, exact character identity/version/manifestation, source asset import gates | Verified resource/package manifests, source hash, GLBs/textures/motion references, reuse suggestions, invoke only accepted import contract | Generic GLB != Unreal acceptance; do not invent compatible rig/bones |
| **Control** | General AI, shared browser, devices/PC, diagnostics and orchestrated chains | Capability/health/trace reports, shared browser task, verified file/security checks, staged assets | No hard dependency; signed, auditable, truthful availability |

Adaptive Study remains **excluded by explicit owner direction** from the current Nexus architecture audit. The future combined Art/Sound/3D/Writing creator app documented by Writing is separate from the Core+Downloader+Rounder merger.

## Asset/project cross-referencing

Nexus's library can index:
- File hashes, media/format, source URI, type/category, creator/character/series, project/version references, access permissions, remote/local availability and validated import status.
- Specialist-owned project descriptors and **read-only summaries** under signed scopes; search can surface "used in Art project", "reference in NoWorld asset", "KK card variant", without taking ownership of private project state.
- Direct actions: open in app, import selected asset, request specific specialist output, compare versions, return verified asset; show real target app capability and error, not a generic "integrated" card.
- Recommendations: suggest actual existing assets or relations with explainable evidence and privacy-preserving indexing; not uncontrolled transfer of user files.

## Integration acceptance matrix

1. Identify target installed package/version/signer and discover capabilities; absent provider must appear `UNAVAILABLE`, not connected.
2. User grants precise scope; request travels through signed interface, media accessible only for approved lifetime.
3. Target performs a real operation; output is inspected/read by Nexus with correct size/hash/identity.
4. Revoke permission; subsequent operation must fail closed and previously delivered authorized outputs remain properly attributed.
5. Simulate target process death, Control disconnection, duplicate request and network loss; recover without double exporting/creating/deleting assets.
6. Verify existing Control private bridge is not replaced or reimplemented unnecessarily; no coupling to unfinished AI inference.
7. Specialist UI quality is independent; Nexus must remain premium even when launching an external app's currently inferior UI.
