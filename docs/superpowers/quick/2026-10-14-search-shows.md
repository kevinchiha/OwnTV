# Search shows

## Goal
In KevBox Live, Search has a Channels tab and a TV guide tab, the guide's search finds channels by name or by a show they carry (grouped Channels / On now / Next 24 hours, matching shows outlined), and holding Back while the preview box plays grows it to full screen as OK on that channel does.

## Scope
New files under `app/src/main/java/tv/own/owntv/kevbox/live/` (with tests), plus KevBox-commented edits to `features/search/SearchScreen.kt`, `features/search/SearchViewModel.kt`, `features/epg/EpgViewModel.kt`, `features/epg/EpgScreen.kt` (and the guide row file that draws programme blocks), `features/shell/OwnTVShell.kt`, `kevbox/di/KevBoxModule.kt`, `res/values/kevbox_strings.xml` and `UPSTREAM-SYNC.md`.

**Gate:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew testStandardDebugUnitTest lintStandardDebug && bash tools/kevbox/check-divergence.sh`

`verifyI18nLiterals` runs inside the build (it hangs off `preBuild`). A new string literal that fails it gets classified with `python tools/i18n/check_hardcoded_strings.py classify-safe --path <file> --text '<text>' --category <category>`, never suppressed. User-visible text goes in `res/values/kevbox_strings.xml` with a comment saying where it shows. `check-divergence.sh` reads committed state only, so commit before running it. Fork rules: new files go under `kevbox/` with the fork-only header the other `kevbox/` files carry; every hunk in an upstream file has a `KevBox:` comment within 3 lines; every changed upstream file has a row and a proof grep in `UPSTREAM-SYNC.md`.

## Acceptance criteria
- **AC-1** One show finder serves both searches: programmes whose title contains the query (case-insensitive), on a channel the profile may see (hidden channels and hidden categories left out, manual guide matches and per-channel guide shifts applied), split into "on now" (start <= now < stop) and "next 24 hours" (now < start < now + 24 h). No 20-result cap: it reads up to 2000 guide rows.
- **AC-2** Main Search with a typed query shows two tabs under the field, where the "LIVE TV · 61" heading sits today: "Channels · n" and "TV guide · n" (n = that tab's result count, the guide count being on now + next 24 hours).
- **AC-3** Down from the field lands on the open tab; Left/Right on the tabs switch the open tab, and the list below changes as focus lands on a tab; Down from a tab lands on the first row of the open list; Up from the first row lands on the open tab, Up from the tab on the field.
- **AC-4** Each new query opens on Channels, except when it finds no channels and at least one show: then TV guide opens.
- **AC-5** The Channels tab lists every matching channel, as today, with no group heading. The TV guide tab lists "ON NOW · n" then "NEXT 24 HOURS · n", each heading shown only when its group has rows; on now in channel order, next 24 hours by start time.
- **AC-6** OK, Hold OK, the right-hand box and Back from full screen behave on both tabs as today's rows do (a show row acts on its channel); Back from full screen lands on the row it left from, in the tab it was in.
- **AC-7** The guide's search (the "Search guide channels" field, inside the open category) keeps a channel when its name matches or when it carries a matching show in the window of AC-1. The grid shows three groups with headings: "CHANNELS · n" (name matches), "ON NOW · n", "NEXT 24 HOURS · n" (by the earliest matching start). A channel shows once, in the first group it qualifies for. A group with no rows has no heading. With the field empty the grid is exactly as today.
- **AC-8** Up/Down in the grid never stops on a heading; CH+/CH− paging, Hold OK jumps and the cursor's row number count channels only, as today.
- **AC-9** While the guide's search has text, every programme block in the grid whose title contains it gets an outline in the accent colour, and keeps it when focused.
- **AC-10** Holding Back for 600 ms opens full screen with the grow animation exactly as OK on the boxed channel does, when the box has a channel, the box belongs to the screen on show (Search or Live TV), no player is open and no shell dialog (exit, avatar, playlist picker) is up. This holds wherever focus sits on that screen: grid, category column, guide search field, main menu, Search results or tabs.
- **AC-11** A short Back does what it does today. Holding Back when AC-10 does not hold does nothing new, and the release of a hold that fired does not also run Back. After a hold, a short Back from full screen shrinks the video into the box, cursor on that channel's row.
- **AC-12** Unit tests cover the show split (AC-1), the tab pick (AC-4), the guide grouping (AC-7) and the hold-Back rule (AC-10).
- **AC-13** Every hunk in an upstream file has a `KevBox:` comment within 3 lines; every changed upstream file has a row in `UPSTREAM-SYNC.md`; `check-divergence.sh` prints 0 failures.

## Non-goals
- Changing core's SearchReader. It still runs its own 12-hour "On TV" query, whose result Search now ignores; the core library is prebuilt and stays unforked (UPSTREAM-SYNC.md "Why Core is not forked").
- Outlining matches anywhere but the guide grid, or scrolling the grid to the matched show's time.
- Searching show descriptions; titles only, as today.
- The TV Guide section (`MainSection.EPG`): KevBox Live never shows it.
- Upstream's mini player (PlayerMode.MINI): its own hold-Back (focus the now-playing rail) stays as is.

## Decisions
- Our own show finder over raising core's limits, because the 12 h / 20 cap is in private constants of the prebuilt core (Kevin, 2026-10-14).
- Window: on now plus the next 24 hours, two lists, after Kevin first picked "the whole downloaded guide" and then replaced it.
- Name matches first in the guide, their own group (Kevin's pick).
- Hold Back reuses the shell's existing 600 ms hold detector (`backHoldJob` in OwnTVShell), widened, over a second detector, so short Back keeps exactly one path.
- Hold Back calls the same `guidePlayChannel` OK uses, so animation, CH+/CH− and the way back are the same code.
- Guide headings are rendered so the grid's channel index stays the channel's position in `state.channels` (CategorySteps.kt already maps positions past extra lazy items); everything that counts rows keeps counting channels.

## Tasks

### Task 1: The show finder
**Delivers:** AC-1, AC-12 (split), AC-13 (for its files)
**Files:** `app/src/main/java/tv/own/owntv/kevbox/live/ShowSearch.kt` (new), `app/src/test/java/tv/own/owntv/kevbox/live/ShowSearchTest.kt` (new), `app/src/main/java/tv/own/owntv/kevbox/di/KevBoxModule.kt`
**Interfaces:**
- `data class ShowMatches(val onNow: List<ProgrammeSearchResult>, val later: List<ProgrammeSearchResult>)` with `val size: Int` and `val channelIds: Set<Long>`; `ShowMatches.EMPTY`.
- `class ShowSearch(epgDao: EpgDao, channelDao: ChannelDao, settings: SettingsRepository)` with `suspend fun find(query: String, liveSourceIds: List<Long>, cust: SectionCustomizations, hiddenCategoryIds: Set<Long>, nowMs: Long): ShowMatches`; registered as a Koin `single`.
- Pure `fun splitShows(found: List<ProgrammeSearchResult>, nowMs: Long): ShowMatches` (on now in the order given, later sorted by start, duplicates of channel + start + title dropped).
- `const val SHOW_WINDOW_MS = 24 * 60 * 60_000L`.
**Does:**
- Reads `epgDao.searchTitles(query, now - 2 h slack, now + 24 h + 2 h slack, 2000)` and maps rows to channels the way core's `SearchReader.programmes` does (byEpgKeys, manual-match tails through byRemoteIdsOrNames, `EpgDedupe.collapse`, `EpgShift.apply` with `EpgShift.minutesFor`, renamed channels), with a local copy of core's `internal epgKeyOf` (manual match first, then the channel's own id, trimmed and lower-cased).
- Leaves out hidden channels and channels in hidden categories; returns `ShowMatches.EMPTY` for a query under 2 characters or no sources.
**Tests:**
- `splitShows` puts a programme spanning now in onNow and one starting in 3 h in later; drops one starting 25 h out and one already over.
- `splitShows` orders later by start time and drops a duplicate.
**Verify:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew testStandardDebugUnitTest --tests 'tv.own.owntv.kevbox.live.*' compileStandardDebugKotlin`

