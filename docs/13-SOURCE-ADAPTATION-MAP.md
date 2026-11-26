# Source adaptation workpackages — real file-level anchors

**Read-only inventory derived from accessible GitHub donor trees.** Import these **verified existing** paths/files only after refreshing their current branch commit and checking complete dependencies, asset/build outputs, licenses and tests. This map prevents accidental duplicate rebuilds. It is *not* an implementation claim.

## Downloader: Kotlin host and acquisition donor

Source repository: `Azrazieliz/AsterionDownloader`, candidate `repair/downloader-global-search-20261121`.

| Workpackage | Existing files / source locations | Nexus integration decision |
|---|---|---|
| Android host and navigation | `app/src/main/java/com/azrael/pixivdumpsync/AsterionHomeActivity.kt`, `MainActivity.kt`, `UnifiedDownloadsUi.kt`, `app/src/main/AndroidManifest.xml` | Extract lifecycle/jobs and capabilities, **replace UI** with premium Nexus shell |
| Provider registry/discovery | `SourceIndex.kt`, `AsterionSearchHub.kt`, `KeywordSources.kt`, `SearchRanking.kt`, `BrowserPageFetch.kt`, `SourceIndexPanel.kt` | One provider identity/search/result registry; global source-scoped query; fix exact search acceptance |
| Gallery and cosplay | `com/azrael/galleryflow/AdapterRegistry.kt`, `CatalogAdapters.kt`, `KiutakuAdapter.kt`, `GenericSiteAdapter.kt`, `SyncEngine.kt`; `com/azrael/pixivdumpsync/ApprovedCosplayActions.kt`, `AsterionCosplayGallery.kt` | Reconcile six native Kyora adapters + generic routing/session; full creator/album/original counts, no preview as original |
| Pixiv, X and creator sources | `PixivApi.kt`, `AsterionUnifiedFollowing.kt`, `XFollowingImportActivity.kt`, `XPhotoEngine.kt`, `CreatorArchives.kt`, `SiteBackfillProfiles.kt` | Preserve subscriptions, live/backfill, cursor consistency and verified source data |
| Video routing | `com/asterion/videoflow/TransferService.kt`, `SiteSpecificOriginalSources.kt`, `PublisherOriginalExtractors.kt`, `VideoDb.kt`, `WebDiscovery.kt`, `NativeMediaDownloader.kt` | Exact selected original resolution, full bytes/duration and provenance; no bypass of legitimate auth/access restrictions |
| Torrents | `TorrentTransfers.kt`, `TorrentMetadataBridge.kt`, `TorrentVerifiedExport.kt`, `TorrentQuarantineGate.kt`, `MultiTorrentSearch.kt` | Restore real transfer/seeding stop, piece safety, verified publication and Nexus job ledger |
| Cloud and transfers | `TeraboxUploader.kt`, `TeraBoxRemoteOriginals.kt`, `NduTeraboxClient.kt`, `RounderDispatch.kt`, `AsterionDownloadIPC.kt` | Reconcile with accepted Rounder cloud authority; no duplicated accounts or false receipts |
| EPUB/media and cards | `NovelEpubService.kt`, `ChapterArchives.kt`, `KoikatsuCards.kt`, `ResourceDownloads.kt` | Register actual original file/metadata and dispatch to Nexus native viewers |
| Existing regressions | `docs/diagnostics/SEVEN_PROVIDER_RELEASE_GATE_2026-11-18.md`, `PROVIDER_RELIABILITY_AND_FAILURE_TAXONOMY_2026-11-16.md`, Android/JVM tests | Preserve donor tests, add real provider/multi-source and phone acceptance |

Import the isolated `:torrent` process only after manifest/process/permission audit. No assumption that all source classes, native libs or provider APIs are already stable.

## Core: Fusion SQLite, native visual models and review

Source repository: `Azrazieliz/Asterion-Core`; **refresh** `recovery/core-v2100-gaps-20261118`, not old `main`.

| Workpackage | Existing files / source locations | Nexus decision |
|---|---|---|
| Fusion schema/repository | `android_app/app/src/main/java/com/ailm/android/runtime/FusionDatabaseSchema.kt`, `FusionDatabaseRepository.kt`, `FusionTaxonomyEngine.kt`, `FusionResolutionStore.kt` | Retain canonical IDs, schema, aliases/migrations, cross-item identity |
| Character knowledge/reference | `FusionKnowledgeReader.kt`, `FusionReferenceMigration.kt`, `CharacterResolver.kt`, `CharacterSheetArchiveImporter.kt` | Readiness gates, reference-scope provenance, no untested character-identity "success" |
| Tagging/family coverage | `FusionVisualLabelMapper.kt`, `CanonicalFamilyAdvancement.kt`, `FusionTagFamilyOrder.kt`, `CharacterProfileCompleteness.kt`, `SubjectTagConsistencyPolicy.kt` | Actual per-subject image/scene family evaluation and uncertainty |
| Native visual inference | `runtime/ai/NativeRuntimeBackends.kt`, `ModelInferenceContract.kt`, `FlorenceStage2.kt`, `runtime/StandaloneRuntime.kt` | Shared validated model runtime, packaging, source hashes, bounded resource state |
| Learning | `FusionVisualOnlineHead.kt`, `NativeTagFeedbackLearning.kt`, `ArtworkPreferenceLearning.kt`, `scripts/fusion_wd_training.py`, `prepare_bundled_wd_vit_v3.py` | Reuse explicit reviewed evidence, online corrections and held-out training; close unsupported families |
| UI and jobs | `ui/viewmodel/AppViewModel.kt`, `ui/navigation/AppNavHost.kt`, `workers/SetupWorkers.kt` | Retain underlying data/operations; replace donor UI, harmonize job statuses and resource budgets |
| Tests | `scripts/test_core_full_tagging_restart.py`, `test_fusion_visual_online_ledger.py`, `test_bundled_wd_vit_v3.py` plus native JUnit | Preserve run evidence, extend to actual phone library and 300k+ index |

