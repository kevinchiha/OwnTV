# Sports: OK previews in the box, like the guide

## Goal
On a match's Channels tab, OK on a channel plays it in the video box on the right instead of
opening full screen. OK on the channel already in the box grows it to full screen with the same
animation the guide and Search use. Back from full screen shrinks it into the box, still playing,
with focus on the same row. The "Watch live" note, the channel count and the filter buttons move
from the top of the list into the right column, under the video.

## Scope
Fork-only rules in `kevbox/live/LiveVideoStage.kt` plus a small fork-only helper, and `KevBox:`
hunks in `SportsMatchScreen.kt`, `SportsScreen.kt` and `OwnTVShell.kt`, with UPSTREAM-SYNC rows.

**Gate:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew testStandardDebugUnitTest lintStandardDebug && bash tools/kevbox/check-divergence.sh`

## Acceptance criteria
- **AC-1** `isBackToGrid` returns true for `MainSection.SPORTS` under the same conditions as LIVE_TV and SEARCH (FULLSCREEN, `zapSource == LIVE_TV`, live on ExoPlayer, no catch-up, not rewound), and only when the box belongs to Sports (`boxSection == SPORTS`). Pass the box section into the rule. Without that check, the hub's "On channels now" rows (SportsHubScreen.kt:275) would keep the stream with no box drawn. The existing test `notBackToGridOutsideLiveTvAndSearch` is updated. New tests cover SPORTS with a Sports box (true) and SPORTS without one (false).
- **AC-2** `sectionShown` keeps the box for the screen that owns it, Sports included. It stops the stream when the new screen is not LIVE_TV or EPG, or when the box belonged to SEARCH or SPORTS. Rule: `stopPreview = !liveScreen || boxSection == SEARCH || boxSection == SPORTS`. Today's results for LIVE_TV/SEARCH/no box are unchanged. Tests cover Sports keeping its box, and Sports to LIVE_TV, EPG and HOME stopping it.
- **AC-3** Focus on a channel row plays nothing. The 700 ms focus preview effect is gone (SportsMatchScreen.kt:124-130).
- **AC-4** OK on a row uses `searchOk(ch.id, box?.id, previewAvailable)`. Here `box` is the stage's box channel when `boxSection == SPORTS`, and `previewAvailable` is the same condition Search uses.
  - PREVIEW: `putInBox(ch, SPORTS)`, then `liveVm.onChannelFocused(ch)` and `liveVm.playPreview(ch)`. This does not record a play and does not start a sports session.
  - WATCH (the boxed channel, or any channel when preview is off): `vm.playedFrom(...)` and `onPlayChannel(ch, zap)` as today.
- **AC-5** The match page's WATCH branch calls `videoStage.putInBox(ch, SPORTS)` before `onPlayChannel`. The shell's shared Sports `onPlayChannel` (OwnTVShell.kt:1491) does not, because the hub and the league page use it too. The shell keeps `ensurePlayingByIdAsync`, not `watchFromGuide`, so CH+/− walk the match's channels. The previewing stream moves to full screen without a reload, through `ensurePlaying` → `startTune` → `expectPromotion`. The grow animation comes from the box and player sites with no new animation code.
- **AC-6** The Sports page stays composed behind full screen, the way Search does (OwnTVShell.kt:1164 adds `selectedSection == MainSection.SPORTS`). The match page, Find channels result and filter survive a trip to full screen and back.
- **AC-7** Back from full screen with AC-1 true runs today's back-to-grid path: bridge, the box takes the playing channel (`liveVm.previewChannel`, so after CH+/− it is the new one), the stream keeps playing. Focus returns to that channel's row. If the channel is not in the list, focus goes to the row OK was pressed on (`vm.lastPlayedChannelId`), then the first row. The rule lives in a pure fork-only function with tests: `sportsReturnChannelId(playingId, lastPlayedId, shownIds)`.
  - The page takes `onScreen` and `restoreFocus`/`onRestored` like SearchScreen.
  - The LazyColumn gets a `LazyListState`. Every channel row gets a `FocusRequester` keyed by channel id. Restore scrolls to the row's index (counting the items above it), then requests focus, retrying as SearchScreen.kt:312-331 does.
  - The focus effect at SportsMatchScreen.kt:133-137 returns early while `!onScreen`. `onScreen` is not one of its keys, because as a key the effect would fire on Back and race the restore.
- **AC-7b** The box is Sports' only while the match page shows it. Leaving the match page any other way (Back to the hub, opening the league page, opening another match) stops the preview and clears a Sports box.
- **AC-8** Sports extras still work after WATCH: score line, other-channels panel, goal toast, `switchToMatch`. The goal toast's `pendingMatch` opens its match even when Sports is already the shown section, because SportsScreen collects `pendingMatch` in a `LaunchedEffect` instead of reading it once in `LaunchedEffect(Unit)`. The toast's branch that already knows a channel (OwnTVShell.kt:1804) also sets `pendingMatch` to that match, so Back from full screen lands on the right match page. `returnToMatch` is dropped: remove the write at SportsScreen.kt:82 and the read at :51-54. The page stays composed, so nothing needs it.
- **AC-9** Back on the match page: one press stops the preview, clears a Sports box and goes back, the same as today's single press (SportsMatchScreen.kt:113).
- **AC-10** Key hints on the Channels tab read like Search's (`kevbox_key_preview` for OK when the focused row is not boxed, `content_key_watch` when it is). The ▲▼ hint and the Hold OK "Always use for <league>" hint stay. Hold OK still opens that choice.
- **AC-11** The right column shows `GuideVideo` with `channel = box ?: focused` and `showVideo = active && box != null`. The video keeps today's size on every tab.
- **AC-12** On the Channels tab only, the right column shows this block under the video, with the same text and order as today:
  - no-playlist note
  - kick-off note (`ClosedNote`) with its "Watch live" title
  - `FindChannels` and its button
  - "not on channels" empty message
  - "Watch live · N channels" with the preferred-language line
  - `FilterChips`
  The left column becomes the tabs plus the channel rows only. The block shows even when the list is empty, which is why it has its own condition separate from the video's `shown.isNotEmpty()`. It leaves about 84 mpx at the bottom for the key hints. Other tabs show the video only.
- **AC-13** D-pad:
  - Right from any channel row goes to the right-column block's first focusable (chips, or the Find channels button).
  - Left from the block returns to the row that last had focus, using Task 2's per-row `FocusRequester`s and `focusProperties { left = ... }` on the block. If that row has left the list (Find channels finished, the filter changed), Left goes to the first row, or to the Channels tab when the list is empty.
  - Up from the first row goes to the tabs.
  - Down from the tabs, with an empty list and the Find channels button showing, reaches that button through an explicit `focusProperties { down = ... }`. Plain focus search does not reliably cross columns.
  - Focus after the filter panel closes lands on the chips again.
- **AC-14** `check-divergence.sh` passes:
  - Every touched upstream hunk carries `// KevBox: ...` (with the colon).
  - SportsScreen.kt gets a new UPSTREAM-SYNC row.
  - The OwnTVShell.kt and SportsMatchScreen.kt rows are updated, and their grep proofs still match. The SportsMatchScreen row greps the ClosedNote item line and `nowTitle = video?...`, and both tasks move or rename that code. Each task fixes the row for its own changes, and the tasks run one after the other.
  - New fork-only files carry the fork-only header.
