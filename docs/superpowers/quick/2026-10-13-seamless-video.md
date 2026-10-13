# Seamless Live video

## Goal
In KevBox Live's Live TV grid, a channel's video grows from the top box to full screen and shrinks back on Back without the stream stopping, pausing or changing video window.

## Scope
A new `app/src/main/java/tv/own/owntv/kevbox/live/LiveVideoStage.kt`, plus KevBox-commented edits to `features/shell/OwnTVShell.kt`, `features/epg/GuideStage.kt`, `features/epg/EpgScreen.kt`, `features/live/LiveViewModel.kt`, `player/MpvVideoSurface.kt`, `kevbox/ui/AutoSetup.kt` and `UPSTREAM-SYNC.md`.

**Gate:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew testStandardDebugUnitTest lintStandardDebug && bash tools/kevbox/check-divergence.sh`

`verifyI18nLiterals` runs inside the build (it hangs off `preBuild`). A new string literal that fails it gets classified with `python tools/i18n/check_hardcoded_strings.py classify-safe --path <file> --text '<text>' --category <category>`, never suppressed. `check-divergence.sh` reads committed state only, so commit before running it.

## Acceptance criteria
- **AC-1** In the Live TV grid, OK on a channel shows its video in the top box, with the LIVE badge, the channel line and the bottom shading drawn over the video, inside the box's 28 mpx rounded corners.
- **AC-2** With the box playing, Left opens the categories sidebar, and the sidebar covers the video wherever the two overlap.
- **AC-3** The Live TV video, on ExoPlayer, has one video window (one `ExoPreviewSurface` call site, in `LiveVideoLayer`). It stays composed while the video moves between the box, full screen and the mini-player, so no move hands the stream a new surface.
- **AC-4** OK on the channel already in the box grows the video from the box's position and size to full screen in 320 ms. The grid stays drawn around it until the grow ends, and the player controls (`PlayerHud`) appear only after that.
- **AC-5** In full screen with the controls hidden, Back shrinks the video into the box in 320 ms. The sound keeps playing, the box shows that channel, and the grid cursor sits on its row.
- **AC-6** After CH+ or CH− in full screen, Back puts the channel now playing into the box and moves the grid cursor to its row.
- **AC-7** After that Back, OK on a different channel replaces the box video with that channel, at preview volume (muted while "Preview audio" is off, its default).
- **AC-8** Docking to the mini-player and expanding back move the same window: the video slides between full screen and the corner box, whose corners stay at 14 dp.
- **AC-9** With Settings > Animations Off, every move is instant, and the stream still never stops.
- **AC-10** Catch-up, rewind, a channel playing on mpv, Audio Mode and Multiview behave as `kevbox-live` does today: no animation, and leaving full screen stops the stream.
- **AC-11** First-run setup writes Animations On (`AnimationLevel.FULL`) along with dark mode, the accent colour and live preview off.
- **AC-12** Every hunk in an upstream file has a `KevBox:` comment within 3 lines. Every changed upstream file has a row in UPSTREAM-SYNC.md's "Changed upstream files" table. `check-divergence.sh` prints 0 failures.
- **AC-13** Unit tests cover which site the window goes to (player beats box), the 600 ms grace period, and the rectangle and corner interpolation.

## Non-goals
- Subtitles in the grid's box. The box is a preview; subtitles show in full screen and the mini-player, as today.
- The Live list view (`LiveStage.kt`), the TV Guide section and the Home hero. KevBox Live shows none of them, so they keep their own video windows.
- The 720p limit on 2 GB TVs. It still lifts in full screen and returns in the box, as today.
- Fixing focus while the video grows. For those 320 ms the grid keeps focus, and a key pressed then goes to the grid.

## Decisions
- One bottom-layer video window plus see-through patches, over two windows that swap. Kevin chose this on 2026-10-13. A swap can stall the picture on some TV chips; the core notes call out Realtek on 4K, which his TCL uses.
- The patch is a `BlendMode.Clear` draw. It was proved on the `kevbox_tv` emulator on 2026-10-13 with a throwaway build: the video showed through the box, the LIVE badge drew on top, and the sidebar covered it.
- Whoever the window has settled on cuts its own patch: the box in `GuideVideo`, or the player box in the shell. While the window moves, or waits out the grace period, a stage-level patch at the window's current rectangle does it. A site that cut its own patch during a move would show the grid's empty background around the growing video.
- The stage is UI state owned by the shell, reached through a `CompositionLocal`, not a Koin single. It lives exactly as long as the shell.
- Back keeps the sound on (Kevin, 2026-10-13). The next preview mutes it, the way any preview starts.
- 320 ms with `FastOutSlowInEasing`, scaled by `AnimationLevel.scale`, so Animations Off makes it 0.
- The phase table's `animaster-pack` is web-only, so the UI tasks carry `ui-ux-pro-max` alone.

## Tasks

### Task 1: One video window, settled in the grid's box
**Delivers:** AC-1, AC-2, AC-13 (and the base of AC-3)
**Files:** `app/src/main/java/tv/own/owntv/kevbox/live/LiveVideoStage.kt` (new), `app/src/main/java/tv/own/owntv/features/epg/GuideStage.kt`, `app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt`, `app/src/main/java/tv/own/owntv/player/MpvVideoSurface.kt`, `app/src/test/java/tv/own/owntv/kevbox/live/LiveVideoStageTest.kt` (new), `UPSTREAM-SYNC.md`
**Design skills:** `~/.agents/skills/ui-ux-pro-max/SKILL.md`
**Interfaces:**
- `enum class VideoSite { BOX, PLAYER }`
- `class LiveVideoStage(durationMs: Int = 320)`, all Compose snapshot state, so plain JUnit can drive it with no composition. Time only moves through `frame`:
  - `var durationMs: Int`: the shell sets it from `AnimationLevel.scale(320)` each composition.
  - `fun report(site: VideoSite, bounds: androidx.compose.ui.geometry.Rect, cornerPx: Float, nowMs: Long)`, with bounds in window pixels.
  - `fun clear(site: VideoSite, nowMs: Long)`
  - `fun frame(nowMs: Long)`: advances the move and the grace period.
  - `val target: VideoSite?`: PLAYER when reported, else BOX, else null.
  - `val current: Rect?` and `val currentCornerPx: Float`, the animated values.
  - `fun settledOn(site: VideoSite): Boolean`: true when the window has reached that site's current rectangle and is not moving.
  - `val visible: Boolean`: true while a target exists, or within 600 ms of the last one clearing.
  - `var boxChannel: ChannelEntity?`, for Task 3.
- A change of target site, or of the target's rectangle, starts a move from `current`. With `durationMs == 0` the move ends on the next `frame`. The first target ever reported starts settled, with no move.
- `val LocalLiveVideoStage = staticCompositionLocalOf<LiveVideoStage?> { null }`
- `@Composable fun LiveVideoLayer(stage: LiveVideoStage, engine: tv.own.owntv.player.LivePreviewEngine, keepAwake: Boolean, autoFrameRate: Boolean)`: the bottom window.
- `@Composable fun LiveVideoPatch(stage: LiveVideoStage)`: the stage-level see-through patch.
- `fun Modifier.liveVideoSite(stage: LiveVideoStage, site: VideoSite, corner: Dp): Modifier`: reports the bounds while the site is composed, clears them when it leaves, and cuts the patch while `settledOn(site)`.
- Pure helpers for the tests:
  - `internal fun lerpRect(from: Rect, to: Rect, t: Float): Rect`
  - `internal fun lerpCorner(from: Float, to: Float, t: Float): Float`
  - `internal fun pickTarget(box: Boolean, player: Boolean): VideoSite?`
  - the grace-period rule as an `internal` function of the last-clear time and now.
- `ExoPreviewSurface` gains `subtitles: Boolean = true`. When false it skips its `StyledSubtitleView`.
- New in `MpvVideoSurface.kt`: `@Composable fun ExoSubtitleLayer(engine: LivePreviewEngine, modifier: Modifier = Modifier)`. It draws the engine's subtitles sized like the video, with the same `videoZoom` maths `ExoPreviewSurface` uses.

**Does:**
- `LiveVideoLayer` draws one `ExoPreviewSurface(subtitles = false)` at `stage.current`, offset and sized in pixels, while `stage.visible`. Nothing is drawn otherwise. It runs a `withFrameMillis` loop that calls `stage.frame(now)` while the stage is visible.
- The window moves to a new target over `durationMs` with `FastOutSlowInEasing`. The shell sets `durationMs` from the current `AnimationLevel`.
- The shell provides a `LiveVideoStage`, made with `remember`, through `LocalLiveVideoStage` in its existing `CompositionLocalProvider`.
- The shell composes `LiveVideoLayer` as the first child of its root `Box`, below the browse UI. It composes `LiveVideoPatch` right after the browse `Column` block.
- `LiveVideoPatch` cuts a `BlendMode.Clear` rounded rectangle at `stage.current`, with `stage.currentCornerPx`, whenever the window is visible and not settled on its target.
- In `GuideVideo`, when `playing` is true, the box puts `Modifier.liveVideoSite(stage, BOX, 28.mpx)` where it called `ExoPreviewSurface`, with the stage from `LocalLiveVideoStage`. It no longer creates a surface of its own. The shell always provides a stage, so there is no fallback. Its overlays still draw above.

**Tests:**
- `pickTarget`: player beats box, box alone gives BOX, neither gives null.
- Grace period, driven through `report`, `clear` and `frame`: `visible` holds 599 ms after the last clear and drops at 600 ms.
- `lerpRect` and `lerpCorner` return `from` at 0, `to` at 1 and the midpoint at 0.5.

**Verify:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew testStandardDebugUnitTest --tests 'tv.own.owntv.kevbox.live.*'`