Do not copy model weights into public Git; inspect packaged licenses/hashes. Core's latest Nov24 CI produced an unsigned release artifact and skipped signed-release; don't infer current device deployment.

## Rounder: assembled Android reader/cloud donor

Repository: `Azrazieliz/AsterionRounder`, current working candidate `feat/control-recursive-inventory-20261122`. **Source must be reconstructed from `openlocaloffice-build/source.part*` and ordered CI workflow** before selecting final Java/JS code.

| Workpackage | Existing file or assembly input | Nexus decision |
|---|---|---|
| Reproducible source | `.github/workflows/openlocaloffice-build.yml`, `openlocaloffice-build/additions/`, `hotfixes/`, `source.part*` | Reconstruct exact accepted release source into checked-in maintainable Nexus modules; preserve history and relative ordering |
| TeraBox connector | `additions/FgTeraboxClient.java`, `FgTeraboxDocumentsProvider.java`, `FgTeraboxSearchIndex.java`, `FgTeraboxMediaDataSource.java` | Multiple isolated cloud accounts, persistent search and progressive range reads |
| Library/search | `additions/premium-library.js`, `FgSearchIndex.java`, `FgRounderInventoryBridge.java` | Keep indexed behavior and consent scopes; replace existing visual UI |
| Readers | `additions/universal-reader.js`, `reader-controls.js`, `epub-reader.js`, `UniversalReader.java`, `SeekableZip.java` | Preserve format semantics, zoom/mode/state and progressive cloud decoding |
| Media player/previews | `additions/FgMediaPlayer.java`, `FgRounderVideoPreviews.java`, `FgRounderOriginalPreviews.java` | Preserve aspect/seek/controls/thumbnail quality, improve presentation |
| Organization | `additions/FgRounderOrganizer.java`, `FgRounderOrganizeJob.java`, `rounder-visual-duplicates.js` | Unified reversible operations, exact duplicate logic and Nexus registry |
| 3D/media bridge | `openlocaloffice-build/noworld-bridge/`, `hotfixes/apply-3d-asset-viewer.py` | Inventory real format support and keep 3D preview independent of unverified NoWorld/KK import |

The owner reports currently **no noticed functional defects in Rounder except a poor UI**. This is the current owner-level functional acceptance, not proof each CI branch/3D feature is accepted. Do not revive old "Rounder broken" defects as blocking scope without fresh evidence.

## Control and specialist adapter anchors

- `Azrazieliz/Asterion-Comander`: `docs/CONTROL_CROSS_APP_API_V1.md`, V3/V5 specs, `android/app/src/main/java/com/asterion/control/AppBridge.java`, `asterion_remote/shared_browser.py`, diagnostic routes, with authenticated existing Control private bridge preserved. Check current Control AI expansion separately.
- `Azrazieliz/AsterionArt`: `README.md`, `docs/METHODOLOGY_REGISTRY.md` and Android provider declarations for image references/results.
- `Azrazieliz/Asterion-Sound`: `7STAR_FROZEN_MASTER.md` plus current actual implementation; do not assume runtime exists from product spec alone.
- `Azrazieliz/Asterion-3d`: `docs/SCOPE.md`, `docs/INTEGRATION.md`, `docs/VERIFICATION.md`; real GLB validation doesn't equal NoWorld import acceptance.
- `Azrazieliz/Asterion-Writing`: `docs/PRODUCT_ARCHITECTURE_V2.md`, `docs/INTEGRATION_CONTRACT.md`; Nexus owns story/archive, Writing inference.
- `Azrazieliz/Asterion-Games`, `Game-Central-Database`, `Asterion-kk`, `NoWorld-False-Horizon`: use their explicit specs and versioned asset boundary, do not merge their actual engines into Nexus.
- `Azrazieliz/Cosplay-Download`: audit original native adapter behavior and archive completion invariants as source reconciliation input.

## Pre-import gate template

For every file/package changed or imported:
```
donor_repo, branch, commit_sha, source_path, destination_path, semantic_owner,
license/provenance, dependencies, removed/replaced prior implementation,
data_schema impact, permission/service/authority collision,
tests preserved, negative tests, build result, device acceptance status,
rollback reference and owner-approved release scope
```

A component with missing source provenance, signing incompatibility, abandoned test, undeclared credential source or uncertain data mapping is **not** ready for bulk merge. Prefer targeted adaptation preserving working behavior and guided tests to a wholesale rewrite.
