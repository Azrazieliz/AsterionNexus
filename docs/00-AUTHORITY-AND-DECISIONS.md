# Nexus authority, product requirements and owner decisions

**Version:** 2026-11-27. **Authority:** owner decisions through 2026-11-27; later refinements are normative in [16 — Approved experience and organisation](16-APPROVED-EXPERIENCE-AND-ORGANISATION.md). **Status:** CANONICAL REQUIREMENTS, NOT IMPLEMENTATION CERTIFICATION.

## Supersession

1. Nexus is a **three-way merger** of Asterion Core, AsterionDownloader and AsterionRounder, with additional capabilities. It is **not** only Downloader + Rounder; the 2026-11-08 two-way document is historical input, not controlling merger scope.
2. The new repository `Azrazieliz/AsterionNexus` owns the final product; older language mandating Downloader as permanent host is superseded. Downloader remains the likely **initial Android source donor**, not the product repository.
3. Source code may be public, but personal media, session credentials, tokens, keystores, account details, unlicensed binaries, commercial game/mod content, private samples and internal diagnostics containing secrets **must not** enter Git.
4. Install Nexus as a **new co-installable application**; migrate verified data non-destructively. Existing apps remain intact and installed until full Nexus phone acceptance. Do not replace legacy package names or reuse a signing key without a demonstrated need and explicit safety review.
5. The owner accepts **Rounder's functional non-3D usage**; its UI is rejected as a basis for Nexus. The owner reports persistent Downloader provider acceptance, cosplay, global search and torrents failures after extended attempts. Fix those in Nexus; do not misstate fixed code or CI as working downloads. Core is mostly right, but exhaustive applicable tagging and stable/consistent execution remain; character identity recognition is untested pending Character Knowledge. Reconcile **latest Core branch**, not old `main`.
6. **No slogans or marketing language in ANY runtime UI**. Premium interface quality is mandatory, as described in `07-UI-DESIGN-CONTRACT.md`. There is no permission to simplify this to a recolor, theme or reused legacy UI.

## Approved navigation and product experience

- Interface language **English**. Charcoal/silver, image-first large previews, exactly three permanent destinations: Library, Organising, Downloading. Category/module tiles replace appropriate folder/element lists only. Rating is opened-file metadata, not a Review or Ratings module. One canonical action/module registry; no duplicate controls or directory trees. See 16.
- Launch into an **adaptive intelligent library**; one cohesive contextual workspace, not three products, a welcome dashboard, promotional home or developer console.
- Adaptive gallery, folder and document layouts; each reader and media player has **specialized gestures/controls**.
- **New distinctive premium Asterion visual system**; advanced actions **always accessible/visible as useful controls**, without clutter or meaningless debug widgets. The user rejected the UIs of all three donor applications. Design an entirely coherent production experience while retaining functional engines.
- General chat assistant belongs to Control; Nexus offers contextual library intelligence and functions.

## Approved data/storage decisions

- **TeraBox is the primary Library and default permanent destination for all acquired media**, with multiple isolated accounts; designed for other cloud providers later. Authorised existing content may be automatically reorganised; migration itself remains non-destructive.
- Offline: **adaptive previews/cache** and explicitly chosen files saved for offline use. Do not mirror entire libraries onto the phone.
- Following **actual remote checksum/size/existence verification**, offer owner-confirmed cleanup of local copies. Do **not** auto-delete the local original.
- **Auto-merge exact duplicates** with reversible identity/location history; never conflate near-duplicates or revisions. Destructive/irreversible changes always require confirmation; reversible actions may be autonomous.
- Edited, translated and converted outputs are **independent items**; relations to originals are **optional**, not hard-coded universal parent/child presentation.
- Explicit migration scope: Downloader provider configurations, subscriptions, completed catalogs and **safe** queue history; Core Fusion taxonomy, tags, ratings, explicit corrections and processing history; Rounder cloud indexes, account **mappings**, bookmarks and reading progress. **Do not bulk move or rewrite existing physical originals**, and **do not export passwords/tokens**; reconnect or securely transfer secrets with explicit approval.
- Preserve the originals of all user content. No silent deletion, relocation or loss of identity, bookmarks, canonical tags or corrected ratings.
- Cross-account indexing is allowed by a local aggregation view, never by mixing credential/session ownership.

## Approved discovery/acquisition