- **AC-15** `verifyI18nLiterals` passes. Each task classifies its own new technical literals with `python3 tools/i18n/check_hardcoded_strings.py classify-safe ...`.

## Non-goals
- Hold Back opening the box from Sports (HoldBack.kt): its path re-arms the category zap list, which is wrong for a match.
- Keeping the stream when playback has fallen back to mpv: Search stops it there too.
- Other tabs' layouts.

## Decisions
- Keep Sports composed behind full screen over rebuilding it, so the 600 ms bridge finds the box and Find channels is not reset.
- Reuse `searchOk` over a Sports copy: it only compares ids.
- A preview is not a play: `playedFrom` runs at WATCH only, so "last played" and the session stay honest.

## Tasks

### Task 1: Stage rules
**Delivers:** AC-1, AC-2, the pure part of AC-7, AC-15 (its literals). The shell call to `isBackToGrid` changes in Task 2 if the signature changes here. Task 1 updates every call site so the build stays green.
**Files:** `app/src/main/java/tv/own/owntv/kevbox/live/LiveVideoStage.kt`, `app/src/main/java/tv/own/owntv/kevbox/sports/SportsBox.kt` (new, fork-only), `app/src/test/java/tv/own/owntv/kevbox/live/LiveVideoStageTest.kt`, `app/src/test/java/tv/own/owntv/kevbox/live/SearchBoxTest.kt`, `app/src/test/java/tv/own/owntv/kevbox/sports/SportsBoxTest.kt`
**Interfaces:** `fun sportsReturnChannelId(playingId: Long?, lastPlayedId: Long?, shownIds: List<Long>): Long?`
**Does:** AC-1 and AC-2 rule changes, the return-focus rule, tests.
**Verify:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew :app:testStandardDebugUnitTest --tests 'tv.own.owntv.kevbox.live.*' --tests 'tv.own.owntv.kevbox.sports.*'`

### Task 2: OK rules, box, full screen and Back
**Delivers:** AC-3 to AC-11, AC-14 (its files), AC-15
**Files:** `app/src/main/java/tv/own/owntv/features/sports/SportsMatchScreen.kt`, `app/src/main/java/tv/own/owntv/features/sports/SportsScreen.kt`, `app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt`, `UPSTREAM-SYNC.md`, string files only if a new string is truly needed
**Does:**
- OK rules, box, full screen and Back as the ACs above say.
- Add the list state and the per-row requesters. Task 3 reuses them.
- Keep shell edits short and put logic in fork-only helpers (`kevbox/sports/SportsBox.kt`).
- In OwnTVShell.kt, use lambda values only, never a local `fun`. A local `fun` there passes the debug gate but fails ART's verifier in release (see the comment at OwnTVShell.kt:997-999).
**Verify:** the full Gate.

### Task 3: Header block into the right column
**Delivers:** AC-12, AC-13, AC-14 (its file), AC-15
**Files:** `app/src/main/java/tv/own/owntv/features/sports/SportsMatchScreen.kt`, `UPSTREAM-SYNC.md`
**Design skills:** refactoring-ui
**Does:** move the block out of `channelsTab` into the right column under `GuideVideo`, with its own condition, and wire the focus rules.
**Verify:** the full Gate.

## Build log
- plan check: 10 problems. Fixes: box set by the match page, not the shell; back-to-grid needs a Sports box; restore falls back to lastPlayedChannelId; list state and per-row requesters move to Task 2; onScreen guard is an early return, not a key; returnToMatch dropped; Back is one press; the goal toast's channel branch sets pendingMatch; no local fun in the shell; proof rows and the explicit Down/Left fallbacks. All went into the plan.
- baseline: gate green on kevbox-live b8961664.
- Task 1: landed (92a02ebb). `isBackToGrid` now ends with a required `boxSection: MainSection?`; the shell's exitPlayerWith already passes `liveVideoStage.boxSection` (KevBox-commented, the UPSTREAM-SYNC grep `kevbox.live.isBackToGrid(` still matches), so Task 2 needs no shell change for AC-1. `sportsReturnChannelId` is in kevbox/sports/SportsBox.kt; add Task 2's helpers there.
- Task 2: landed (2a01290b). Gate green: 663 tests, lint 0 errors, divergence 0 failures. Choices: the shell keeps Sports composed behind full screen only while `liveVideoStage.boxSection == SPORTS` (the match page owns the box), so the hub's own full-screen rows rebuild as before; `previewAvailable = active && videoStage != null` like Search, so the Live preview setting is no longer read; the ▲▼ hint now reads `content_key_channels` ("Channels"), since moving no longer previews; the goal toast's channel branch sets `pendingMatch` only when Sports is the shown section; a `pendingMatch` arriving while the page is on screen clears a Sports box, behind full screen it does not (that box is the playing stream); `returnToMatch` is removed from SportsPlayback.kt (KevBox comment, proof added). New helper `clearSportsBox(stage)` in SportsBox.kt, used by Back, the league page and the collector. For Task 3, in SportsMatchScreen.kt: `rowFocus: ChannelRowFocus` (`rowFocus.of(channelId)` gives the row's FocusRequester; wrap requestFocus in runCatching), `listState` (the page's LazyListState), `focused` (the row that last had focus), and `channelRowsStart(state)`, the list index of the first channel row. That function counts the note and the count/chips items above the rows, so when Task 3 moves that block out of the list it must change to `2`.
- Task 3: landed (911f0382). Gate green: 666 tests, lint 0 errors, divergence 0 failures. Layout: `WatchLiveBlock` sits in the right column under GuideVideo on the Channels tab only, 39 mpx under the video (the column gap, clear of its shadow), at the top level with the score box when there is no video; groups 24 apart; the column now ends 84 mpx above the bottom; the block scrolls (verticalScroll) when it runs past that; FilterChips is a FlowRow so chips wrap in the narrower column. `channelRowsShown(state)` drives both the rows and the count/chips, so they stay in step; `channelRowsStart` became `CHANNEL_ROWS_START = 2` (Back restore uses it). Focus: rows carry `right = blockFocus` and the first row `up = tabFocus`; the tabs carry `down = blockFocus` only when the tab has no rows and the block has a button or chip (`blockFocusable`); Left out of the block is an `onExit` on the block's focusGroup (a group's own `left` does not apply to its children), using `sportsBlockLeftRowId` in SportsBox.kt, scrolling the list first when that row is off screen. The filter panel is a Dialog, so the chip keeps focus underneath; the focus-follows-search effect now skips while the block has focus, so a filter change that empties or refills the list no longer pulls focus to the tabs or the first row. Needs a device: the onExit redirect, the chip wrap and scroll in the docked layout, and key hints that still describe OK on a row while focus is on the chips.
- gate on 0f0e8c68: green (tests, lint, divergence 0 failures); debug APK on kevbox_a14.
- device check (kevbox_a14, Man City v Brighton): OK previews in the box, focus stays, hint flips to Watch; OK again grows to full screen with no reload (caught mid-grow); Back shrinks to the box, still playing, focus on the same row; Right from row 3 goes to "Any", Left returns to row 3; OK on another row swaps the box; Back on the page goes to the hub with no audio player left running.
- ocr: 1 comment (medium): per-match state (`focused`, `listState`, `rowFocus`, focus effect keys) survives a goal-toast match switch on the kept page.
- review: no critical. important: a Sports box outlives the page through the mini player (SportsMatchScreen.kt:130); stream with no box when the list empties (:309). minor: focus lost after Find channels with listed rows (:156); restoreFocus lingers after a hub full-screen trip (SportsScreen.kt:106); channelRowsShown/blockFocusable untested (:380-388).
- triage: all six → fix (one fixer).
- fix round 1: f44a29e3. Gate green: 668 tests, lint 0 errors, divergence 0 failures.
  1. A `DisposableEffect(videoStage)` above `page ?: return` empties a Sports box when the match page leaves composition (box only, the stream stays for the mini player); the shell keeps the page composed behind full screen while the box is Sports', and the goal toast swaps the match inside the same page, so full screen never disposes it.
  2. `withVideo = box != null || (state.hasPlaylist && shown.isNotEmpty())`.
  3. `focused`, `listState` (now `remember(m.key) { LazyListState() }`) and `rowFocus` are keyed on `m.key`; the first-row focus effect has `m.key` as a key.
  4. The focus effect targets only drawn rows (Channels tab and `channelRowsShown`) and falls back to `tabFocus` when `requestFocus` returns false or throws.
  5. SportsScreen calls `onRestored()` when `restoreFocus && onScreen` and the hub or league page shows; the restore loop stops on the second try with no row ids.
  6. `channelRowsShown` and `blockFocusable` live in kevbox/sports/SportsBox.kt over (search, hasPlaylist, total), with one-line wrappers in the page; SportsBoxTest table-tests every ChannelSearch case. UPSTREAM-SYNC rows gained proofs for items 1, 2 and 5.
