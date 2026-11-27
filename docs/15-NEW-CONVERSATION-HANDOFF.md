# New-conversation execution handoff — start coding, not replanning

**Use this file to resume without re-asking approved architecture questions.**

## User-approved immediate objective

Implement Asterion Nexus as an actual three-way **Core + Downloader + Rounder** native Android merger in `Azrazieliz/AsterionNexus`, using its existing verified donor sources and preserving all working behavior. **Merge engines first** before investing in optional bridges and new capabilities. The existing standalone apps remain intact until full Samsung phone acceptance. The public repository must contain **source only**, no private media, credentials, paid game assets, signing keys or unreleased proprietary weights.

**Very important UX correction:** all three donor UIs were considered poor. Build a completely new, exceptionally premium, adaptive, English-language Nexus UI. **Slogans are prohibited throughout the runtime UI**, without exception for splash, onboarding, notifications, empty state, loaders or settings. Advanced actions must remain useful and visible; no dead controls, fake statuses or three-app launcher.

## Canonical owner decisions are already finalized

Read:
- `spec/owner-decisions.v1.json` (do not reopen decisions already approved);
- `docs/16-APPROVED-EXPERIENCE-AND-ORGANISATION.md` (2026-11-27 controlling UI, navigation, rating, organization, naming, TeraBox, acquisition and regression rules);
- `docs/00-AUTHORITY-AND-DECISIONS.md` (supersession/behavior);
- `docs/01-MERGE-FIRST-ARCHITECTURE.md` (source adaptation/ownership);
- `docs/13-SOURCE-ADAPTATION-MAP.md` (existing file-level implementation anchors);
- `docs/14-DELIVERY-ROADMAP.md` (fast critical path and evidence gates);
- `docs/09-EXECUTION-AND-ACCEPTANCE.md` and `spec/acceptance-cases.v1.json` (actual acceptance, not source-only completion).

Do **not** propose another architectural rewrite, start a new Nexus repository or ask whether it should be three-way. Do not wait for a revised specification; all requested features are already captured. The initial 2026-11-26 handoff predated native source integration. Inspect current branches/PRs/CI before resuming; actual harmonization source exists on development branches, and is not certified or merged by this specification update. Do not restart the merge or assume an old candidate matches the new design.

## Start executing in this order

1. Refresh the latest donor SHAs and inspect reproducible build pipelines and real package/ABI/signing identities:
   - `Azrazieliz/AsterionDownloader` `repair/downloader-global-search-20261121` (Kotlin host candidate, native provider/cosplay/video/torrent engines).
   - `Azrazieliz/Asterion-Core` `recovery/core-v2100-gaps-20261118` (latest Fusion/learned visual tagging; **old Core main is obsolete**).
   - `Azrazieliz/AsterionRounder` `feat/control-recursive-inventory-20261122`; **assemble from source.part archive and ordered hotfixes to derive the real accepted program**.
2. Confirm the existing authenticated Asterion Control Windows+Android bridge is reachable. Reuse it for permitted PC/phone source, build, test and diagnostics operations; **do not create a redundant bridge**. Use GitHub connector for repository reads and commits. If the bridge is down, continue all repository-only work and report exact unavailable action; do not claim device test.
3. Open a work branch in Nexus, commit a real Android project scaffold **based on donor source**, then import one actual engine per donor, with source provenance and manifest collision fixes. **Do not stop after scaffolding**.
4. Establish CI Android compilation, inherited tests, native ABI/model provenance, explicit package ID and non-destructive identity. Verify actual callable code. Keep donor repos and installed apps unchanged.
5. Create the unified registry and persistent transfer/job core; complete a real source -> verified selected original -> searchable/openable library item as the **first target acceptance**.
6. Fix long-standing Downloader provider/cosplay/search/torrent defects **within Nexus**; carry across latest Core tagging/stability and accepted Rounder streaming/readers/organizer.
7. Build the slogan-free truly premium integrated UI against functioning services, with real Samsung tests of visual quality, touch, media and performance. Maintain user-visible functionality parity.
8. Integrate Control as independent optional shared browser/general assistant/complex orchestrator and real signed direct specialist app actions.
9. Implement additional capabilities incrementally; especially **Episode Assembly** retaining first OP/last ED, cutting reliably detected redundant intervening OP/ED, recaps and previews, preserving unique scenes and sources; phone-offline first-party CJK book/comic translation; full private book indexing; semantic image search; archive/file repair; media processing and smart collections.
10. Validate real provider/phone/media output, authorized migration of selected history/metadata and only then propose legacy retirement.

## Prioritization and status discipline

- Rounder = owner-accepted non-3D functions, **UI poor**. Do not waste time restarting standalone Rounder defect hunts.
- Downloader = provider acceptance, cosplay, search, torrents **not accepted**, despite many CI/source patches. Fix root causes in Nexus; request real source evidence as needed.
- Core = mostly correct, advanced past older audit; full applicable family tagging, stability/consistency remain; **character recognition untested because Character Knowledge not yet ready**.
- All default actions reversible; irreversible operations require confirmation. TeraBox is the primary Library and default permanent destination for every media type, with multiple isolated accounts and adaptive cache/pins. Authorised content may be automatically organised; migration itself remains non-destructive. Migrate the **selected metadata/history** from each app, never bulk-move files or copy credentials to Git.
- Control's latest independent AI architecture must be respected without duplicating its engines. Nexus remains usable without Control and can call authorized specialist apps directly.
- Passing source tests, compiling, producing APK, signing, installing, executing on phone, verifying bytes and owner acceptance are **distinct levels**. Update acceptance ledger ONLY with actual proof.
- No fabricated success, no endlessly repeated failed builds, no placeholder UI, no long status narration instead of implementation. Make actual source modifications, test, correct and verify as much as available tools allow.

## Existing links

Nexus: https://github.com/Azrazieliz/AsterionNexus

Roadmap: https://github.com/Azrazieliz/AsterionNexus/blob/main/docs/14-DELIVERY-ROADMAP.md

Import map: https://github.com/Azrazieliz/AsterionNexus/blob/main/docs/13-SOURCE-ADAPTATION-MAP.md

UI contract: https://github.com/Azrazieliz/AsterionNexus/blob/main/docs/07-UI-DESIGN-CONTRACT.md

Episode assembly: https://github.com/Azrazieliz/AsterionNexus/blob/main/docs/06-MEDIA-EDITING-EPISODE-ASSEMBLY.md

## First status line expected from the next execution conversation

`Donor SHAs: ... | Host: ... | Rounder assembly: ... | Git branch: ... | Integrated source modules: ... | Android compile: ... | Source tests: ... | First real Nexus functional test: ... | Phone evidence: ... | Blockers: ...`

Be clear where a field is `NOT RUN`, `UNAVAILABLE` or `FAILED`. Do not ask permission for normal implementation already authorized, but never bypass real protected account or destructive-action consent.