- **One simultaneous search** across local Nexus library, each authorized cloud account and enabled online providers, with explicit source provenance, progressive federated results, filters and partial-failure truth. Private local queries/content must not silently leak to web providers.
- Source onboarding: native site adapters when needed; reuse Control's shared browser/session infrastructure where useful, with a Nexus-owned acquisition adapter and independent degradation path.
- Site logins: owner-authorized sessions; surface genuinely required owner interactions.
- If the exact selected media is unavailable, automatically try **authorized mirrors of the SAME item**. Never substitute a similar, wrong or advertisement file.
- Online discovery preview tap acquires the selected exact original/complete collection, never a browser redirect or link. Existing library search opens existing items. Preserve distinct modules by media type, source following, independent live sync/backfill and all donor systems.
- Cosplay: **creator -> albums -> originals**, with genuine complete-gallery intake and verified counts. Filename: Character - Cosplayer Number.extension.
- Torrent: actual metadata/transfer, piece verification, independent safety checks and safe publishing; **stop seeding upon verified completion**. Control can supply deeper inspection but is not required for Nexus baseline operation.

## Approved Fusion behavior

- Preserve live canonical Fusion, character/subject scope, image-wide context, rating authorities and source evidence. **Complete every applicable taxonomy family** or record a precise unsupported/missing-evidence state.
- Missing family coverage prioritizes native learning and review, not fabricated tags; immediate **bounded correction learning plus periodically held-out-validated model-head training**.
- Confident results may organize automatically; uncertain items await review; retain reversibility and correction lineage.
- Until actual Character Knowledge is available and validated, do general tagging and mark identity **unverified**. Never fabricate recognition acceptance.
- Review handles uncertain automation decisions; subjective per-file 0–100 ratings are opened-file metadata, not a review module. Both learn automatically from separate signals. Artwork scored below 25/100: review/discard **suggestions only**, no automatic delete.
- Few shared specialist models plus bounded task-specific heads; load according to measured phone resources; prohibit proliferating unverified competing AI backends.

## Approved books and media

- **Automatic offline phone-native quality translation** for every verified supported CJK book/comic: Japanese, Korean, simplified/traditional Chinese -> English; preserve source; OCR only for books/comics where needed.
- **Full private searchable text indexing of ALL imported books** is explicitly approved and **supersedes** the old 2026-11-11 prohibition. Index must remain locally private, access-controlled, recoverable and kept out of public web search and repo. Unsupported languages must be honestly reported. Translation/OCR engines must remain inside Nexus at runtime without Control, PC or external API dependency.
- Advanced media analysis, image/video conversion and derivative generation, file repair, corruption detection/recovery, archive inspection/extraction, semantic visual search, smart collections, content recommendations and a unified creator/series follow/backfill dashboard are **in scope**.
- Each newly acquired complete comic title is one ordered CBZ. Preserve the established Core naming/routing convention and the additional media-specific naming rules in 16.
- **Automatic Episode Assembly** on selected video collections is mandatory: intelligently order parts, retain the first opening, retain the last ending, remove intervening OP/ED, and **automatically remove redundant recaps and next-episode previews when reliably detected**; preserve uncertain content; produce a verified continuous original-preserving output. See `06-MEDIA-EDITING-EPISODE-ASSEMBLY.md`.

## Approved integration and rollout

- Control and Nexus remain independent applications communicating through versioned, signed, authorized capability/diagnostic bridges. Control owns general AI, the shared browser, deeper security diagnostics and **complex cross-app orchestration**. Nexus can invoke **direct, authorized specialist actions** when Control is unavailable.
- Art/Sound/3D/Writing: **deep integrated project browsing and remote specialist actions**, while each specialized engine remains outside Nexus.
- Game Pilot, Game Codex, Asterion KK and NoWorld: **project-aware asset indexing, context-sensitive asset recommendations and verified file/package relationships**.
- Adaptive Study excluded from this audit/plan, not silently merged.
- **Merge-first implementation**; broad architecture now, progressive verified delivery. First end-to-end acceptance: exact acquired source -> verified file -> searchable organized library item.
- Interactive viewing/playback > active transfers > background AI/indexing under measured battery/thermal budgets.
- Real phone tests at each meaningful integrated feature gate; **one signed APK per evidence-based release preflight**, with stable signing, rollback and no misreported success.

## Decision precedence

This file and the domain-specific Nexus contracts reflect the newer confirmed owner decisions. An older donor specification remains authoritative for *working source behaviors and specialist rules* only where it does not contradict the Nexus owner's newer decisions. Reconcile contradictions explicitly in `10-SOURCE-BASELINES-AND-EVIDENCE.md`; do not silently delete older requirements. No user-selected requirement may be weakened because integration is inconvenient.

## Still requiring empirical determination, not owner re-questioning

Exact Android package ID and signing path; choice of initial host module after build comparison; device-specific performance, model/codec availability; detailed eligible-source provider matrix; permissions to read each private legacy database; production asset formats and account connectivity. These are **engineering discovery/verification items**, not permission to postpone the approved scope or guess answers.
