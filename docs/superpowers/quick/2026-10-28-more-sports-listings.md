# LiveOnSat channels for basketball, motor sport and MMA, plus the leftover-box sound fix

## Goal
Find channels on a basketball, Formula 1 or MMA match lists LiveOnSat's channels first, labelled
"Listed by LiveOnSat", the same way football does. A Sports box left behind by a launcher Live tile
no longer leaves sound playing behind the Sports hub.

## Scope
Fork-only `kevbox/sports/FootballListings.kt` and `kevbox/live/LiveVideoStage.kt`, one column in
`kevbox/supabase/SupabaseApi.kt`, and their tests. No upstream file changes.

**Gate:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew testStandardDebugUnitTest lintStandardDebug && bash tools/kevbox/check-divergence.sh`

## Acceptance criteria
- **AC-1** `sectionShown(section, playerIdle = false, boxSection = SPORTS)` returns `clearBox = true, stopPreview = false` for every section but SPORTS. For SPORTS it returns no clear and no stop. All other results stay as they are. A SearchBoxTest case (the sectionShown tests live there, SearchBoxTest.kt:123-165) covers LIVE_TV, HOME and SPORTS with the player not idle.
- **AC-2** `SupabaseApi.selectFootballListings` asks for `sport` too (`select=kickoff,competition,home,away,channels,sport`), and nothing else about the call changes.
- **AC-3** `FootballListing` carries `sport: String`. A row without `sport`, or with JSON null, reads as `"soccer"`. A row with a sport the app doesn't know is kept and links to nothing.
- **AC-4** `listingFor` / `link` accept SOCCER, BASKETBALL, MMA and RACING matches, and a listing links only to a match of its own sport (`soccer`→SOCCER, `basketball`→BASKETBALL, `mma`→MMA, `racing`→RACING). Football links exactly as today: the existing FootballListingsTest cases pass, except the select-string check at FootballListingsTest.kt:108, which gains `sport`. The case at :201 (a basketball match against a listing with no sport) still returns null, because that listing reads as soccer.
- **AC-5** Basketball links by the football rule: kickoff within 15 minutes and both teams found, or one team within 5 minutes. A test links "Los Angeles Lakers v Portland Trail Blazers" to ESPN's "Los Angeles Lakers" / "Portland Trail Blazers". MMA links only when both fighter names are found (`match.home` / `match.away`, which fall back to the first two competitors, the main-event athletes), within 12 hours of the match start. The wider window exists because Core times an MMA card from its first prelim fight (EspnParser.kt:292), while LiveOnSat times the main card. A test links "Islam Makhachev v Arman Tsarukyan" to an MMA card with those two athletes starting 3 hours earlier. A card with only one of them doesn't link.
- **AC-6** Racing links when the listing's kickoff is within 15 minutes of the match's start and its competition contains the match's league name, ignoring case ("Formula 1 Grand Prix - Round 19" contains "Formula 1"). A test links the Mexican Grand Prix row to an F1 race session at the same time. A NASCAR session at that time does not link, and neither does an F1 practice session 2 hours earlier.
- **AC-7** ListedChannels, SportsViewModel and SportsPlayback are unchanged: they already merge whatever `listingFor` returns. A ListedChannelsTest case shows a basketball match getting its listed channel first.
- **AC-8** `check-divergence.sh` passes. Only fork-only files change, so no UPSTREAM-SYNC row is needed; every touched file keeps its fork-only header.

## Non-goals
- Other LiveOnSat sports: Kevin limited this to basketball, motor sport and MMA.
- EuroLeague, boxing and non-F1 motor sport linking: Core has no league for them. The server tags boxing rows `mma` (they share LiveOnSat's page), but no ESPN MMA card has boxers' names, so they link to nothing.
- Renaming FootballListing / FootballListings: churn across call sites for no behaviour.
- Shipping before the server's migration 0019 is live: a build that asks for `sport` without the column gets an error and shows no listed channels. The server run applies the migration first.

## Decisions
- Sport stored as the server's string, mapped when linking, over a Kotlin enum, so a sport added on the server later reads fine on old builds.
- Racing links by time plus league name over event names, because LiveOnSat says "Mexican Grand Prix" where ESPN may say "Mexico City Grand Prix".
- The sound fix in `sectionShown` over the page, because the page is already gone when the launcher tile fires, and the shell reads the real player state.

## Tasks

### Task 1: Leftover-box sound fix
**Delivers:** AC-1, AC-8 (its file)
**Files:** `app/src/main/java/tv/own/owntv/kevbox/live/LiveVideoStage.kt`, `app/src/test/java/tv/own/owntv/kevbox/live/SearchBoxTest.kt`
**Does:**
- `sectionShown`'s not-idle branch returns `clearBox = boxSection == SPORTS && section != SPORTS`, `stopPreview = false`;
- its KDoc says why: a launcher Live tile opens full screen in the same frame as the section change.
**Tests:** the AC-1 case.
**Verify:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew :app:testStandardDebugUnitTest --tests 'tv.own.owntv.kevbox.live.*'`

### Task 2: Listings for every sport
**Delivers:** AC-2 to AC-7, AC-8 (its files)
**Files:** `app/src/main/java/tv/own/owntv/kevbox/supabase/SupabaseApi.kt`, `app/src/main/java/tv/own/owntv/kevbox/sports/FootballListings.kt`, `app/src/test/java/tv/own/owntv/kevbox/sports/FootballListingsTest.kt`, `app/src/test/java/tv/own/owntv/kevbox/sports/ListedChannelsTest.kt`, `tools/i18n/safe_literals.txt` and `tools/i18n/hardcoded_baseline.txt` if new technical literals need `classify-safe`
**Interfaces:** `FootballListing(kickoffMs, competition, home, away, channels, sport: String = "soccer")`
**Does:** AC-2 to AC-6, with the SOCCER guards at FootballListings.kt:52 and :122 replaced by the sport map.
**Tests:** the AC-5 and AC-6 cases, one ListedChannelsTest case, and the updated select-string check.
**Verify:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew :app:testStandardDebugUnitTest --tests 'tv.own.owntv.kevbox.sports.*'`

## Build log
- plan check: 4 problems (the BASKETBALL case does not flip; the select-string test changes; Core times MMA cards from the first prelim, so MMA uses both names within 12 hours; boxing rows are tagged mma but link to nothing). Task 1's test file is SearchBoxTest. All fixed in the plan.
- baseline: gate green on kevbox-live 5af394b4 (tests, lint, divergence 0 failures).
- Task 1+2: started at 0f0705b2 (one implementer: one Gradle project)
- Task 1+2: landed (062fac91 sound fix, 1089663d listings). No interface bent, no file outside Files. link() keeps the match's own sport, then linkByTeams (soccer, basketball), linkByFighters (mma: both names, 12 h) or linkBySeries (racing: 15 min, competition contains leagueName, blank never links). safe_literals.txt classified the new literals. Implementer's gate: green, divergence 0 failures.
- ocr: 4 comments (1 medium: the not-idle clear also fires for the mini player; 3 low).
