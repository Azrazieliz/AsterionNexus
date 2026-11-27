#!/usr/bin/env python3
"""Validate Nexus specification integrity and synthetic contracts.

This is NOT a test of Android functionality, AI quality, providers, migration,
download bytes or video cutting. Actual device acceptance is separate.
"""
from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]


def load(path: str) -> dict:
    return json.loads((ROOT / path).read_text(encoding="utf-8"))


def require(test: bool, reason: str) -> None:
    if not test:
        raise AssertionError(reason)


def check_document_set() -> None:
    require((ROOT / "README.md").exists(), "Missing README")
    for i in range(14):
        hits = list((ROOT / "docs").glob(f"{i:02d}-*.md"))
        require(len(hits) == 1, f"Expected one document {i:02d}, got {len(hits)}")
        require(len(hits[0].read_text(encoding="utf-8")) > 2000,
                f"Document {i:02d} is unexpectedly truncated")
    readme = (ROOT / "README.md").read_text(encoding="utf-8")
    for relative in re.findall(r"\]\((docs/[^)#]+|contracts/[^)#]+|spec/[^)#]+)\)", readme):
        require((ROOT / relative).exists(), f"Broken README link: {relative}")


def check_approved_decisions() -> None:
    d = load("spec/owner-decisions.v1.json")
    require(d["status"] == "OWNER_APPROVED_SPECIFICATION_ONLY", "Decision status")
    require(d["product"]["merge_components"] == ["Core", "Downloader", "Rounder"], "Three way merge")
    require(d["product"]["repo"] == "Azrazieliz/AsterionNexus", "Repository mismatch")
    require(d["product"]["public_source_only"], "Public source only")
    require(d["product"]["co_installable_new_app"], "New co-installable app")
    require(d["product"]["retire_old_apps_only_after_full_phone_acceptance"], "Premature retirement")
    ui = d["ui"]
    require(ui["no_slogans_anywhere"] and ui["highest_achievable_premium_quality"], "Premium/no slogans")
    require(ui["visual"] == "new_distinctive_premium_asterion", "Distinctive UI")
    require(ui["donor_ui_reuse_as_final"] is False, "No imported legacy final UI")
    require(ui["primary_navigation"] == ["Library", "Organising", "Downloading"], "Exactly three main destinations")
    require(ui["palette"] == "charcoal_and_silver" and ui["content_first"], "Owner visual direction")
    require(ui["category_tiles"] == "only_replace_applicable_folder_or_element_lists", "Tile layout scope")
    require(ui["rating_placement"] == "opened_file_metadata_only_not_review_or_module", "Rating metadata placement")
    require(all("Rating" not in tab for tabs in ui["submenus"].values() for tab in tabs), "No ratings module")
    require(ui["no_duplicate_commands_actions_or_module_directories"] and ui["canonical_action_registry"], "No duplicate commands")
    require(ui["centered_video_preview"] == "muted_autoplay_single_visible_preview" and ui["video_open"] == "actual_fullscreen_playback", "Preview and player")
    require(d["cloud"]["default_permanent_destination_all_media"] == "TeraBox", "TeraBox permanent library")
    require(d["search"]["online_result_tap"] == "download_exact_selected_original_without_browser_redirect_or_link", "No discovery redirect")
    require(d["acquisition"]["source_sync_and_backfill_independent"] and d["acquisition"]["sync_backfill_independent_cursors"], "Independent sync/backfill")
    require(d["fusion"]["rating_separate_from_review"] and d["fusion"]["review_and_rating_automatic_learning"], "Separate learning")
    require(d["organisation"]["filename_patterns"]["cosplay"] == "Character - Cosplayer Number.extension", "Cosplay naming")
    require(d["organisation"]["filename_patterns"]["comic"] == "Comic Title.cbz", "Complete comic naming")
    require(d["books"]["comic_output"] == "one_complete_title_cbz", "One CBZ per complete title")
    require(d["organisation"]["filename_patterns"]["series"] == "Series Name.mp4", "Assembly output name")
    require(d["organisation"]["filename_patterns"]["character_model"] == "Character (Transformation) Number.extension", "3D character naming")
    require(d["organisation"]["preserve_dependency_sensitive_paths"], "Preserve asset dependency paths")
    require((ROOT / "docs/16-APPROVED-EXPERIENCE-AND-ORGANISATION.md").is_file(), "Missing latest authority")
    require(d["cloud"]["multiple_isolated_accounts"], "Multi TeraBox")
    require(d["cloud"]["exact_duplicates"].startswith("automatically_merge"), "Exact reversible duplicates")
    require(not d["migration"]["bulk_move_original_files"], "Never bulk move original files")
    require(d["search"]["execution"] == "simultaneous_federation", "Federated search")
    require(d["search"]["no_private_query_leakage"], "Private query privacy")
    require(d["acquisition"]["torrent_auto_stop_seeding"], "Seeding policy")
    require(d["acquisition"]["same_exact_original_mirror_fallback"], "Original mirror fallback")
    require(d["fusion"]["all_applicable_families_evaluated"], "Full Fusion tagging")
    require(d["fusion"]["character_identity_until_knowledge"] == "unverified", "Recognition honesty")
    require(d["books"]["full_private_index_of_all_imported_books"], "Owner text indexing override")
    require(d["books"]["translation_execution"] == "phone_native_offline_only", "No remote translation")
    require(d["books"]["ocr_scope"] == "books_and_comics_only", "OCR approved scope")
    require(d["editing"]["preserve_first_opening"] if "preserve_first_opening" in d["editing"]
            else d["editing"]["first_episode_opening"] == "keep", "First opening")
    require(d["editing"]["last_episode_ending"] == "keep", "Last ending")
    require(d["editing"]["redundant_recaps_and_previews"] == "remove_when_reliably_identified",
            "Approved recap/preview removal")
    require(d["editing"]["uncertain_segments"] == "preserve_and_offer_review", "Protect unique story")
    require(d["integrations"]["nexus_direct_authorized_specialist_actions_without_control"], "Direct actions")
    require(d["integrations"]["control_owns_complex_workflow_orchestration"], "Control boundary")
    require(d["execution"]["irreversible_actions_require_confirmation"], "No destructive automation")
    require(d["execution"]["first_acceptance"] ==
            "real_selected_source_to_verified_searchable_organized_openable_file", "First slice acceptance")


