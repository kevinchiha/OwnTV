# LiveOnSat channels in Sports

## Goal
When a member presses Find channels on a football match, the channels LiveOnSat lists for it (as
kevbox.dev matched them to the provider's channels) come first, labelled "Listed by LiveOnSat", in
the match page and in the player's other-channels panel.

## Scope
A fork-only `kevbox/sports/` package (listings read, cache, match link, resolve, merge), one new
call in `SupabaseApi`, small `KevBox:` hooks in `SportsViewModel`, `SportsPlayback` and
`SportsMatchScreen`'s `ChannelRow`, two strings (en + fr), the KevBox DI module, `UPSTREAM-SYNC.md`
rows, i18n literal classification.

**Gate:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew testStandardDebugUnitTest lintStandardDebug && bash tools/kevbox/check-divergence.sh`

## Acceptance criteria
- **AC-1** `SupabaseApi.selectFootballListings(accessToken, fromIso, toIso)` GETs `rest/v1/football_listing` with `select=kickoff,competition,home,away,channels`, one `and=(kickoff.gte.<from>,kickoff.lte.<to>)` parameter (send's query is a map, so two `kickoff` keys cannot coexist), `order=kickoff.asc`, the member's bearer token and the anon key.
- **AC-2** `FootballListings` parses the rows (`channels` items `stream_id`, `name`, `listed_as`) and keeps them in memory; a second ask within 10 minutes makes no call; a failed or non-2xx call returns the last good copy (or nothing) and is retried on the next ask after 1 minute. The window asked for is now − 6 h to now + 36 h. A signed-out TV or a different member clears the copy.
- **AC-3** `FootballListings.listingFor(match)` returns a listing only for `Sport.SOCCER` matches, whose kickoff is within 15 minutes of `match.startMs`, and whose `home v away` text contains both of the match's teams by Core's `TeamNames` keys, or one team when the kickoffs are within 5 minutes. "FC Cologne v Union Berlin" links to ESPN's "1. FC Köln" v "1. FC Union Berlin"; a listing at the same kickoff with other teams does not link; the same teams 3 hours apart do not link.
- **AC-4** `ListedChannels.options(match)` turns the linked listing's channels into the member's own channels: `ChannelDao.byRemoteIdsOrNames` over the active profile's live sources, by `stream_id` first and exact `name` second; channels the profile hid (or in hidden categories) are left out; order follows the listing; one row per channel.
- **AC-5** Each such channel is a `WatchOption(channel, WatchTier.GUIDE, guideTitle = ListedChannels.MARK)`; `ListedChannels.merge(listed, core)` puts them first and drops Core's option for any channel already listed.
- **AC-6** Find channels on the match page shows the merged list: the `SportsViewModel` search uses it both when Core's `found()` has a cached answer and after `watchFor()`. With no listing (no network, not football, no link) the list is exactly Core's.
- **AC-7** The player's other-channels panel (`SportsPlayback.channelsFor`) gets the same merged list, through the same `WatchList` filter as today.
- **AC-8** `ChannelRow` shows the reason "Listed by LiveOnSat" (the guide icon, the guide tier's colour) for an option whose `guideTitle` is `ListedChannels.MARK`; the mark itself never shows anywhere, including the match page's preview caption (`SportsMatchScreen` passes `video?.guideTitle` as `nowTitle`, printed by GuideStage as " · <title>"); every other row reads as before. French: "Listé par LiveOnSat".
- **AC-9** `check-divergence.sh` passes: every touched upstream hunk carries a `KevBox:` comment and each touched upstream file has a row in UPSTREAM-SYNC.md's changed-files table with a grep proof; new fork-only files carry the fork-only header.
- **AC-10** `verifyI18nLiterals` passes: each task classifies its own new technical literals with `python3 tools/i18n/check_hardcoded_strings.py classify-safe --path <file> --text '<text>' --category <category>` (it rewrites `tools/i18n/safe_literals.txt` and `tools/i18n/hardcoded_baseline.txt`), because `preBuild` depends on `verifyI18nLiterals`.

## Non-goals
- Showing listings without Find channels, or outside its window: the upstream flow stays as it is.
- A setting to turn it off: with no listing the page is unchanged, so there is nothing to opt out of.
- Translating the label beyond English and French: KevBox's own strings exist in those two only.
- Matches ESPN does not have: the Sports page never shows them.

## Decisions
- Mark listed options by a sentinel `guideTitle` over a new field, because `WatchOption` is a Core data class and a field would mean forking Core; no real guide title equals the mark.
- Tier GUIDE over a new tier, for the same reason; it also makes the "In the guide" broadcasts section and the "only confirmed" filter count them, which is right: they are confirmed.
- Merge at the three call sites over wrapping `WatchReader`, because `WatchReader` is a final Core class.
- Link by kickoff + `TeamNames` over LiveOnSat's competition name, because LiveOnSat's names ("German Cup (DFB-Pokal)") do not follow ESPN's.

## Tasks

### Task 1: Read and link listings
**Delivers:** AC-1, AC-2, AC-3, AC-10 (its literals)
**Files:** `app/src/main/java/tv/own/owntv/kevbox/supabase/SupabaseApi.kt`, `app/src/main/java/tv/own/owntv/kevbox/sports/FootballListings.kt`, `app/src/main/java/tv/own/owntv/kevbox/di/KevBoxModule.kt`, `app/src/test/java/tv/own/owntv/kevbox/sports/FootballListingsTest.kt`, `tools/i18n/safe_literals.txt`, `tools/i18n/hardcoded_baseline.txt`
**Interfaces:** `data class ListedChannel(val streamId: String, val name: String, val listedAs: String)`; `data class FootballListing(val kickoffMs: Long, val competition: String, val home: String, val away: String, val channels: List<ListedChannel>)`; `class FootballListings(auth: KevBoxAuth, api: SupabaseApi, now: () -> Long)` with `suspend fun listingFor(match: Match): FootballListing?`; pure `internal fun link(match: Match, listings: List<FootballListing>): FootballListing?` for tests.
**Does:**
- The `SupabaseApi` call as AC-1 (its comment about constant query values updated to name the `and` filter's two timestamps, ISO-8601 UTC with `Z`, no `+`).
- Calls through `auth.signedInCall`, caches per AC-2, keyed by `auth.userId`.
- Links per AC-3 with `TeamNames.tokens` / `TeamNames.keyOf(team).foundIn(...)`; best link is the closest kickoff.
**Tests:** parse a two-row body into listings; cache (no second call within 10 min, last good copy kept on failure); the three AC-3 link cases.
**Verify:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew :app:testStandardDebugUnitTest --tests 'tv.own.owntv.kevbox.sports.*'`

### Task 2: Resolve, merge and hook in
**Delivers:** AC-4, AC-5, AC-6, AC-7, AC-9 (for the files it touches), AC-10 (its literals)
**Files:** `app/src/main/java/tv/own/owntv/kevbox/sports/ListedChannels.kt`, `app/src/main/java/tv/own/owntv/kevbox/di/KevBoxModule.kt`, `app/src/main/java/tv/own/owntv/features/sports/SportsViewModel.kt`, `app/src/main/java/tv/own/owntv/features/sports/SportsPlayback.kt`, `UPSTREAM-SYNC.md`, `app/src/test/java/tv/own/owntv/kevbox/sports/ListedChannelsTest.kt`, `tools/i18n/safe_literals.txt`, `tools/i18n/hardcoded_baseline.txt`
**Interfaces:** `class ListedChannels(listings: FootballListings, channels: ListedChannelReads)` with `suspend fun options(match: Match): List<WatchOption>`, `companion object { const val MARK: String; fun merge(listed: List<WatchOption>, core: List<WatchOption>): List<WatchOption>; fun isListed(o: WatchOption): Boolean }`; `interface ListedChannelReads { suspend fun visibleByRemoteIdsOrNames(keys: List<String>): List<ChannelEntity> }` with a Room-backed implementation (active profile's live sources, hidden items and hidden categories as Core's `RoomWatchData.scope` does).
**Does:**
- `options` per AC-4/AC-5; any failure returns an empty list.
- `SportsViewModel` and `SportsPlayback` take `ListedChannels` and merge at the found / watchFor / channelsFor points, each hunk with a `KevBox:` comment; both files get UPSTREAM-SYNC rows.
**Tests:** stream id beats name and order follows the listing; hidden channel left out; `merge` puts listed first and drops Core's duplicate.
**Verify:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew :app:testStandardDebugUnitTest --tests 'tv.own.owntv.kevbox.sports.*'`

### Task 3: The label
**Delivers:** AC-8, AC-9 (for its file), AC-10 (its literals)
**Files:** `app/src/main/java/tv/own/owntv/features/sports/SportsMatchScreen.kt`, `app/src/main/res/values/kevbox_strings.xml`, `app/src/main/res/values-fr/kevbox_strings.xml`, `UPSTREAM-SYNC.md`, `tools/i18n/safe_literals.txt`, `tools/i18n/hardcoded_baseline.txt`
**Design skills:** refactoring-ui
**Interfaces:** string `kevbox_sports_listed_by_liveonsat`.
**Does:**
- `ChannelRow`'s reason branch checks `ListedChannels.isListed(o)` before the GUIDE branch (`KevBox:` comment), same icon and colour as GUIDE.
- The preview caption (`nowTitle = video?.guideTitle`, around SportsMatchScreen.kt:227) passes null for a listed option (`KevBox:` comment).
- Strings en + fr; UPSTREAM-SYNC row for `SportsMatchScreen.kt`; then run the full gate.
**Tests:** none new (UI text); the gate's lint and divergence check cover it.
**Verify:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew testStandardDebugUnitTest lintStandardDebug && bash tools/kevbox/check-divergence.sh`

## Build log
- plan check: 3 problems (two `kickoff` keys in a map, literals classified too late for preBuild, MARK leaking into the preview caption); all fixed in the plan.
- baseline: gate green on kevbox-live (testStandardDebugUnitTest lintStandardDebug, check-divergence).
- Task 1: started at 335d7752
- Task 1: landed (79a3f170). link() is top-level internal in FootballListings.kt; extra internal listings() and parseListings(); hardcoded_baseline.txt unchanged.
- Task 2: started at c53f54db
- Task 2: landed (557e77f7). MARK = "\u0001kevbox-liveonsat"; Room reads in RoomListedChannelReads; a cached Core answer now waits for one listings read (up to SupabaseApi's 15 s timeout on a slow network); the hub's channelsFound (Remind me) is not merged.
- Task 3: started at 53ac085e
- Task 3: landed (003b407c). Fully qualified names, so no import hunk; no other guideTitle printers; the player panel reuses ChannelRow.
