# Nexus premium UI and interaction contract — zero slogans

**P0, owner mandated (2026-11-26):** **SLOGANS ARE PROHIBITED THROUGHOUT THE RUNTIME INTERFACE.** The existing Core, Downloader and Rounder UIs were rejected as messy; copying one as the final Nexus shell is not permitted. The **highest achievable premium quality** is a design/implementation/acceptance requirement, not a promise from attractive adjectives.

## Absolute content ban

- No slogans, inspirational phrases, product pitches, marketing/branding taglines, gimmicky AI claims, promotional headlines, "welcome to the future", "unlock", "supercharge", "your universe", decorative status prose, fake completion banners or tutorial fluff in navigation, welcome, home, splash, settings, loading, empty state, errors, controls, notifications, accessibility announcements, widgets or store-facing in-app content.
- Asterion brand **name and a restrained unique mark** are allowed as normal identity. Functional labels, true file metadata, actionable hints, concise accessibility text and concrete error descriptions are allowed. No exception for onboarding/splash hero illustrations; avoid any nonfunctional greeting surface.
- No fake "connected", "AI ready", "processing complete", synthetic thumbnails, decorative counters, misleading badges, inactive buttons, "provider supported" labels or raw developer diagnostics as default UX.
- Do not hide a slogan in string resources or translation/localization assets. Add a UI copy-review gate examining all user-visible strings and screenshots before each accepted release.
- UI is **English** initially, with consistent capitalization/terms and human-readable source names; keep provider/model implementation IDs and JSON out of normal UI.

## Design objective and interaction principles

A premium **media-centered Android application** where the content occupies most available space, typography and spacing establish hierarchy, motion reinforces state, actions are discoverable, and every screen is coherent. Premium does **not** mean extravagant gradients, gratuitous glass effects, gigantic unused whitespace or tiny typography. Keep context and high information density without visual disorder.

- One adaptive intelligent Library launch, not a Home/Welcome screen or the three donor apps' tabs. Contextual functions surface based on selected content and workflow; advanced actions remain **readily visible** and purposeful (not buried behind ten screens, hidden-only gestures or a permanent debug toolbar).
- A considered native design system: consistent 4/8dp spacing rhythm, scale-aware typography, responsive grid, restrained semantic accent/color palette, compliant contrast, predictable iconography and states. Final tokens selected and verified against actual implementation, not arbitrary branding guesses.
- Distinctive Asterion icon, genuine visual hierarchy, full-resolution/correct-aspect artwork, beautiful readable books and videos, honest loading/error placeholders and respectful privacy; do not rebrand old Rounder/Kuroha/Nocturne styles as a "new premium" design.
- Native touch: comfortable hit areas (target **at least 48dp**), avoid small edge arrows, unreachable bottom actions, hidden scroll regions, unbounded horizontally scrolling page, blocked Back navigation, gesture conflicts or orientation/keyboard overlap. Support Android navigation/insets, gesture navigation, accessibility, TalkBack and hardware/system settings.
- On SM-S948B Android 17, verify portrait 1080x2340 and landscape, split-screen/IME, font scale, density, low-power conditions, full-screen video and media reader; do not infer from emulator-only screenshots.
- Fluid scroll and media transitions, predictable backstack, fast visible result feedback; do not block frame/render thread with cloud/network/AI work. Use progressive real thumbnails from source, bound image memory and de-duplicate loaded requests.
- Never hide a critical failure. Present concise exact stage and action: retry, authorize, open settings, review, resume; avoid leaking secrets or raw unredacted network traces.
- Search returns progressive results from local/cloud/web with per-source truth, filters and no invented or merged-away distinct results. Online discovery taps acquire exact selected content rather than redirect; existing library results open files.
- Selection actions stay consistent: tap opens existing content or starts acquisition on online discovery results, long-press selects/batch actions; destructive or risk-bearing actions show honest impact and confirmation; reversible actions show undo/recovery.

## Screen model (contexts, not independent navigation destinations)

