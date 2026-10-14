# Guide sidebar

## Goal
On KevBox Live's Live TV page the category list is always on screen while the cursor is on the main menu or the categories, and Right from the categories slides the menu and the list away so the guide fills the screen; Left or Back slides them back.

## Scope
A new `app/src/main/java/tv/own/owntv/kevbox/live/GuideSidebar.kt` (with its test), plus KevBox-commented edits to `features/epg/EpgScreen.kt`, `features/live/LiveStage.kt`, `features/shell/OwnTVShell.kt`, `features/shell/components/StageRail.kt` and `UPSTREAM-SYNC.md`.

**Gate:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew testStandardDebugUnitTest lintStandardDebug && bash tools/kevbox/check-divergence.sh`

`verifyI18nLiterals` runs inside the build (it hangs off `preBuild`). A new string literal that fails it gets classified with `python tools/i18n/check_hardcoded_strings.py classify-safe --path <file> --text '<text>' --category <category>`, never suppressed. `check-divergence.sh` reads committed state only, so commit before running it. Fork rules: new files go under `kevbox/` with the fork-only header the other `kevbox/` files carry; every hunk in an upstream file has a `KevBox:` comment within 3 lines; every changed upstream file has a row and a proof grep in `UPSTREAM-SYNC.md`.

## Acceptance criteria
- **AC-1** On Live TV with the cursor on the main menu, the screen shows the menu, the category column right of it (the open category marked), and the guide moved right by the menu's room plus the column's width. The guide keeps its full-screen layout; its right part runs off the screen edge.
- **AC-2** Right from the menu puts the cursor on the open category in the column. Nothing on screen moves, and the guide never shows expanded on the way, not for one frame.
- **AC-3** Up and Down in the column: once the cursor rests on a category for 300 ms, the guide behind shows that category's channels. A held key loads none of the categories it passes, and a category loading never pulls the cursor back to an earlier row.
- **AC-4** Right or OK on a category: if the guide does not show that category yet, it is selected at once. When its channels are there, the menu and the column slide off to the left and the guide slides left to fill the screen, in 320 ms. The cursor lands on the first row for a newly selected category, or on the row it was on before for the same category.
- **AC-5** Right or OK on a category with no channels does nothing; the cursor stays on it.
- **AC-6** Left or Back on a channel row (outside its programmes) slides everything back to the AC-1 layout in 320 ms, cursor on the open category. Inside a row's programmes Left still moves through the programmes and Back still leaves the row first.
- **AC-7** Left on a category row, or Back anywhere in the column, moves the cursor to the main menu with the layout unchanged. Back with text in the column's search clears the search first, as today. Back on the menu shows the exit prompt, as today.
- **AC-8** With the guide expanded the menu is fully off screen, with no edge glow. Every other page keeps its menu exactly as today.
- **AC-9** A channel playing in the top box moves with the guide during the slide and fills the box in both layouts. Growing it to full screen and shrinking it back with Back lands in the expanded guide's box, cursor on the channel's row.
- **AC-10** A programme or channel menu, or any dialog, opened over the guide leaves the layout as it was.
- **AC-11** With Settings > Animations Off, every switch is instant.
- **AC-12** Once the guide is fully expanded the column is not drawn. The TV Guide section (not Live TV) keeps its glass category sheet and its behaviour unchanged.
- **AC-13** Unit tests cover the view rules (which focus change collapses or expands the guide) and the Right/OK decision (select first or go straight to the grid, or stay on an empty category).
- **AC-14** Every hunk in an upstream file has a `KevBox:` comment within 3 lines; every changed upstream file has a row in `UPSTREAM-SYNC.md`; `check-divergence.sh` prints 0 failures.

## Non-goals
- TiviMate's look. The column uses LiveCategories' existing flat column style (`sheet = false`) with its search field; the screenshots only set the behaviour.
- A setting for the column width or slide speed.
- Tuning the Floating menu style. KevBox Live installs Docked + Compact; Floating gets the same room as the Compact capsule and is not tested on the TV.
- The TV Guide section and the Live list view (`LiveScreen.kt`). KevBox Live shows neither in Live TV.

## Decisions
- Move the guide with a layer translation (`graphicsLayer { translationX }`) over resizing it, because resizing re-lays out every visible row on each frame of the slide on the TV.
- 320 ms over the 200 ms first offered, so the guide moves with the menu's own 320 ms slide (`ownTvTween(320)` in `StageRail`); `ownTvTween` already snaps when animations are off.
- Load a category after 300 ms of rest, not on every step, so a held key stays cheap.
- The guide expands only on an explicit move into the grid (Right/OK from the column, a row taking focus from the full-screen player or Search's "Go to channel") and collapses on the menu taking focus or Left/Back on a row. A row briefly taking focus while focus is being sent into the column (the entry retries) does not count, so AC-2 holds.
- Column width 420 mpx, starting at the menu's room; the old sheet was 450 including its glass padding.
- No edge glow while hidden on Live TV: the expanded guide should show only the guide, as in the reference screenshot.

## Tasks

### Task 1: The column and the slide in the Live TV guide
**Delivers:** AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7, AC-9, AC-10, AC-11, AC-12, AC-13, AC-14 (for its own files)
**Files:** `app/src/main/java/tv/own/owntv/kevbox/live/GuideSidebar.kt` (new), `app/src/test/java/tv/own/owntv/kevbox/live/GuideSidebarTest.kt` (new), `app/src/main/java/tv/own/owntv/features/epg/EpgScreen.kt`, `app/src/main/java/tv/own/owntv/features/live/LiveStage.kt`, `UPSTREAM-SYNC.md`
**Design skills:** `~/.agents/skills/ui-ux-pro-max/SKILL.md`, `~/.agents/skills/motion-design/SKILL.md`
**Interfaces:** `EpgScreen` gains three parameters, used by Task 2 and ignored outside `liveMode`:
- `menuHasFocus: Boolean = false`: true while the shell's main menu holds focus.
- `menuRoom: Dp = 0.dp`: the width the menu takes from the screen's left edge; the column starts there.
- `onExpandedChange: (Boolean) -> Unit = {}`: called with true once the guide starts expanding, false once it starts collapsing.
`LiveCategories` gains `focusFollowsSelection: Boolean = true`; false stops its `LaunchedEffect(selectedIndex)` from moving focus.
**Does:**
- `GuideSidebar.kt` holds the pure rules: a reducer from (expanded, event) to expanded for the events menu focused, grid entered on purpose, grid focused while entering the column, Left/Back on a row, dialog opened; and the Right/OK decision from (cursor category, shown category, shown channel count, load pending) to select-then-enter, enter, or stay. No Compose in this file.
- In `liveMode`, `EpgScreen` replaces the overlay sheet with a column that is always composed: `LiveCategories(sheet = false, focusFollowsSelection = false)`, 420 mpx wide, full height, starting at `menuRoom`, with the existing 92% `GlassTint` layer dropped.
- The column and the guide move together with one animated fraction (`ownTvTween(320)`): collapsed, the guide's translationX is `menuRoom + 420 mpx` and the column's is 0; expanded, the guide's is 0 and the column's is minus its width plus `menuRoom`. Once fully expanded the column is not drawn.
- The cursor resting 300 ms on a column row selects that category through `liveVm.select` and, when the category changed, resets `focusedRow` to 0 and scrolls the grid to its top, as today's OK path does (`EpgScreen.kt` near line 745). So Right after a rest-select lands on row 0, and Right on the category that was already open lands on the row it was on. Right/OK use the decision above and carry out the move through the existing `sidebarExit` effect, which no longer reopens anything on an empty category.
- Updates the `EpgScreen.kt` row's proof grep in `UPSTREAM-SYNC.md`, which today greps for the `if (liveMode) { sheetOpen = true; return@invoke true }` line this task replaces, so it matches the new code.
- Right from the menu (the entry hook) focuses the column without expanding. Left/Back on a row collapses and focuses the open category. Left on a column row and Back in the column call `onOpenMenu()`. `menuHasFocus` turning true collapses.
- The existing `sheetOpen` overlay, its Back handler and its close-on-focus-loss stay for the TV Guide section only.
- Checks that the top box's video window (`liveVideoSite` in `LiveVideoStage.kt`) follows the box while the guide's layer moves. If `onGloballyPositioned` does not fire for a layer translation, the box reports its bounds on each frame of the slide; any change to `LiveVideoStage.kt` goes in the report as a file outside **Files**.
**Tests:**
- The reducer: menu focus collapses, a purposeful grid entry expands, a grid focus during column entry and a dialog leave the state alone.
- The Right/OK decision: a category not yet shown selects first; the shown category with channels enters; an empty shown category stays.
**Verify:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew testStandardDebugUnitTest --tests 'tv.own.owntv.kevbox.live.*' compileStandardDebugKotlin`

