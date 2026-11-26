# Fusion-native intelligence, tags, knowledge, review and learning

## Preserve the most advanced Core implementation

The relevant donor is `Azrazieliz/Asterion-Core` `recovery/core-v2100-gaps-20261118` at or after `f453a9d5defd` (2026-11-24), subject to renewed branch/installed-build inspection. It has advanced well beyond `main`: feature-backed WD-ViT visual inference, person/subject region recognition, per-family coverage, canonical label mapping, learning and corrections, visual-reference migration, resumable processing and multiple consistency fixes. Reuse its current source, data model and tests; no new parallel raw-tag store, no rewriting thousands of taxonomy records into prompts, and no false inference of live tag accuracy from synthetic model smoke.

## Canonical Fusion requirements

- One operational SQLite Fusion taxonomy: immutable stable IDs, family classification, aliases, relationships, character, series and version knowledge, species/transformations, original-character differentiation, source provenance, ratings, and human correction history.
- Canonical taxonomy structures and owner edits survive re-indexing, model changes, application upgrades, backups and migration. 15 historical knowledge packs are migration sources, not permanent user-facing JSON administration screens.
- Support growing character knowledge exceeding 20,000 entries, tests with 25,000+ and media libraries of 300,000+ images; indexed reads, bounded memory, stable paging and incremental edits.
- User-facing Add Tag: search by name, suggest true existing family and aliases, disambiguate duplicates, write permanent canonical entry with safe ID allocation; Fusion export is a verifiable SQLite backup in a data/recovery setting.
- Recognize every **applicable** visual tag family, per distinct visible character or image-wide scene as defined; cannot label missing, occluded, inapplicable or unsupported features as observed.
- Character attributes must be grounded in **visible subject pixels**; canonical lore/background knowledge must never be misrepresented as visible in a cropped photo. Preserve image-level scenery, objects and composition separately from subject-level hair/eyes/body/clothes/pose.
- Handle multiple characters independently with stable regions, correct per-subject tag scope, relationship/interaction evidence when genuinely visible and image-wide environmental observations.
- Precisely classify content rating separately from aesthetic quality; owner manual rating remains authoritative, survives new scans and is not overwritten by inferred score.

## Required ordered, exhaustive family advancement

Use the Core `CanonicalFamilyAdvancement`, `FusionTagFamilyOrder`, `FusionVisualLabelMapper`, `CharacterProfileCompleteness`, `NativeSubjectEvidencePolicy`, `SubjectTagConsistencyPolicy`, and their latest tests as starting points. For each required family, record:

```
family_id | image/subject | expected_applicability | evaluated_by_model |
candidate_scores | accepted_observations | held_back_conflicts |
status=ACCEPTED | MISSING_EVIDENCE | UNSUPPORTED_MODEL |
DEFERRED_REVIEW | NOT_APPLICABLE | NOT_VISIBLE |
model_id + checksum | source/crop_hash | threshold | reason
```

Acceptance means complete **evaluation of each required family**, not a fabricated tag per family; a legitimately non-applicable or unobservable family must be explicitly resolved, not left silent. Weak/contradictory observations do not become permanent canonical facts. Report first blocked family with precise coverage and remediation; preserve completed independent families after one model/crop fails.

## Learning without hallucinated capabilities

- Reuse WD-ViT native features (validated feature dimension and model hash), Fusion visual online learning ledger, explicit per-subject positive/negative corrections, reversible correction/retraction and source fingerprint invalidation.
- Immediate bounded corrections can adjust confidence only with actual model evidence; no invented labels outside fixed-head vocabulary; suppress duplicate adjustments after an already learned head consumed the correction.
- Periodically train/attach task-specific Fusion heads only from reviewed, provenance-linked and region-scoped samples; separate source groups across train/validation; evaluate at actual Android admission thresholds; gate precision/recall and regressions; keep old model/DB and rollback if candidate performs worse.
- The existing `fusion_wd_training.py` fits selected heads and requires independent positive/negative sources and held-out metrics; preserve this implementation and expand training data/coverage, rather than treating an untrained head as supported.
- Few well-adapted shared specialist models with bounded task-specific heads, sequential/on-demand model loading, hardware-backed inference only if measured on the Samsung, and thermal/memory/energy budgeting.
- Do not introduce an unrelated "smol" general LLM in place of the tailored Fusion visual intelligence. Model/package provenance, license, quantization, hash, backend and honest user-image metrics required.

## Character identity and temporary readiness

Character Knowledge is not yet sufficient for full live recognition acceptance. Continue image tags, classification, quality review, organization and reader functions. Character IDs may be `UNKNOWN`, `ORIGINAL_CHARACTER` or `POSSIBLE_MATCH_UNVERIFIED` with review evidence. Never label synthetic roster tests as recognition. When reference sheets and knowledge become available, validate source-scoped matching, variants, false-positive rate, unusual appearance, multi-character scenes, rejection of ambiguous associations and temporal update migration.

## Organization and review

- High-confidence, owner-approved routing rules may organize with durable moved-file receipts and rollback, respecting SAF permissions and cloud presence. Distinguish proposal from applied move and from verified destination.
- Uncertain identity/tags/rating, incompatible species traits and weak evidence enter a precise review inbox; user correction is persistent and retractable.
- Junk/preview triage and aesthetic score below 25/100 may suggest discard/review, **never automatically delete**. Original Characters and Uncertain Identity routes preserve evidence and allow correction.
- Smart collections/visual semantic search use actual embeddings or accepted Fusion evidence, with an indexed similarity layer, no cross-subject tag contamination and explainable matching. Recommendations derived from existing library are private, optional, grounded and never auto-download/deleting originals.
- Any material moved to an independent collection retains source identity, original path, source provider and backward navigation.

## Performance and acceptance matrix

- Correct images across expected tag families and subject count; repeated runs yield stable results within defined tolerance, no oscillating tags, no corrupt thumbnail/preview, no reanalysis wipe.
- Test 25k+ character corpus and 300k+ image index, dozens of simultaneous jobs under capped memory, cancel/resume/restart, 3D/animated inputs appropriately unsupported/handled, DDL migration/backup integrity, model switching/rollback.
- One-run family coverage report with required/optional statuses; compare **real owner-reviewed image set** and independent holdout, including multi-person and ambiguous cases. Record accuracy, precision/recall, runtime, memory, thermal and user outcome; no claims of acceptance without on-device evidence.
- Keep the gallery-first intuitive normal UI. Implementation vocabulary (`ONNX`, `GGUF`, raw tags like `HC001`, SQL, debug traces) belongs to technical diagnostics only, not primary viewing surfaces.

## Primary donor references

- `docs/production/ASTERION_CORE_FUSION_NATIVE_GALLERY_REBUILD_REQUIREMENTS_2026-11-08.md`
- `docs/production/ASTERION_CORE_CANONICAL_TAGGING_ORDER_2026-11-18.md`
- `android_app/app/src/main/java/com/ailm/android/runtime/` (Fusion stores, label mapper, confidence policies, region inference, visual head, reviews)
- `scripts/fusion_wd_training.py`, `scripts/prepare_bundled_wd_vit_v3.py` and associated Python/JUnit gates.