### Task 2: Channels and TV guide tabs in Search
**Delivers:** AC-2, AC-3, AC-4, AC-5, AC-6, AC-12 (tab pick), AC-13 (for its files)
**Files:** `app/src/main/java/tv/own/owntv/features/search/SearchScreen.kt`, `app/src/main/java/tv/own/owntv/features/search/SearchViewModel.kt`, `app/src/main/java/tv/own/owntv/kevbox/live/SearchBox.kt`, `app/src/test/java/tv/own/owntv/kevbox/live/SearchBoxTest.kt` (new or existing), `app/src/main/res/values/kevbox_strings.xml`, `UPSTREAM-SYNC.md`
**Design skills:** `~/.agents/skills/ui-ux-pro-max/SKILL.md`
**Interfaces:** uses Task 1's `ShowSearch.find` and `ShowMatches`. Adds `enum class SearchShowTab { CHANNELS, SHOWS }` and pure `fun pickShowTab(chosen: SearchShowTab?, channelCount: Int, showCount: Int): SearchShowTab` in `SearchBox.kt`.
**Does:**
- `SearchViewModel` gains a `shows: StateFlow<ShowMatches>` driven by the same debounced query (its customizations and hidden categories read as SearchReader reads them), and a chosen-tab state cleared by `setQuery` when the text changes.
- `SearchScreen` brings back a two-tab row with the existing `StageTab` (the `if (false)` block is the model) at the place of the group heading, and moves the list down to make room; the Channels tab lists `results.channels` with no heading, the TV guide tab lists "ON NOW · n" / "NEXT 24 HOURS · n" headings and `Entry.Programme` rows from `shows`, ignoring `results.programmes`.
- Focus moves of AC-3, including the field's ▼ hook and the list's ▲ exit, which today go straight between field and list.
- The title's result count is channels + shows.
- `lastFocusKey` restore (and `searchReturnKey`) opens the tab holding the key before focusing the row.
**Tests:**
- `pickShowTab`: no choice + channels -> CHANNELS; no choice + 0 channels + shows -> SHOWS; a choice wins.
**Verify:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew testStandardDebugUnitTest --tests 'tv.own.owntv.kevbox.live.*' compileStandardDebugKotlin`

### Task 3: Names and shows in the guide's search
**Delivers:** AC-7, AC-8, AC-9, AC-12 (grouping), AC-13 (for its files)
**Files:** `app/src/main/java/tv/own/owntv/kevbox/live/GuideShowGroups.kt` (new), `app/src/test/java/tv/own/owntv/kevbox/live/GuideShowGroupsTest.kt` (new), `app/src/main/java/tv/own/owntv/features/epg/EpgViewModel.kt`, `app/src/main/java/tv/own/owntv/features/epg/EpgScreen.kt`, `app/src/main/java/tv/own/owntv/features/epg/GuideCore.kt` (`ProgrammeStripCanvas`, which draws the grid's programme blocks; not `GuideProgrammeBlock` in GuideStage.kt, which is the single panel above the grid), `app/src/main/res/values/kevbox_strings.xml`, `UPSTREAM-SYNC.md`
**Design skills:** `~/.agents/skills/ui-ux-pro-max/SKILL.md`
**Interfaces:** uses Task 1's `ShowSearch.find`. Adds pure `fun groupGuideChannels(nameMatches: List<ChannelEntity>, shows: ShowMatches, inCategory: (ChannelEntity) -> Boolean): GuideGroups` where `GuideGroups(val channels: List<ChannelEntity>, val headings: List<GuideHeading>)` and `GuideHeading(val kind: Kind /* NAMES, ON_NOW, LATER */, val firstIndex: Int, val count: Int)`. `EpgUiState` gains `headings: List<GuideHeading> = emptyList()`.
**Does:**
- In `runLoad`, with a non-empty query (the view model has no Live TV flag; KevBox Live never shows the TV Guide section, so no flag is added), the name matches (today's path) and `ShowSearch.find` results, both passed through the same hidden, rename and category filters, become `state.channels` in group order plus `state.headings`. An empty query leaves the load as today.
- The grid draws each heading above its group's first row so `index` in `itemsIndexed(state.channels)` stays the channel's position (or uses separate lazy items with a position map in the style of `CategorySteps.kt`); stepping, `focusedRow`, `chNavPaging`, category jumps and the visible-row prefetch count channels only.
- Programme blocks whose title contains the query (case-insensitive, trimmed) draw an accent outline while the query is non-empty.
**Tests:**
- Grouping: a channel that matches by name and by a show sits only in NAMES; one with a show on now and later sits only in ON_NOW; LATER is ordered by earliest start.
- Grouping: channels outside the category are dropped; empty groups give no heading; `firstIndex` values match positions in `channels`.
**Verify:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew testStandardDebugUnitTest --tests 'tv.own.owntv.kevbox.live.*' compileStandardDebugKotlin`

