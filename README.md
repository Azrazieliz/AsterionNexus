# Asterion Nexus

**Canonical specification repository — 26 November 2026.** Status: **requirements and integration plan committed; Android implementation not yet integrated or device-accepted**.

Asterion Nexus is the **three-way native Android merger** of **Asterion Core + Asterion Downloader + Asterion Rounder**, augmented by verified new capabilities. This repository, not the older Downloader-host specification, is the target for the merged product. It must not become a thin launcher containing three separate applications. Existing source implementations and accepted functionality are to be adapted and consolidated before adding integration layers or new features.

## Reading order

1. [Product authority and decisions](docs/00-AUTHORITY-AND-DECISIONS.md)
2. [Merge-first architecture and implementation inventory](docs/01-MERGE-FIRST-ARCHITECTURE.md)
3. [Migration, storage and library](docs/02-MIGRATION-STORAGE-LIBRARY.md)
4. [Acquisition, providers, search and torrents](docs/03-ACQUISITION-PROVIDERS-SEARCH.md)
5. [Fusion intelligence and learning](docs/04-FUSION-INTELLIGENCE.md)
6. [Readers, translation and private indexing](docs/05-READERS-TRANSLATION.md)
7. [Automatic video editing and episode assembly](docs/06-MEDIA-EDITING-EPISODE-ASSEMBLY.md)
8. [Premium interface acceptance](docs/07-UI-DESIGN-CONTRACT.md)
9. [Control and cross-app contracts](docs/08-ECOSYSTEM-INTEGRATION.md)
10. [Execution plan, acceptance and release gates](docs/09-EXECUTION-AND-ACCEPTANCE.md)
11. [Evidence, dependencies and source references](docs/10-SOURCE-BASELINES-AND-EVIDENCE.md)
12. [Capability and data contract schemas](contracts/README.md)

## Product rules

- **Merge first**: use the latest verified and accepted **real source** from the three donors, reconcile their functionality and persistent state, then establish cohesive behavior, bridges and new Nexus capabilities.
- **Public source only**: no credentials, cloud session cookies, personal content, commercial game assets, private model weights, keystores or other protected material in Git.
- **Co-install first**: new Android package; preserve all legacy applications and their databases/files; migration is verified, opt-in and non-destructive.
- **Rounder functionality is accepted by the owner**, although its UI must be replaced. Downloader provider/cosplay/search/torrent flows are not accepted. Core is mostly correct but needs full applicable tagging, consistency and stability; character identity recognition is not certified without Character Knowledge.
- **No slogans anywhere in Nexus UI.** No marketing-style welcome text, motivational claims, decorative captions, faux dashboards, unfinished placeholders or exposed internal/debug implementation vocabulary in normal workflows.
- **Highest achievable premium quality** is an acceptance target expressed through measurable interaction, typography, rendering, accessibility, performance and real-device testing—not a superficial theme or label.
- **Nexus provides contextual library intelligence; Control owns the general assistant and complex inter-app orchestration.** Nexus can invoke direct authorized specialist actions independently.
- Build success, source presence, emulator tests, signed APK, installed APK and owner-accepted phone functionality are distinct evidence states. No false completion claims.

## Implementation status

This repository was empty when the specification work began. These documents are **binding target requirements and execution design**, not claims that a merger, APK, migration or verification has occurred. Do not copy or replace source donors without a tested migration plan; do not archive or uninstall them before full Nexus phone acceptance.

Scope exclusions: **Adaptive Study** is not part of the cross-app integration audit or immediate Nexus plan. The separate prospective Art + Sound + 3D + Writing creative-app merger is not the Nexus three-way merger.
