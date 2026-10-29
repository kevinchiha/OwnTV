# Silent update: match KevBox TV (Live side)

## Goal
KevBox Live takes its early-user list from each release (`SILENT_EARLY_USERS` in release.env, Supabase
user ids), can publish a release with silent install switched off, and brings Core's update card back
on TV's rules, so Live and KevBox TV behave the same (Kevin's ruling 2026-10-29: "best of each, change
both"). Live keeps its server clock, 3-hour idle rule and 6 h / 15 min timing, which TV adopts.

## Scope
`release.sh`, `tools/kevbox/release-checks.sh` and its test script; `kevbox/release/SilentUpdateRules.kt`,
`SilentUpdater.kt`, `SilentUpdatePrefs.kt`, `kevbox/di/KevBoxModule.kt` (signed-in id instead of email);
`UPSTREAM-SYNC.md` notes. No Core file changes beyond what already exists.

**Gate:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew testStandardDebugUnitTest lintStandardDebug && bash tools/kevbox/test-release-checks.sh && bash tools/kevbox/check-divergence.sh`
(commit first: check-divergence reads committed work only; known baseline in the Build log.)

## Acceptance criteria
- **AC-1** `./release.sh [--no-silent] <A.B.C.K> [notes...]`: `--no-silent` counts only as the first
  argument; `--no-silent` anywhere else, or any other argument starting with `--`, stops the script
  with exit 1 before any build or GitHub call. The old usage (`<version> [notes...]`) works unchanged.
- **AC-2** Without `--no-silent`, release.sh reads `SILENT_EARLY_USERS` from
  `~/secrets/kevbox-keys/release.env` (comma-separated; blank or missing = no early users). An entry that
  is not a UUID (8-4-4-4-12 hex) stops the script before the build, naming its position, never its value.
  It writes `build/kevbox-release/kevbox-silent.json` = `{"after": <epoch ms when written + 86 400 000>,
  "earlyUsers": [...]}` and uploads it with the release. With `--no-silent` no such asset is uploaded.
- **AC-3** `SilentUpdateRules.parse` also returns the URL of the asset named `kevbox-silent.json` (null
  when absent). `SilentUpdater` fetches it (at most 64 KB) and parses it leniently: `after` a positive
  JSON integer, `earlyUsers` missing (= empty) or an array of strings. A missing asset, a failed fetch or
  any malformed value means "no silent block": the run logs `no-silent`, downloads and installs nothing.
- **AC-4** `rolloutDue` is due when the signed-in Supabase user id (KevBoxAuth.userId) equals an
  `earlyUsers` entry (trimmed, case-insensitive), or when the server time is at or past `after`.
  `EARLY_EMAILS` and the email parameter are gone. Logs never contain the user id.
- **AC-5** Core's startup card (`SilentUpdatePrefs.fallbackNeeded`) shows on Android 12+ when: the box gave
  up on a newer version (as today: refused, no install permission, 3 failures); or the newest release a
  check read is newer than the installed version and has no silent block; or that release is still not
  installed at `after` + 259 200 000 ms (72 h). `after` is stored on the box's own clock (moved by the
  box's offset from the server, as `seen` does today), so a wrong box date does not matter. The 7-day
  rule (`FALLBACK_AFTER_MS`) is removed. Below Android 12 the card shows as today.
- **AC-6** JVM tests cover: early by id (case and spaces), not early, not yet, due by server time;
  silent JSON parse (missing asset, `"after": "x"`, `0`, `-5`, `earlyUsers` not an array or holding a
  number, valid); the card rules (no silent block, 72 h boundary on a wrong box clock, gave up, caught up).
  `tools/kevbox/test-release-checks.sh` covers the argument rule, the UUID check (an email refused with
  its position, no value printed) and the JSON shape (two ids; blank; passes `python3 -m json.tool`).
- **AC-7** Emulator (orchestrator, after the final review): a debug build with
  `-Pkevbox.updateFeed=http://10.0.2.2:<port>/latest.json` and a local feed. Run A: `kevbox-silent.json`
  with `after` 24 h ahead and earlyUsers = the emulator account's id → the app installs the newer build
  after Home and a forced job (early). Run B: a newer release with no silent asset → `no-silent`, nothing
  installed, and Core's update card appears on the next app open (it shows the real GitHub release,
  because Core's own check does not read the local feed; the point is that the gate opened).

## Non-goals
- Releasing. release.sh is edited, not run, in this job; a release waits for Kevin's yes (and the
  untracked `docs/superpowers/research/2026-10-28-extra-sports-sources.md` blocks release.sh's clean-tree
  check until Kevin commits or moves it).
- TV's metered-network prompt rule: Live downloads at install time on any network, so it has no
  "no download possible" case.

## Decisions
- The silent block travels as a release asset, not in the release notes, because Core shows the notes
  and an asset can be left out with `--no-silent`.
- `after` is computed by release.sh (as TV does) instead of `published_at + 24 h`, so both apps read
  one rule from the release.
- One `SILENT_EARLY_USERS` line serves both apps: TV and Live sign in to the same Supabase project
  (checked 2026-10-29 by comparing the configured URLs).

## Tasks