def validate_json_schemas_and_examples() -> None:
    schema_paths = sorted((ROOT / "contracts").glob("*.v1.schema.json"))
    require(len(schema_paths) == 7, f"Expected seven versioned schemas, found {len(schema_paths)}")
    schemas = {}
    for path in schema_paths:
        schema = json.loads(path.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        schemas[path.name] = Draft202012Validator(schema, format_checker=FormatChecker())
    examples = [
        ("episode-assembly.v1.schema.json", "contracts/examples/episode-assembly.e1e3.synthetic.json"),
        ("transfer-receipt.v1.schema.json", "contracts/examples/transfer-receipt.synthetic.json"),
    ]
    for schema_name, example_path in examples:
        schemas[schema_name].validate(load(example_path))


def validate_episode_assembly_example() -> None:
    fixture = load("contracts/examples/episode-assembly.e1e3.synthetic.json")
    episodes = fixture["input_episodes"]
    require([e["order"] for e in episodes] == [1, 2, 3], "Episode order")
    require(fixture["source_assets_preserved"] is True, "Must preserve originals")
    require(fixture["preserve_first_opening"] and fixture["preserve_last_ending"], "OP ED")
    require(fixture["preserve_uncertain_content"], "No uncertain story cuts")
    segments = fixture["segments"]
    require(all(s["start_ms"] < s["end_ms"] for s in segments), "Invalid timeline")
    by = {(s["source_id"], s["semantic_class"]): s["decision"] for s in segments}
    require(by[("e1", "OPENING")] == "KEEP", "OP1 must be kept")
    require(by[("e3", "ENDING")] == "KEEP", "ED3 must be kept")
    for e in ("e1", "e2", "e3"):
        require(by[(e, "STORY")] == "KEEP", f"Lost story {e}")
    for e in ("e2", "e3"):
        require(by[(e, "OPENING")] == "CUT", f"Redundant OP {e}")
    for e in ("e1", "e2"):
        require(by[(e, "ENDING")] == "CUT", f"Redundant ED {e}")
    require(all(s["decision"] == "CUT" for s in segments
                if s["semantic_class"] in ("RECAP", "NEXT_EPISODE_PREVIEW")),
            "Synthetic redundant recaps and previews must be cut")


def check_acceptance_is_not_faked() -> None:
    acceptance = load("spec/acceptance-cases.v1.json")
    require(acceptance["current_overall_status"] == "SPECIFIED_ONLY", "No runtime proof claimed")
    cases = acceptance["cases"]
    require(len(cases) >= 20, "Acceptance inventory incomplete")
    keys = [c["id"] for c in cases]
    require(len(keys) == len(set(keys)), "Duplicate acceptance IDs")
    for key in ("ACQ-001", "ACQ-002", "ACQ-003", "FUS-001", "CLOUD-001",
                "UI-001", "TXT-001", "IDX-001", "VID-001", "CTRL-001", "DATA-001", "REL-001"):
        require(key in keys, f"Missing required acceptance: {key}")
    for key in ("UI-027", "UI-028", "ACQ-027", "ACQ-028", "CLOUD-027", "ORG-027", "BOOK-027", "FUS-027", "FUS-028"):
        require(key in keys, f"Missing November 27 owner acceptance: {key}")
    for case in cases:
        require(case["status"] == "SPECIFIED" and case["actual_evidence"] == [],
                f"Unsubstantiated implemented/pass claim: {case['id']}")
        require(not case["owner_accepted"], "Cannot assume owner acceptance")
    imports = load("spec/source-import-manifest.template.json")
    require(imports["status"] == "NOT_IMPORTED" and not imports["source_modules"],
            "Cannot claim integration without source manifest")


def check_public_repo_and_ui_copy() -> None:
    tracked = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT,
                             capture_output=True, check=True).stdout.decode("utf-8").split("\0")
    banned_suffixes = (".jks", ".keystore", ".p12", ".pfx", ".pem", ".key", ".apk",
                       ".mp4", ".mkv", ".epub", ".cbz", ".cbr", ".torrent")
    banned_names = (".env", "credentials.json", "secrets.json", "google-services.json")
    marketing = re.compile(
        r"\b(?:welcome to|unlock your|start your journey|supercharge|"
        r"experience the future|your world awaits|powered by ai|reimagine your|"
        r"discover endless|unleash the power)\b", re.I)
    for rel in filter(None, tracked):
        p = Path(rel)
        require(not rel.lower().endswith(banned_suffixes) and p.name.lower() not in banned_names,
                f"Public repo forbidden binary/secret candidate: {rel}")
        # This is a targeted static copy check, not comprehensive manual UI approval.
        if (p.suffix.lower() in (".xml", ".kt", ".java", ".html", ".tsx", ".jsx")
                and any(part in p.parts for part in ("app", "android_app", "ui"))):
            content = (ROOT / p).read_text(encoding="utf-8", errors="replace")
            require(marketing.search(content) is None, f"Marketing/slogan phrase found in {rel}")
    ui_contract = (ROOT / "docs/07-UI-DESIGN-CONTRACT.md").read_text(encoding="utf-8")
    require("SLOGANS ARE PROHIBITED" in ui_contract, "UI slogan ban missing")


if __name__ == "__main__":
    check_document_set()
    check_approved_decisions()
    validate_json_schemas_and_examples()
    validate_episode_assembly_example()
    check_acceptance_is_not_faked()
    check_public_repo_and_ui_copy()
    print("PASS: canonical docs, owner decisions, JSON Schemas, synthetic E1-E3 example,")
    print("      acceptance evidence honesty, public-repo file gate and targeted UI copy gate.")
    print("NOTE: No Android app, donor source integration, real downloads/media or phone")
    print("      acceptance has been tested by this specification-only check.")