### Task 4: Hold Back opens the box full screen
**Delivers:** AC-10, AC-11, AC-12 (hold rule), AC-13 (for its files)
**Files:** `app/src/main/java/tv/own/owntv/kevbox/live/LiveShortcuts.kt` (or a new `kevbox/live/HoldBack.kt`), its test, `app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt`, `UPSTREAM-SYNC.md`
**Interfaces:** pure `fun holdBackOpensBox(playerIdle: Boolean, section: MainSection, boxSection: MainSection?, hasBoxChannel: Boolean, dialogOpen: Boolean): Boolean` (true only for section SEARCH or LIVE_TV equal to boxSection).
**Does:**
- `longPressBackArmed` also arms when `holdBackOpensBox(...)` holds; when the 600 ms hold fires in that case it calls `guidePlayChannel(liveVideoStage.boxChannel, emptyList())` instead of `toggleNowPlayingFocus()`. The MINI case keeps its action.
- `guidePlayChannel` is reachable from the key handler (hoisted if it is declared below it), with no behaviour change for its other callers.
- Checks that Live TV's grid and Search put the cursor on the boxed channel's row on the way back when full screen was opened by the hold, not by OK on a row; any fix needed there goes in the report as a file outside **Files**.
**Tests:**
- `holdBackOpensBox`: true for Live TV with its box filled and the player idle; false with the box from the other section, with a dialog up, with the player open, and on Settings.
**Verify:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew testStandardDebugUnitTest --tests 'tv.own.owntv.kevbox.live.*' compileStandardDebugKotlin`

## Build log
- baseline: gate green (tests, lint, check-divergence 0 failures)
- Task 1: started at 7dd93b3b
- Task 1: landed (ec2c012e)
- Task 2: started at 53f5fe79
- Task 2: landed (f1899cb6). Outside Files: tools/i18n/safe_literals.txt (lazy keys h-onnow, h-later classified technical). Strings kevbox_shows_on_now / kevbox_shows_next_24_hours exist for Task 3; card body text now says next 24 hours.
- Task 3: started at ea40d387
- Task 3: landed (0a468959). Headings drawn inside the first row's lazy item (indices stay channel positions). EpgViewModel builds its own ShowSearch (Koin viewModelOf caps at 22 params). One-letter query: names only.
- Task 4: started at 48d8deaf
- Task 4: landed (c2f95c81). Hold also calls epgVm.noteChannelTuned on Live TV; exitPlayerWith reveals the playing channel when the grid no longer lists it (hold from another category). Known edge: guide search filter hiding the boxed channel leaves a pending reveal, as CH+/CH- today.
- gate (orchestrator, after Task 4): red, lint NonObservableLocale at EpgScreen.kt:1262 (Locale.getDefault() in a composable). On the fix list as important.
- ocr: 4 medium, 7 low (low dropped). Medium OwnTVShell.kt:1046 (hold fires on the KeyDown-time arming; re-check section, boxSection, dialogs when it fires): fix. Medium ShowSearch.kt:86 (rows grouped by raw epgChannelId, looked up by trimmed lower-case key): fix. Medium SearchViewModel.kt:92 (no error catch, shows not recomputed when sources load): fix. Medium EpgScreen.kt:848 (outline uses live field text and one-letter queries; take the loaded query from state, 2+ characters): fix.
- review: critical EpgScreen.kt:1262 lint NonObservableLocale (same item as the gate red above).
- review: important ShowSearch.kt:111-113 (AC-7): per-guide-id dedupe runs before the guide's category filter, so a second channel on the same guide id (HD/SD copy, other playlist) never gets an On now / Next 24 hours row, and a copy inside the open category is lost when the first carrier is outside it.
- review: minor EpgScreen.kt:848 one-letter outline (same item as ocr medium EpgScreen.kt:848): fix.
- review: minor GuideCore.kt:195-201 light palette focused match has no own outline: skip, KevBox Live's palette is dark.
- review: minor SQLite LIKE case only for A-Z vs Kotlin ignoreCase: skip, core behaves the same.
- review: minor guide On now order follows ShowSearch (playlist position) not the grid's order: fix.
- review: minor SearchViewModel.kt:88-93 results and shows debounce apart: "Nothing found" flashes and Down before shows arrive locks Channels as the choice: fix (with ocr medium SearchViewModel.kt:92).
- review: minor SearchScreen.kt:644 "▲ Search" key hint now one step off: fix.
- review: minor SearchScreen.kt:203-208 results.copy per recomposition makes both entry lists compare up to 500 rows per key press: fix.
- review: minor SearchScreen.kt:305/307/502 changed lines 5-7 from a KevBox: comment: fix.