### Task 1: release.sh publishes the silent block
**Delivers:** AC-1, AC-2, AC-6 (release-checks tests)
**Files:** `release.sh`, `tools/kevbox/release-checks.sh`, `tools/kevbox/test-release-checks.sh`
**Interfaces:** in release-checks.sh, shaped like the existing helpers (return 1 with the reason on
stderr; release.sh calls them as `check_x || die ...`): `check_release_args "$@"` (returns 1 on a
misplaced or unknown `--` option), `check_early_users "<comma list>"` (returns 1, message names the
position), and `render_silent_json <now_ms> "<comma list>"` (prints the JSON on stdout). After
`check_release_args` passes, release.sh itself tests `$1` for `--no-silent`, sets `no_silent=1` and
shifts it, before it reads the version from `$1`.
**Does:**
- Argument check first, then the existing checks; the early-user check sits with them, before the build.
- The pre-build cleanup also removes `"$out_dir/kevbox-silent.json"`, so a file from an earlier run
  can never ship.
- After the build, write `kevbox-silent.json` into the release folder and add it to `gh release create`
  unless `--no-silent`.
- Header comment documents `--no-silent` and `SILENT_EARLY_USERS`.
**Tests:** the three helpers, as in AC-6.
**Verify:** `bash tools/kevbox/test-release-checks.sh && bash -n release.sh`

### Task 2: The app reads the block and uses TV's card rules
**Delivers:** AC-3, AC-4, AC-5, AC-6 (JVM tests)
**Files:** `kevbox/release/SilentUpdateRules.kt`, `SilentUpdater.kt`, `SilentUpdatePrefs.kt`,
`kevbox/di/KevBoxModule.kt`, their tests, `UPSTREAM-SYNC.md` (the 7-day note at line ~157),
`features/shell/OwnTVShell.kt` (only the `KevBox:` comment at ~2176-2177 that says "7 days")
**Interfaces:** `SilentRelease` gains `silentUrl: String?`; `data class SilentBlock(val afterMs: Long,
val earlyUsers: List<String>)` with `SilentBlock.parse(json: String): SilentBlock?`;
`rolloutDue(block: SilentBlock, signedInUserId: String?, serverNowMs: Long)`;
`SilentUpdater(..., signedInUserId: () -> String?, ...)`; `fallbackNeeded(gaveUpVersion, seenVersion,
seenHasSilent: Boolean, seenAfterBoxMs: Long?, installedVersion, boxNowMs)`.
**Does:**
- After `isNewer` and `skips`, fetch and parse the block. The `prefs.seen(...)` call moves from its
  current place (SilentUpdater.kt:120, before `isNewer`) to just after the block fetch, on both paths:
  `seen(version, hasSilent, afterBoxMs)`; no block → record it and return `no-silent`.
- `seen(...)` stores whether the release had a block and its `after` on the box clock; its "nothing
  changed, skip the write" check compares every stored field, so a later run that finds the block
  replaces an earlier "no block" record.
- `SAFETY_NET_MS = 259_200_000L` replaces `FALLBACK_AFTER_MS`.
- KevBoxModule passes `auth.userId.value` instead of `auth.email.value` to SilentUpdater (LoginFileSignIn keeps its email).
**Tests:** as in AC-6.
**Verify:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew testStandardDebugUnitTest --tests 'tv.own.owntv.kevbox.release.*'`

## Build log
baseline: all green on 27cae12a (2026-10-29 10:4x): testStandardDebugUnitTest + lintStandardDebug BUILD SUCCESSFUL, test-release-checks 35 passed, check-divergence 0 failures.
plan check (6285a91a): 4 problems folded: seen() moves after the block fetch and compares all fields; helpers return 1 and release.sh shifts --no-silent itself; pre-build cleanup removes kevbox-silent.json; Run B proves the card gate, not the local build. Also the OwnTVShell 7-day comment joins Task 2.
Task 1+2: started at e84be319
Task 1+2: landed (011716b1, 0f470669). Added SilentUpdateRules.SILENT_ASSET; touched tools/i18n/safe_literals.txt (new literals classified, stale email entry pruned).
ocr: committed (3 comments)
review: gate green (88 test classes rerun, release checks 68/68, divergence 0, bash -n ok); AC-1..AC-6 hold. important 1 (release.sh header, UPSTREAM-SYNC ~588): boxes on today's Live build ignore kevbox-silent.json, so --no-silent only works once boxes run a build from this branch; the first release from it must be a normal one (doc fix). minor 2 SilentUpdater.kt:135-142 permission checked after rolloutDue (card up to 24 h later than TV); minor 3 card waits for a silent run (TV decides at app open); minor 4 SilentUpdatePrefs.kt:46-50 afterBoxMs drifts so seen() writes every run; minor 5 SilentUpdater.kt:185-196 non-IO exceptions and the URL builder outside the try; minor 6 SilentUpdatePrefs.kt:30 an old record without seen_has_silent reads as "no block".
ocr: medium release.sh:140 a failing `source` of release.env still exits 0 and gives an empty early list; medium SilentUpdater.kt:130 a failed fetch of an existing asset is treated like a missing asset and opens the card at once; low SilentUpdatePrefs.kt:48 (same as review minor 4).
fix list: (1) important, doc: release.sh header + UPSTREAM-SYNC say --no-silent only takes effect on boxes running this build or later; first release from this branch is a normal one. (2) ocr medium: release.sh stops when release.env cannot be sourced. (3) ocr medium + review 5: a missing asset = no block ("no-silent", seen without block); a fetch that fails for any reason (any exception but cancellation, bad URL, non-2xx, over 64 KB, malformed counts as no block) = "silent-fetch-failed", the earlier seen record is kept and nothing installs. (4) review 2: check install permission before rolloutDue, as TV does. (5) review 4 + ocr low: seen() skips the write when afterBoxMs moved by under 60 s. (6) review 6: a record without seen_has_silent does not open the card by the no-block rule.
skipped: review 3 (Live decides from its own silent run; TV reads version.json at app open; Live's Core card check reads GitHub, not the silent file, so matching needs Core-side changes; Run B already expects "next app open").
