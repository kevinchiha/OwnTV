# Sports hub: a Starting soon row per sport, no on-your-channels section

## Goal
The Sports hub shows one "Starting soon" row for each sport with upcoming matches (Football,
Basketball, Tennis, ...), instead of one row mixing every sport, and no longer shows the "Sport on
your channels now" section.

## Scope
`features/sports/SportsHubScreen.kt` (upstream file, `KevBox:` hunks), a fork-only helper with
tests, a KevBox string (en + fr), and a new UPSTREAM-SYNC row.

**Gate:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew testStandardDebugUnitTest lintStandardDebug && bash tools/kevbox/check-divergence.sh` (commit first: check-divergence reads committed work only)

## Acceptance criteria
- **AC-1** The hub's single "Starting soon" row (SportsHubScreen.kt ~:286-311) becomes one row per sport. Each sport that has at least one upcoming match passing the chips gets a row. A sport with none gets no row.
- **AC-2** The sport rows follow the order of the hub's sport chips (`vm.followedSports(config)`). A sport missing from that list goes after the listed ones, in Core's Sport enum order.
- **AC-3** Inside a row, matches keep today's sort: league rank (`config.rankOf`), then start time. Each row shows at most 12 matches of its sport.
- **AC-4** A row's title reads "Starting soon · ⚽ Football", using the chip's emoji and label (`sportEmoji`, `sportLabel`). The title comes from a new KevBox string with a placeholder (`kevbox_sports_starting_soon_sport`, en "Starting soon · %1$s", fr "Bientôt · %1$s"), not from joined text.
- **AC-5** The chips still filter: choosing Football leaves only the Football row. My teams leaves rows only for sports that have one of the user's teams playing soon. League chips filter inside their sport's row.
- **AC-6** Each row keeps its own scroll position and returns to its first card when the chips or leagues change, as the single row does today. Back from a match puts focus on the card it was opened from (`restoreKey` / `focusOf(m.key)`), whatever row it sits in. Back rebuilds the hub at the top with every row on its first card, and a lazy list only draws what is visible. So the restore finds the card's sport row and position, scrolls the hub's LazyColumn to that row's item and the row's LazyListState to the card, then requests focus, retrying as today. The hub column gets a `LazyListState`; the per-sport row states live in one map keyed by sport.
- **AC-7** D-pad: Up and Down move between rows, and each row's `focusRestorer` brings focus back to the card last focused in that row.
- **AC-8** The grouping is a pure fork-only function in `kevbox/sports/` with unit tests: `soonBySport(soon: List<Match>, order: List<Sport>, perSport: Int = 12): List<Pair<Sport, List<Match>>>`.
- **AC-10** The hub no longer shows "Sport on your channels now" (SportsHubScreen.kt ~:266-284). It is hidden behind one fork switch (`const val SHOW_ON_CHANNELS_NOW = false` in `kevbox/sports/SoonBySport.kt`), not deleted, so upstream merges stay small. The empty-hub note (~:230 `nothing`) no longer counts those rows, so a hub with only them shows the "nothing" note. The view model may keep computing them; Kevin asked for the section to go, nothing more.
- **AC-9** `check-divergence.sh` passes:
  - every hunk in SportsHubScreen.kt carries `// KevBox: ...`;
  - SportsHubScreen.kt gets a row in UPSTREAM-SYNC.md's changed-files table, with grep proofs;
  - the new file has the fork-only header;
  - `verifyI18nLiterals` passes.

## Non-goals
- Changing *Live now*: Kevin asked about Starting soon and the on-your-channels section only.
- Stopping the guide scan behind *Sport on your channels now*: hiding the section is what was asked; the scan feeds nothing else on screen.
- A per-sport "see all" page: the row scrolls, as today.

## Decisions
- Order by the chips over alphabetical, so the rows read in the same order as the buttons above them.
- 12 per sport over 12 in total, so one busy sport no longer crowds out the others.
- A KevBox string with a placeholder over joining two strings, so French word order stays right.

## Tasks