### Task 2: Full screen and the mini-player on the same window
**Delivers:** AC-3, AC-4, AC-8, AC-9, AC-10
**Files:** `app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt`, `app/src/main/java/tv/own/owntv/kevbox/live/LiveVideoStage.kt`, `UPSTREAM-SYNC.md`
**Design skills:** `~/.agents/skills/ui-ux-pro-max/SKILL.md`
**Does:**
- When `liveOnExo` is true, the shell's player `Box` (the one at `if (multiview == null && (playerMode == FULLSCREEN || playerMode == MINI))`) changes three ways:
  - It drops its own `ExoPreviewSurface` and its black background.
  - It adds `liveVideoSite(stage, PLAYER, corner)`, with corner 0 dp in full screen and 14 dp in the mini-player.
  - It draws `ExoSubtitleLayer(liveVm.previewEngine)` where the surface was. The mpv path is untouched.
- `LiveVideoLayer` gets `keepAwake = true` while the player shows live on ExoPlayer, and `autoFrameRate = playerMode == PlayerMode.FULLSCREEN && autoFrameRate`. The shell computes these at the root `Box`, because the player box's local `isFull` is out of scope there. These are the values the old call passed.
- The browse UI stays composed in full screen until the window has settled on PLAYER. The condition becomes `playerMode != FULLSCREEN || (liveOnExo && !stage.settledOn(PLAYER))`.
- `PlayerHud` and the full-screen-only overlays (the auto-frame-rate prompt) compose only once settled on PLAYER, or at once when `liveOnExo` is false.
- Docking to the mini-player and expanding move the window between the two PLAYER rectangles. The stage animates any change of target rectangle, not only a change of site.
- Audio Mode, Multiview and mpv playback report no site, so the window goes away after the grace period, as today.
- While no target exists, `LiveVideoPatch` draws only if the bridge is armed (`fun armBridge()`) and the last site to clear was PLAYER. That bridges Back from full screen until the box reports. The arm is spent when the next target appears or the grace period ends. Every other exit hides the video at once: mini Close, Audio Mode, Back from a channel opened outside the grid, and a BOX leaving with no new target. The window itself still waits out the grace period. Task 2 adds the hook but never calls it; Task 3 does.