### Task 2: The menu slides away with the categories
**Delivers:** AC-8, AC-14 (for its own files)
**Files:** `app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt`, `app/src/main/java/tv/own/owntv/features/shell/components/StageRail.kt`, `UPSTREAM-SYNC.md`
**Design skills:** `~/.agents/skills/ui-ux-pro-max/SKILL.md`
**Interfaces:** `StageRail` gains `edgeGlow: Boolean = true`; false hides `RailEdgeGlow` in the HIDDEN state.
**Does:**
- The shell keeps a `liveGuideExpanded` flag set from `EpgScreen`'s `onExpandedChange` on the Live TV branch, cleared when the section changes.
- On Live TV the content box gets start padding 0, and the Live TV `EpgScreen` gets `menuRoom = railDockedReserve(navSize)` for Docked and `railDockedReserve(NavSize.COMPACT)` for Floating, and `menuHasFocus = railHasFocus`.
- `railState` is HIDDEN while the section is Live TV, `liveGuideExpanded` is true and the menu does not hold focus; `edgeGlow` is false on Live TV. Every other section computes `railState` and its padding as today.
**Tests:** none; the shell has no unit seams and the rule is one condition. The TV pass after the build checks it.
**Verify:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew compileStandardDebugKotlin`

## Build log
- baseline: gate green (tests, lint, check-divergence 0 failures)