**2026-11-27 controlling navigation:** exactly three permanent entries, Library / Organising / Downloading. See [16](16-APPROVED-EXPERIENCE-AND-ORGANISATION.md) for approved submenus, charcoal/silver, content-first adaptive media previews, tile restrictions, swipe arbitration and canonical command deduplication. Cloud belongs inside Library, Transfers inside Downloading, taxonomy/review/bridges inside Organising, and rating belongs on opened-file metadata only.

| Context | Primary UI | Persistent useful actions |
|---|---|---|
| Adaptive Library | Actual local/cloud content previews, folders, collections, recent reading, projects and smart relationships | Search, switch account/location, filter/sort, source/add, select, contextual actions |
| Discover / Acquire | Federated results, selected provider/creator/album, real original previews and source status | Search, subscribe, sync, independent backfill, selected download, inspect jobs |
| Transfers | Actual active/completed/failed job ledger, exact bytes and sources | Pause, resume, cancel, retry, inspect failure and destination |
| Fusion / Organising | Automatic activity, canonical tags and per-character view, uncertainty/confirmation review, bridges | Correct tag, add tag, review, undo and backup; owner rating is available from the opened file, not here as a module |
| Reader | Real page/document/EPUB/comic view | Gesture-aware mode, bookmarks, chapter navigation, zoom, edition and translation status |
| Media player | Native-aspect immersive playback | Seek, tracks, subtitles, speed, play next/replay/shuffle, orientation/lock, editing |
| Video assembly/editor | Series/episode selection, proposed segment timeline, preview and render receipts | Edit keep/cut boundaries, review uncertainty, assemble, resume, open output |
| Settings / data | Explicit concise credentials/accounts/permissions, retention, model/storage budgets, export, diagnostics | Revoke, verify, backup, migrate, privacy, signed update |

These are **contexts**, not nine mandated navigation tabs. Build prototyped flow maps based on realistic media samples and choose an appropriate compact adaptable navigation. Existing supported functionality may be routed through native specialized viewer surfaces; a WebView reader only when its behavior is proven equivalent and visually integrated.

## Reusability without visual laziness

- Internal design tokens, reusable composable primitives, media tiles, source chips, bottom sheets, error/empty/loading states, typography and motion rules. One clear icon system (original licensed assets), not mismatched default icons or copied visual patterns.
- Native implementation can use Kotlin/Compose or an equivalent tested shell; do not replace accepted Rounder media rendering with an inferior rewritten generic viewer merely for framework purity.
- Draw UI from real feature state with stable IDs and lifecycle awareness. "Progress" is actual byte/step evidence, not a spinning indicator attached to a dead engine.
- Theme uses owner-approved charcoal and silver, correct color contrast and legibility; no arbitrary neon, gratuitous shadows or ornamental boxes.

## Premium acceptance checklist

Each critical screen must pass:
1. **Content relevance**: primary content visible immediately; no slogan, greeting, pitch, dashboard filler or implementation internals.
2. **Function**: every displayed action is working and enabled only when the real capability exists. No dead taps, wrong destinations or pending actions misreported as successful.
3. **Visual polish**: consistent spacing/typography, actual media, stable tile geometry and crop policy, tasteful transitions, no truncation/overlap/below-screen controls.
4. **Accessibility**: touch targets >=48dp where practical, semantic labels, contrast, large text, screen reader navigation and keyboard/landscape checks.
5. **Performance**: smooth scrolling in representative 300k+ library through indexed paging, bounded memory and real cloud progressive thumbnails; no main-thread network.
6. **Error recovery**: offline/error/auth/empty/partial index/cancel state and actionable retry with accurate preserved state.
7. **Cross-journey**: start from search, open media, edit or organize, resume, Back and reopen without losing position/selection.
8. **Slogan review**: manually examine every default runtime screen/notification/empty/error; static string scan helps but cannot alone certify the ban.
9. **Owner screenshots/recording review on physical Samsung**; iterate until visibly premium, not just source compiled.
10. **Functional regression**: preserve accepted Rounder reader/player controls and Core/Downloader behavior under the new shell.

Owner acceptance of a UI requires actual screenshots and demonstrated workflows, not description, mockup alone or "premium" terminology. No custom slogan text is to be invented during implementation.