**Tests** (plain JUnit, through `report` and `frame`):
- A target-rectangle change on the same site starts a move: `settledOn` is false at `now + durationMs - 1` and true at `now + durationMs`.
- With `durationMs = 0`, `settledOn` is true after one `frame`.
- The patch rule: with no target, the patch shows after an armed PLAYER clear, and stays hidden after an unarmed PLAYER clear or any BOX clear. The arm is gone after the next report.

**Verify:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew testStandardDebugUnitTest --tests 'tv.own.owntv.kevbox.live.*'`

### Task 3: Back keeps the stream, the box and the cursor; Animations On at first run
**Delivers:** AC-5, AC-6, AC-7, AC-11, AC-12
**Files:** `app/src/main/java/tv/own/owntv/features/epg/EpgScreen.kt`, `app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt`, `app/src/main/java/tv/own/owntv/features/live/LiveViewModel.kt`, `app/src/main/java/tv/own/owntv/kevbox/ui/AutoSetup.kt`, `UPSTREAM-SYNC.md`
**Does:**
- `EpgScreen` drops its `remember`ed `boxChannel` and reads and writes `LocalLiveVideoStage.current?.boxChannel`. With no stage, it falls back to a local `remember`.
- Its `LaunchedEffect(previewEnabled) { if (!previewEnabled) boxChannel = null }` goes, so full screen no longer empties the box.
- `exitPlayer` in the shell reads, before it changes anything, whether this is a Back-to-grid exit. All of these must hold:
  - `playerMode == FULLSCREEN`
  - `selectedSection == LIVE_TV` and `zapSource == LIVE_TV`
  - `liveOnExo` is true
  - `catchupActive` is false

  On such an exit the shell does four things:
  - It calls `stage.armBridge()` (added in Task 2) before the player box leaves, so the patch carries the video until the box reports.
  - It sets `stage.boxChannel = liveVm.previewChannel.value`.
  - It calls `liveVm.onFullscreenExited(keepStream = true)`.
  - If that channel differs from the box channel when full screen opened, it calls `liveVm.revealChannel(channel)`. Every other exit clears `stage.boxChannel` and keeps today's call.
- `LiveViewModel.onFullscreenExited(keepStream: Boolean = false)` skips `live.stopExo()` when `keepStream` is true, and does not re-mute.
- `KevBoxAutoSetup`, on first run, also calls `setAnimationLevel(AnimationLevel.FULL)`.
- UPSTREAM-SYNC.md: rows for every upstream file this branch changed, plus a short "Seamless Live video" note in the "Live TV only" section. The note covers the bottom-layer window, the patch rule and the grace period.

**Tests:**
- A pure helper `isBackToGrid(...)` (in `LiveVideoStage.kt`) returns true only when all four conditions hold.

**Verify:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew testStandardDebugUnitTest --tests 'tv.own.owntv.kevbox.live.*'`, then commit, then `bash tools/kevbox/check-divergence.sh` (it reads committed state only).

