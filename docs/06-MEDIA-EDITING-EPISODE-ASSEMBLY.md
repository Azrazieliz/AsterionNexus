# Media Processing: Automatic Episode Assembly and video editing

**Mandatory user scenario:** Given consecutive episodes 1, 2, 3 of a series, Nexus automatically produces one continuous video with the opening of episode 1, the full meaningful contents of episodes 1+2+3, and the ending of episode 3. Intermediate OP/ED sequences are removed. **Redundant recaps and next-episode previews are also removed when reliably detected.** This is the primary concrete user intent for "automatic video editing"; generic editor controls alone do not fulfill it.

## First-class operation: assembled continuous episodes

```
Input:
 E1: [OP1] [story1] [ED1] [next-episode preview 1]
 E2: [recap2] [OP2] [story2] [ED2] [next-episode preview 2]
 E3: [recap3] [OP3] [story3] [ED3]
Target if all redundant segments were reliably identified:
 FINAL: [OP1] [story1] [story2] [story3] [ED3]
```

This illustration is not a fixed order or a universal rule for actual anime/media. Files may contain cold opens, post-credit scenes, teasers, multiple endings, different OPs, mid-episode previews, recap-specific plot content or credits interleaved with story. Preserve **every unique meaningful scene** even if adjacent to OP/ED; do not remove episodes' core content merely because a classifier called it "recap".

### Input/selection

- Select a **collection, series, season or consecutive multipart files** for automatic processing; not every acquired video by default.
- Resolve episode ordering from trustworthy source series metadata, episode numbers, explicit owner correction and inspected media, not filename lexical sorting alone (E2 vs E10). Detect duplicates, missing episodes, different cuts, mismatched language/dub, resolution/frame rate/audio tracks, mismatched aspect ratios and partial/corrupt sources.
- Allow configurable input ranges and selection of special episodes (OVA, recap episodes, openings/credits variants); don't silently merge a mismatched season or special.
- TeraBox streams may be staged in bounded verified segments/caches; no unbounded full phone mirror or cleanup of original cloud data.
- Maintain project with source account/location, source hashes or strongest verified available signatures, exact byte ranges and order, chosen editing policy, decision evidence and reproducible timeline segments.

### Intelligent segmentation and classification

Use complementary evidence rather than fixed timestamps:
1. Decode/inspect bounded time windows and scene boundaries with audio waveform energy, acoustic/visual fingerprints and perceptual similarities across consecutive episodes.
2. Compare repeated OP/ED sequences, including localized credits, aspect differences, short bumper edits and shifted audio. A repeated opening doesn't need to be identical byte-for-byte.
3. Use chapter tags, subtitles and source metadata as additional **uncertain evidence**, never lone irreversible authority.
4. Distinguish **unique cold opens, post-credits story, credit-overlaid story, genuine recap scenes vital to continuity, previews with unique canon** from redundant OP/ED/recap/preview sequences.
5. Preserve first opening and final ending by default. Intermediate repeated openings and endings are candidate cuts. Detect/remove redundant recaps and next-episode previews **only at sufficient validated reliability**.
6. Each candidate cut has exact timestamp/frame bounds, classification, score/calibration, cross-episode matching evidence, model/policy version and decision `CUT|KEEP|REVIEW`. Do not fake precision or confidence.
7. Low-confidence/missing evidence means **KEEP** and optionally propose a review; do not delete unique story scenes to maximize cut rate.
8. Keep source metadata and original files intact. Owner clarification 2026-11-28: a normal MP4 with the main audio track (or mute) is sufficient; professional multi-track export is not required.

### Editable non-destructive timeline and rendering

`AssemblyProject` owns ordered `SourceSegment` entries, exact in/out times, frame/time base, sound/subtitle mappings and edit decision list (EDL). Every render is an **independent final Nexus content item** with optional links to sources. No source overwrite; keep reversible project history, cuts and manual corrections. User can preview proposed joins, override classification and save reusable per-series correction rules without hard-coding wrong global timestamps.