### Task 1: Rows per sport
**Delivers:** AC-1 to AC-10
**Files:** `app/src/main/java/tv/own/owntv/features/sports/SportsHubScreen.kt`, `app/src/main/java/tv/own/owntv/kevbox/sports/SoonBySport.kt` (new), `app/src/test/java/tv/own/owntv/kevbox/sports/SoonBySportTest.kt` (new), `app/src/main/res/values/kevbox_strings.xml`, `app/src/main/res/values-fr/kevbox_strings.xml`, `UPSTREAM-SYNC.md`, `tools/i18n/safe_literals.txt` / `tools/i18n/hardcoded_baseline.txt` if `classify-safe` needs them
**Design skills:** refactoring-ui
**Interfaces:** `soonBySport` as in AC-8.
**Does:**
- `soon` (~:165) drops its overall `take(12)`. The hub groups it with `soonBySport(soon, followedSports)`.
- One `item(key = "soon-<sport>")` per group, each with its own `rememberLazyListState` reset on chip and league changes.
- Same `RowHeader` and `MatchCard`s as today, with the AC-4 title.
- The onNow item hidden behind the fork switch (AC-10).
**Tests:** order follows the given sport list with unknown sports after it; at most `perSport` per sport, with the input order kept inside a sport; empty sports are dropped. (If the restore's "which row, which position" lookup is pure, test it too.)
**Verify:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew :app:testStandardDebugUnitTest --tests 'tv.own.owntv.kevbox.sports.*'`, then commit and run the full Gate.

## Build log
- plan check: 3 problems (gate needs a commit first; AC-6 restore needs scrolling to the card's row and position; the AC-10 switch had no file). All fixed in the plan.
- baseline: gate green on kevbox-live 71bc2621 (the merge just gated).
- Task 1: started at 0e339a76
- Task 1: landed (81c00233). No interface bent. SoonRows holds one LazyListState per sport and reveals a card (column to its row, row to the card); it relies on the soon rows staying the column's last items. The hub's restore now uses requestFocus(FocusDirection.Enter), which returns a Boolean (runCatching never failed, so the old loop stopped after one try); this also covers Live now cards. Implementer's gate green.
- gate on 81c00233: green (my run: tests, lint, divergence 0 failures); debug APK on kevbox_a14.
- device check: rows read "Starting soon · ⚽ Football", "· 🏀 Basketball", "· 🎾 Tennis" in chip order; Sport on your channels now gone; Back from the 8th tennis card (Mensik v Mannarino) lands on that card with the row and column scrolled to it. Found: D-pad Down into the bottom row (Tennis) leaves its cards cut off at the screen bottom, under the key hints; the column scrolls only until the card's top is on screen (/tmp/los/r2.png).
- ocr: 0 findings.
- review: all ACs met; no critical or important. minor: restore loop can take focus back from a key pressed during its 10 frames (:123-131); reveal runs after the last try (:129); "soon rows are last" is only a comment (SoonBySport.kt:39,:74); groups written during composition on a @Stable class (:175); onFilter untested; two tests re-derive their expected values.
- triage: the cut-off bottom row (device check) → fix as important. Review minors 1-6 → fix, all small.
- fix round 1: 3d32aae4. Gate green (tests, lint, divergence 0 failures).
  1. Cut-off bottom row: each soon row asks the column, when focus enters it, to show the whole row plus 110.mpx under it (WholeRow / wholeAboveHints, a BringIntoViewRequester), so the column scrolls until the cards sit above the key hints. Only soon rows get it; the other rows and the rows' sideways scrolling keep the default.
  2. A key pressed on the hub (KeyDown, onPreviewKeyEvent on the Box) sets keyDuringRestore; the restore loop stops at its next frame.
  3. The loop reveals only when attempt < 9.
  4. reveal checks the counted index against the drawn item keys (soonRowScroll): row drawn, leave the column; counted index drawn under another key, stop; else scroll. The row key comes from soonRowKey(sport), shared with the hub.
  5. soonRows.groups is written in a SideEffect; @Stable dropped, with a comment on why nothing needs it.
  6. Tests: onFilter on a plain LazyListState (first call, same value, changed value); the order test spells out its sports; a counting test with a "none" item before the rows, plus the stop case and the whole-row rectangle.
