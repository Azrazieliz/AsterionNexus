# Donor baselines, decisions, external sources and evidence limitations

**Evidence cutoff:** source checks performed in the owner conversation on 2026-11-26. These SHAs are *audit anchors*, not permanent prohibitions against importing newer work. Before implementation, discover newer donor commits and review exact installed versions.

## Current known donor source baselines

| Donor | Source branch / SHA | Verified facts and limits |
|---|---|---|
| Core | [`recovery/core-v2100-gaps-20261118`](https://github.com/Azrazieliz/Asterion-Core/tree/recovery/core-v2100-gaps-20261118), `f453a9d5defd14dbb7fa9fcef107f99766bd65d1` | 62 commits ahead of earlier Nov18 baseline; [CI 45435993242](https://github.com/Azrazieliz/Asterion-Core/actions/runs/45435993242) built successfully, produced **unsigned** 383MB-ish artifact; signed release skipped. Not evidence of signed installed device acceptance or every image family working. |
| Downloader | [`repair/downloader-global-search-20261121`](https://github.com/Azrazieliz/AsterionDownloader/tree/repair/downloader-global-search-20261121), `71f38b91af637bbb0aed74906eea2a92fbb17d03` | Nov26 source changes for torrent safety/search pagination and emulator lane; [source preflight](https://github.com/Azrazieliz/AsterionDownloader/actions/runs/45791873394) passed at prior head; [emulator transfer workflow](https://github.com/Azrazieliz/AsterionDownloader/actions/runs/45786392976) failed. Owner says provider/cosplay/search/torrent acceptance remains bad. |
| Rounder | [`feat/control-recursive-inventory-20261122`](https://github.com/Azrazieliz/AsterionRounder/tree/feat/control-recursive-inventory-20261122), `d6c9a1725a3fdc91d8e325625c25395b5e3962c4` | Functional non-3D usage accepted by owner; UI to be replaced. The signed Control inventory source is present. Exact latest accepted **assembled** Android source and installed APK identity need verification; source is not conventional main tree. |
| Control | [`work/control-ai-core-20261115`](https://github.com/Azrazieliz/Asterion-Comander/tree/work/control-ai-core-20261115), `a4695d541b2637380363686984b48994e19cd241` | V3–V5 describe general tailored AI, browser, tool registry, deep diagnostics, security and cross-app IPC; source helpers exist, but features differ in runtime readiness. Additional Nov26 independent Control AI owner direction must be reconciled against live Control project before bridge implementation. |
| Nexus | [`Azrazieliz/AsterionNexus`](https://github.com/Azrazieliz/AsterionNexus) | New public repo, empty before canonical docs; no source integration or APK claimed. |

## Primary specification sources

- [Old two-way Nexus merger](https://github.com/Azrazieliz/AsterionDownloader/blob/main/docs/production/ASTERION_NEXUS_MERGE_SCOPE_2026-11-08.md): valuable original product/transfer/migration responsibilities; **superseded** regarding two-way scope, Downloader as permanent production repository, Core only external, five rigid top-level nav and prohibition against UI redesign.
- [Old offline translation contract](https://github.com/Azrazieliz/AsterionDownloader/blob/main/docs/production/ASTERION_NEXUS_INTERNAL_TRANSLATION_CONTRACT_2026-11-11.md): preserve CJK->English, offline first-party model/OCR/format quality, verification and non-destructive derived outputs; **superseded** in full-text indexing (now required), PC optional execution (now prohibited), image OCR (now books/comics only) and owner automatic triggers (now all eligible supported books).
- [Core canonical Fusion and Gallery](https://github.com/Azrazieliz/Asterion-Core/blob/recovery/core-v2100-gaps-20261118/docs/production/ASTERION_CORE_FUSION_NATIVE_GALLERY_REBUILD_REQUIREMENTS_2026-11-08.md) and [ordered tags](https://github.com/Azrazieliz/Asterion-Core/blob/recovery/core-v2100-gaps-20261118/docs/production/ASTERION_CORE_CANONICAL_TAGGING_ORDER_2026-11-18.md): preserve live Fusion semantics and latest implementation; old Core UI blueprint is **not** Nexus final UI.
- [Rounder 7-star authority](https://github.com/Azrazieliz/AsterionRounder/blob/feat/control-recursive-inventory-20261122/docs/production/PROJECT_7STAR_AUTHORITY.md), [Rounder acceptance ledger](https://github.com/Azrazieliz/AsterionRounder/blob/feat/control-recursive-inventory-20261122/docs/production/ADVANCEMENT_PROGRESS.json), [Rounder assembly workflow](https://github.com/Azrazieliz/AsterionRounder/blob/feat/control-recursive-inventory-20261122/.github/workflows/openlocaloffice-build.yml): preserve real media semantics/engines/credentials/signer, but the owner has expressly authorized a totally new Nexus UI.
- [Downloader provider failure taxonomy](https://github.com/Azrazieliz/AsterionDownloader/blob/repair/downloader-global-search-20261121/docs/diagnostics/PROVIDER_RELIABILITY_AND_FAILURE_TAXONOMY_2026-11-16.md), [seven selected-original PC gate](https://github.com/Azrazieliz/AsterionDownloader/blob/repair/downloader-global-search-20261121/docs/diagnostics/SEVEN_PROVIDER_RELEASE_GATE_2026-11-18.md), [nine/eleven site checks](https://github.com/Azrazieliz/AsterionDownloader/blob/repair/downloader-global-search-20261121/docs/diagnostics/NINE_PRINCIPAL_PROVIDERS_REAL_ACCEPTANCE_2026-11-17.md): stage-specific truth and blockers; older site status may be superseded by later code/owner tests.
- [Original cosplay adapter architecture](https://github.com/Azrazieliz/Cosplay-Download/blob/feature/kyora-0.7-flat-terabox-15min-20261023/docs/ARCHITECTURE.md): actual provider identity, album completeness and FlowLink contracts. Current Downloader already embeds adapted Kyora source.
- [Control V3 system](https://github.com/Azrazieliz/Asterion-Comander/blob/work/control-ai-core-20261115/docs/CONTROL_V3_COMPLETE_DESIGN_NO_CODE_20261115.md), [Control V5 browser/reuse](https://github.com/Azrazieliz/Asterion-Comander/blob/work/control-ai-core-20261115/docs/CONTROL_V5_USAGE_GEO_BROWSER_REUSE_12000_EVAL_20261115.md), [Control signed IPC](https://github.com/Azrazieliz/Asterion-Comander/blob/work/control-ai-core-20261115/docs/CONTROL_CROSS_APP_API_V1.md): reflect actual tool and diagnostics contracts, maintain independent products.
- [Art project](https://github.com/Azrazieliz/AsterionArt), [Sound](https://github.com/Azrazieliz/Asterion-Sound), [3D Foundry](https://github.com/Azrazieliz/Asterion-3d), [Writing Nexus contract](https://github.com/Azrazieliz/Asterion-Writing/blob/main/docs/INTEGRATION_CONTRACT.md), [Game Pilot](https://github.com/Azrazieliz/Asterion-Games/blob/main/docs/CANONICAL_PURPOSE_AND_ARCHITECTURE_LOCKED.md), [Game Codex](https://github.com/Azrazieliz/Game-Central-Database/blob/main/docs/PRODUCT_RULES.md), [KK](https://github.com/Azrazieliz/Asterion-kk) and [NoWorld](https://github.com/Azrazieliz/NoWorld-False-Horizon): inspect partner-specific canonical identities/permissions and update their reference maps before implementing direct actions.

## Conflict register

| ID | Historical requirement | Later approved owner correction | Nexus resolution |
|---|---|---|---|
| `C01` | Downloader + Rounder merger, Core external | Core + Downloader + Rounder plus more | Three integrated donors, Core Fusion internal |
| `C02` | Downloader repo is final host | New `AsterionNexus` repo exists and selected | Nexus is final repository; downloader may provide initial project source |
| `C03` | Preserve Rounder and Core old interfaces | All old UIs were bad | Rewrite shell/design; retain supported behavior and data |
| `C04` | Five fixed nav tabs | Adaptive intelligent library and one contextual workspace | Flexible contexts, no rigid inherited tabs |
| `C05` | CJK full-text indexing forbidden | Full **private** indexing of all imported books approved | Index locally with owner security and no third-party egress |
| `C06` | Optional PC translation acceleration | Phone-native offline only | No PC/backend dependency |
| `C07` | Translation image OCR | Books and comics only | Scope OCR to books/comics |
| `C08` | Auto translation selected collections | Every verified supported non-English (CJK eligible) book | Auto queue all eligible CJK originals |
| `C09` | Derived edition default linked group | Independent outputs and optional links | Separate items; relation opt-in |
| `C10` | Duplicate keeper always manually chosen | Auto-merge byte-exact duplicates reversibly | Logical merge only, no silent file deletion |
| `C11` | Core model/tagging was earlier incomplete | Core advanced through Nov24; mostly correct, gaps in tagging/stability | Audit/import current latest, validate actual family coverage |
| `C12` | Downloader fixes in stand-alone release | Persistent one-month defects remain | Repair inside Nexus and verify end-to-end |
| `C13` | Control limited remote UI tool | New independent, tailored AI and broad tool plan | Control owns general assistant, orchestration; Nexus remains library/media authority |
| `C14` | Video editor generic trim/concat | Automatic series episode stitching, OP/ED and redundant recap/preview cuts | Concrete episode assembly contract, preserve uncertain unique scenes |
| `C15` | Premium broadly described | No slogans anywhere and highest achievable polish | Strict UI copy/performance/usability acceptance |

Conflicts should remain traceable; do not rewrite or erase historical donor documents.

## Technical unknowns / blockers to verify

1. Android source compatibility: Gradle/Java/Kotlin/Compose/WebView hybrid, native ABI, service collisions, packaging/signing, foreground services Android 17, app-private data export.
2. Rounder's precise installed accepted version vs assembly source hash and reproducible extraction; 3D remains outside owner functional acceptance.
3. New Nexus signing identity and migration authorization; retaining original apps prevents silent access to their sandboxed data.
4. Actual phone free memory/thermal/disk/network conditions and native inferencing/media codec performance.
5. Private TeraBox multi-account session capability, canonical remote checksum availability and cloud provider operational access.
6. For Downloader providers, actual currently available original source, provider terms/login/gates, definitive transfer traces; network access blocks are not magically fixed by UI/code.
7. Real text/OCR translation training data/weights and acceptable literary quality; old contract is not a trained runtime.
8. Reliable media episode segmentation under variants and critical false-positive loss avoidance; require annotated fixture set and actual device render.
9. Which specialist partner APIs are actually installed and callable; many historical docs say `NOT WIRED`.
10. New Control independent-AI spec may be ahead of its last GitHub V5; use current owner repository/bridge without replacing working relay.

These unknowns must be resolved by implementation research, source verification and authorized testing; do not guess them into "implemented".

## Repository safety and status

Only public source, schemas, documentation and synthetic test fixtures may be committed. Do not include real user libraries, character image references, copyrighted source books/videos, cloud accounts, arbitrary app backups, signing material, private access logs or endpoints requiring secrets. No source repository mutation is implied by Nexus planning. **The owner authorized Nexus specification implementation in this repository, not any destructive action on donor apps, phone or PC.**