- Render with accurate timestamp normalization, track mapping and concatenation; prefer safe stream-copy when codecs/profile/timebases/container/segment boundaries permit, otherwise transcode only needed segments or whole output where necessary.
- Maintain motion continuity and avoid accidental black-frame insertions, audio pops, loudness jumps, discontinuities, wrong color range/aspect, codec mismatches, subtitle desync and broken seeking.
- Use the main audio track or mute, and retain source episode provenance. Multiple audio/subtitle tracks and professional authoring are outside the immediate export scope.
- Validate output with independent parser/decoder or representative playback: nonzero duration, exact ordered segment coverage, expected audio/video tracks, frame continuity, seek integrity, no truncation and correct stored destination.
- Android isolated, resumable processing with CPU/GPU budgets; checkpoint segmentation, EDL, temporary output and final checksum; prevent duplicate publications and orphan caches on process death.
- Actual export support constrained by licensed codec/runtime and device performance; never claim hardware acceleration without measured proof.

## Related video-editing scope

The owner explicitly prioritized **automatic cutting, trimming, highlights, pacing, complete montages and scene selection**, and automatic processing for selected collections. Episode assembly comes first; the broader subsystem should reuse segmentation, media parsing, timeline, output encoder and QA:
- Automatic extraction of highlights (scene-motion/audio/story cues grounded in real frames), user-adjustable length and pacing.
- Manual/automatic trims, cuts, reorder and montage assembly with a real timeline and clip review.
- Optional image/video conversion and derivative export, resolution/aspect/bitrate selection and metadata preservation.
- Later enhancements such as sound alignment, captions or specialist Art/Sound generation are supported via direct authorized specialist actions, but **not** forced dependencies to finish a basic episode merge.
- Any media "improvement" or conversion is independently versioned, never a hidden mutation of the only copy.

## Specific acceptance matrix

| Test | Required proof |
|---|---|
| 3 episodes, same OP/ED, repeated recap/preview | E1 OP retained, E1+E2+E3 full unique content, E3 ED retained, duplicate OP/ED/recap/preview omitted; one valid output |
| 10+ episodes and E10 lexical ordering | Correct sequence and full story content |
| Different opening/ending versions | Retain meaningful unique variants unless policy+proof validates removal; classification recorded |
| Episode cold open, post-credit unique scene | Scene preserved even if within common intro/outro window |
| Recap partly unique | Preserve unique material; uncertain recap kept/reviewed |
| Different resolutions, FPS/timebases, audio streams | Correct decode/concat/transcode strategy and synchronized playback |
| TeraBox cloud streaming and cache exhaustion | Bounded verified staging, graceful resume, no unbounded local copy |
| Interruption/restart and duplicate request | One published verified result, resumable progress, no orphan file loss |
| Corrupt/missing/unauthorized source | Clear failure/skip per item, no fabricated composite |
| Manual correction | Re-render new version with saved decisions, source files and prior final unaffected |

The acceptance oracle should compare an annotated set of **real, authorized, representative episodes** with reference keep/cut frame intervals and continuous final playback; synthetic clips may test code paths but cannot certify actual OP/ED/review accuracy. A single incorrectly removed unique narrative segment is a **critical failure**. The final merged video should reopen from Nexus Library and stay indexed/searchable after reboot.

## Direct source relationship and other apps

Nexus owns source segments, media editing, export and library state. **Control** may optionally orchestrate a separate multi-app creative workflow; it is never required for ordinary automatic Episode Assembly. Sound can generate/rework soundtrack and Art can generate visual assets through independent scoped calls, with provenance and owner approval. Neither gains authority to rewrite Nexus original files.

## 2026-11-27 output convention

The selected assembled series/episode collection becomes Series Name.mp4; a film becomes Film Title.mp4. Retain all source provenance and unique narrative content and publish verified outputs to TeraBox. Library video previews autoplay muted when centered and open to actual fullscreen playback on tap. See [16](16-APPROVED-EXPERIENCE-AND-ORGANISATION.md).