## Build log
baseline: gate green (tests, lint, check-divergence 0 failures)
Task 1: started at a0defb47
Task 1: landed (5cc39008, fix round 1 a042cf64: window clipped to the animated corner so Glass mode keeps round box corners). Bent: frame loop runs only while moving or in the grace period; expiry resets the window so the next site starts in place; bounds from localToWindow, not boundsInWindow; no inspectableProperties (i18n check).
Task 1 minor: lerpRect reuses lerpCorner for x/y (naming); runCatching around requireLayoutCoordinates in update() swallows everything.
Plan edit: Task 2 gains the patch rule (bridge only after PLAYER clears; a BOX leaving with no target hides at once), from the Task 1 implementer's risk note.
Task 1: emulator check ok (box video with round corners and overlays, sidebar and exit dialog cover it)
Task 2: started at b7c6b60d
Plan edit: the bridge is opt-in (armBridge), so mini Close, Audio Mode and Back from a channel opened outside the grid hide the video at once. Task 3 calls armBridge on a Back-to-grid exit.
Task 2: landed (a2d92da1). Added: armBridge, patchVisible, internal patchShows(target, settledOnTarget, lastCleared, bridgeArmed); a same-site report keeps the arm. ExoSubtitleLayer composes only once the window settles. PlayerHud, the AFR prompt and the timeshift-resume dialog wait for the window to settle (playerChromeReady).
Task 3: started at 80de1d23
Task 3: landed (236564ea). Bent: isBackToGrid takes zapSource as nullable; no separate "opened" field, stage.boxChannel set in guidePlayChannel records the channel full screen opened on. Added: the shell clears boxChannel when Live TV is not shown with no player up, and in stopForSleep. Risks noted: revealChannel may switch the grid to the channel's own folder after CH+/-; guide "Video preview" off would stop the kept stream (hidden setting); a rewound recording copy also returns to the box.
gate (orchestrator, after Task 3): green (tests, lint, check-divergence 0 failures)
emulator (orchestrator): grow and shrink work and the stream never restarts (clip counter runs on through both); box keeps playing after Back with the cursor on the row. Fix-list item, important (AC-4/AC-5 look): during a move the video lags its see-through patch, so a black strip trails the right/bottom edges through the 320 ms grow and the first ~50 ms of the shrink. Cause: LiveVideoLayer resizes the SurfaceView by layout, and SurfaceView applies size changes off the frame. Proposed fix: keep the window at one fixed size (the root box) and place it with graphicsLayer translation + uniform scale (transformOrigin 0,0), corner clip = currentCornerPx / scale; SurfaceView applies RenderNode transforms in step with the frame.
ocr: done (ecf6ab80): 3 medium, 2 low.
ocr medium OwnTVShell.kt exitPlayer: fix. backToGrid also fires for Multiview onExit and stopForSleep (dead channel in the box, bridge left armed); the low armBridge-without-player comment rides along.
ocr medium LiveVideoStage.kt LiveVideoLayer per-frame resize: fix, merged with the orchestrator's black-strip item (one important item).
ocr medium GuideStage.kt no fallback / TV Guide section uses the stage: skip; KevBox Live hides the TV Guide section and the shell always provides a stage.
ocr low EpgScreen.kt previewEnabled reset removed: dropped (exitPlayer clears the box on every non-Back-to-grid exit).
review: gate green. important 1: zap then Back then OK opens full screen the slow way (previewedChannelId stale; expectPromotion misses). important 2: Multiview onExit and stopForSleep pass isBackToGrid (same as ocr). important 3: a rewound local timeshift copy passes isBackToGrid. important 4: the window stays placed for the 600 ms grace, so with a see-through (picture) background the hidden video shows. important 5: liveOnExo turning true in full screen (engine flip) rebuilds the HUD and mounts the grid for a frame. minor 6: failed stream with no fallback leaves a dead channel in the box (skip, rare). minor 7: Back within 500 ms of CH+ leaves the deferred zap tune running (fix, rides on item 1). minor 8: two SurfaceViews and an AFR reset during the grace after an mpv hand-off (fix, solved with item 4). minor 9: Back during the 320 ms grow goes to the grid (skip, plan non-goal). minor 10: reveal can switch the grid's category (skip). minor 11: UPSTREAM-SYNC note wording (fix). minor 12: 1 px rounding of the window (fix, rides on the black-strip rework).
fix list (round 1): review 1 + minor 7; review 2 + ocr exitPlayer + ocr armBridge; review 3; review 4 + minor 8 (window leaves at once unless the bridge is armed); review 5; black strip + ocr per-frame resize + minor 12; minor 11.
