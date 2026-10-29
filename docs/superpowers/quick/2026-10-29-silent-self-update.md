# Silent self-update

## Goal
KevBox Live boxes on Android 12 and later install new releases by themselves, with no prompt and
never while someone is watching; Kevin's own boxes get a release first, everyone else a day later.

## Scope
New `kevbox/release/SilentUpdate*.kt`, a small change to `kevbox/release/UpdateProfileAttacher.kt`,
the manifest, `app/build.gradle.kts` (one debug-only BuildConfig field), Koin wiring, `OwnTVApp.kt`
start line, the startup update card in `features/shell/OwnTVShell.kt`, UPSTREAM-SYNC.md, tests.

**Gate:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew testStandardDebugUnitTest lintStandardDebug && bash tools/kevbox/check-divergence.sh` (commit first: check-divergence reads committed work only; its known baseline failure is `features/sports/SportsViewModel.kt`)

## Acceptance criteria
- **AC-1** The check reads `https://api.github.com/repos/<CoreBuildInfo.releaseRepo>/releases/latest`
  (kevinchiha/OwnTV), or `BuildConfig.KEVBOX_UPDATE_FEED` when that is not blank. It picks the APK
  asset for the box's ABI by Core's rule (name contains `x86_64` exactly when
  `Build.SUPPORTED_ABIS[0] == "x86_64"`) and the profile asset named by
  `UpdateProfileAttacher.dexMetadataUrl(apkUrl, sdk)` when the release has it.
- **AC-2** A release counts as newer by Core's rule (numeric compare segment by segment, `v` prefix
  dropped) against `BuildConfig.VERSION_NAME`. A local build (99.99.99.99) never updates.
- **AC-3** A box signed in as an email in the early list (`kevin.chiha@gmail.com`, compared trimmed
  and lowercased) installs a newer release at once. Every other box, signed out included, waits
  until 24 h after the release's `published_at`, measured by the API response's `Date` header (the
  box clock only when the header is missing or unreadable).
- **AC-4** Installs only when no `MainActivity` is started, or when one is started but nothing plays
  (`PlaybackSession.active` false) and no remote key was pressed for 3 h (`LastPress`). Checked
  before the download and again right before the install.
- **AC-5** Downloads go to `filesDir/silent-update/`. The APK is refused (deleted, nothing
  installed) when its length differs from Content-Length, its package name is not this app's, its
  versionCode is not above the installed one, or free space is under twice its size. A missing or
  unreadable profile never blocks the APK. Both files are deleted once the session is committed or
  the run fails.
- **AC-6** Android 12 (API 31) and later only: one `PackageInstaller` session, `MODE_FULL_INSTALL`,
  `setRequireUserAction(USER_ACTION_NOT_REQUIRED)`, app label `KevBox silent update`, APK entry
  `kevbox-update`, profile entry `kevbox-update.dm`, committed to a non-exported manifest receiver.
  The manifest declares `android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION`. Below API 31
  nothing installs silently and today's prompt stays as it is.
- **AC-7** `UpdateProfileAttacher` leaves alone any session whose app label is `KevBox silent update`.
- **AC-8** Install results: success needs nothing (Android restarts nothing; the next open runs the
  new version). `STATUS_PENDING_USER_ACTION` never shows the system prompt: the session is abandoned
  and the box is marked "silent refused", which brings back Core's startup update card (AC-9). Any
  other failure counts against that version; after 3 failures that version is skipped until a newer
  one appears.
- **AC-9** Core's startup "update available" card no longer runs on API 31+ unless the box is marked
  "silent refused". The manual check in More › About stays as it is everywhere.
- **AC-10** Runs from WorkManager: a unique periodic job every 6 h needing a network (kept across
  starts), and a unique one-time job 15 min after `MainActivity` stops (replaced at each stop). Only
  one run at a time.
- **AC-11** One `Log.i` line per run with its outcome (no URLs with tokens, no emails).
- **AC-12** JVM tests cover the release parse, the newer rule, the rollout rule, the idle rule and
  the install-result rule (AC-1 to AC-4, AC-8).
- **AC-13** Emulator: a debug build at 5.2.1.90 on `kevbox_tv`, with a local feed offering a
  debug-signed 5.2.1.91, becomes 5.2.1.91 after Home and a forced job run, with no dialog on screen.

## Non-goals
- Android 9 to 11 boxes: Android has no silent self-update before 12; they keep the prompt.
- No server change and no kevbox.dev switch for the early list; it lives in the app for now.
- Reopening the app after an update: Android blocks a background app from opening a screen.
- Core's own updater stays as it is (Core is a prebuilt library).

## Decisions
- KevBox's own small installer over Core's `UpdateManager`, because Core's session never sets
  `setRequireUserAction` and Core is a binary dependency.
- Early list in the app (one email) over a kevbox.dev flag: no server work; Kevin edits one line.
  Open point for Kevin: which accounts his own TVs are signed in with.
- Server `Date` over the box clock for the 24 h wait: some boxes have a wrong date.
- Hide the startup card on API 31+: otherwise every customer gets the prompt the moment a release
  appears and the 24 h wait means nothing.
- 3 h without a key press as "nobody is watching" while the app is open: boxes that never sleep
  would otherwise never update. Cost: someone watching the small preview box for 3 h with no press
  loses it to the update.

## Tasks

### Task 1: The rules
**Delivers:** AC-1, AC-2, AC-3, AC-4, AC-12 (parse, newer, rollout, idle)
**Files:** `app/src/main/java/tv/own/owntv/kevbox/release/SilentUpdateRules.kt` (new),
`app/src/test/java/tv/own/owntv/kevbox/release/SilentUpdateRulesTest.kt` (new)
**Interfaces:**
- `data class SilentRelease(val version: String, val publishedAtMs: Long, val apkUrl: String, val dmUrl: String?)`
- `object SilentUpdateRules` with `fun parse(body: String, wantX86: Boolean, sdk: Int): SilentRelease?`,
  `fun isNewer(remote: String, local: String): Boolean`,
  `fun rolloutDue(release: SilentRelease, signedInEmail: String?, serverNowMs: Long): Boolean`,
  `fun idle(activityStarted: Boolean, playing: Boolean, lastPressMs: Long, nowMs: Long): Boolean`,
  `fun serverNow(dateHeader: String?, boxNowMs: Long): Long`, and constants `EARLY_EMAILS`,
  `ROLLOUT_DELAY_MS = 24 h`, `IDLE_AFTER_MS = 3 h`.
**Does:** pure functions, no Android types except none (sdk and ABI come in as values).
**Tests:**
- parse: arm vs x86_64 pick, .dm vs -api28.dm vs none by sdk, missing assets/tag/bad JSON give null.
- isNewer and rolloutDue: 99.99.99.99 never older; early email at once; others at 24 h exactly; Date header parse and fallback.
- idle: background always idle; foreground idle only when not playing and 3 h since the last press.
**Verify:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew testStandardDebugUnitTest --tests '*SilentUpdateRulesTest'`

### Task 2: Download, install, results
**Delivers:** AC-5, AC-6, AC-7, AC-8, AC-10 (single run), AC-11, AC-12 (result rule)
**Files:** `app/src/main/java/tv/own/owntv/kevbox/release/SilentUpdater.kt` (new),
`app/src/main/java/tv/own/owntv/kevbox/release/SilentUpdateReceiver.kt` (new),
`app/src/main/java/tv/own/owntv/kevbox/release/SilentUpdatePrefs.kt` (new),
`app/src/main/java/tv/own/owntv/kevbox/release/UpdateProfileAttacher.kt`,
`app/src/main/AndroidManifest.xml`, `app/src/test/java/tv/own/owntv/kevbox/release/SilentUpdatePrefsTest.kt` (new)
**Interfaces:**
- `class SilentUpdater(context: Context, client: OkHttpClient, prefs: SilentUpdatePrefs, signedInEmail: () -> String?, activityStarted: () -> Boolean, playing: () -> Boolean, lastPressMs: () -> Long, now: () -> Long, feedUrl: String)`
  with `suspend fun run(): String` (returns the outcome word it logged). A `Mutex` inside makes a
  second `run()` that starts while one runs return `busy` at once (AC-10's one run at a time).
- `class SilentUpdatePrefs(prefs: SharedPreferences)` with `silentRefused: Boolean`,
  `fun failed(version: String)`, `fun skips(version: String): Boolean` (3 failures),
  `fun markRefused()`, and a pure `companion fun outcomeOf(status: Int): Outcome` (SUCCESS,
  REFUSED for PENDING_USER_ACTION, FAILED otherwise).
- The commit `PendingIntent` carries the release version as extra `version` (explicit intent to the
  receiver class, `FLAG_MUTABLE` so the installer can add its extras).
- `SilentUpdateReceiver` (manifest, `exported=false`) reads `EXTRA_STATUS`, `EXTRA_SESSION_ID` and `version`, calls
  `outcomeOf`, abandons the session and marks refused on REFUSED, records the failure on FAILED.
- `const val SILENT_SESSION_LABEL = "KevBox silent update"` in SilentUpdater's companion.
**Does:**
- run(): API < 31 or `skips`/not newer/not due/not idle → stop with a word; else download, verify
  per AC-5, re-check idle, install per AC-6.
- UpdateProfileAttacher's session callback returns early for a session with that label.
**Tests:**
- prefs: refused flag survives a new instance; 3 failures skip that version, a newer version is not skipped.
- outcomeOf maps SUCCESS, PENDING_USER_ACTION and three failure codes.
**Verify:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew testStandardDebugUnitTest --tests '*SilentUpdate*' && GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew compileStandardDebugKotlin`

### Task 3: Wiring and the startup card
**Delivers:** AC-9, AC-10, plus AC-1's feed field
**Files:** `app/src/main/java/tv/own/owntv/kevbox/release/SilentUpdateWorker.kt` (new),
`app/src/main/java/tv/own/owntv/kevbox/di/KevBoxModule.kt`, `app/src/main/java/tv/own/owntv/OwnTVApp.kt`,
`app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt`, `app/build.gradle.kts`, `UPSTREAM-SYNC.md`
**Interfaces:**
- `class SilentUpdateScheduler(application: Application, workManager: () -> WorkManager)` with
  `fun start()`: registers activity callbacks that keep the set of started `MainActivity`
  instances (add on every start, remove on every stop, config changes included, so the set is
  exact), enqueues the periodic job, enqueues the one-time job on a stop that is not a config change;
  exposes `val activityStarted: () -> Boolean` for SilentUpdater.
- `SilentUpdateWorker : CoroutineWorker` (built by WorkManager's default factory; Core's
  KoinWorkerFactory returns null for unknown classes) gets `SilentUpdater` from Koin and calls run().
- `BuildConfig.KEVBOX_UPDATE_FEED`: debug from Gradle property `kevbox.updateFeed` (blank when unset),
  release always blank.
- From Task 2 as built: pass `BuildConfig.KEVBOX_UPDATE_FEED` to SilentUpdater's `feedUrl` as is
  (blank = GitHub); build the prefs single with `SilentUpdatePrefs.open(androidContext())`, the same
  file the receiver opens.
**Does:**
- Koin singles for prefs, updater, scheduler; `OwnTVApp` starts the scheduler with the other kevbox
  `start()` lines (with a `KevBox:` comment).
- OwnTVShell's startup check runs only when `Build.VERSION.SDK_INT < 31 || prefs.silentRefused`,
  with a `KevBox:` comment; UPSTREAM-SYNC.md rows and a short section explain both hooks.
**Tests:** none new (wiring; the emulator check covers it).
**Verify:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew compileStandardDebugKotlin`

### Task 4: Emulator check (run by the orchestrator, after the final review)
**Delivers:** AC-13
**Files:** none
**Does:** builds two x86_64 debug APKs (VERSION_NAME 5.2.1.90 and 5.2.1.91, codes from
`tools/kevbox/version.sh`, feed `-Pkevbox.updateFeed=http://10.0.2.2:<port>/latest.json`), serves a
GitHub-shaped `latest.json` (tag `v5.2.1.91`, `published_at` two days back, asset
`KevBoxLive-x86_64.apk`) plus the .91 APK from the host (the AVD stays signed in as Kevin from the
login-file test, so the early rule would pass too), installs .90 with `-r -d`, opens the app,
presses Home, forces the job with `cmd jobscheduler run -f`, and reads the version and the screen.
**Verify:** `dumpsys package tv.kevbox.live` shows 5.2.1.91; no installer dialog appeared.

## Build log
Task 1: started at 3758a07e
baseline: same as kevbox-live (tests and lint green on 2026-10-29 05:2x; check-divergence fails only on features/sports/SportsViewModel.kt). Leave it alone.
Task 1+2: landed (37f98e67, 3b599fe6). Bent: `markRefused(version: String? = null)` also skips that version so a refusing box stops downloading it every 6 h; added `SilentUpdatePrefs.open(context)`, `FILE`, `MAX_FAILURES`, `SilentUpdater.EXTRA_VERSION`; blank `feedUrl` means GitHub. Also touched tools/i18n/safe_literals.txt and the manifest row of UPSTREAM-SYNC.md.
Task 3: started at b6681254
Task 3: landed (c78f015c). Scheduler lives in SilentUpdateWorker.kt; also touched tools/i18n/safe_literals.txt.
review: gate green apart from the baseline; AC-1..AC-12 pass as written.
review: critical, SilentUpdater.kt:107-135: a 62 MB download on a slow line outlives the 10-minute worker limit and restarts from zero every run. Fix: resume with HTTP Range, stop cooperatively on cancel.
review: important, SilentUpdatePrefs.kt:38: "silent refused" never clears. Fix: replace with a "gave up on version V" mark that stops mattering once the installed version reaches V.
review: important, OwnTVShell.kt:2181: the card stays hidden when silent install gives up for other reasons. Fix: give up (show the card) after MAX_FAILURES, and when a seen release is 7 days old and still not installed.
review: important, REQUEST_INSTALL_PACKAGES must be allowed for a no-prompt install. Fix: check canRequestPackageInstalls() before downloading; when off, give up on that version (card comes back). Emulator: allow the app-op first.
review: minor 5 (idle check right before commit), 6 (no schedule below API 31), 7 (KEEP for the one-time job), 8 (compare signing certificates before installing), 9 (retry once without the .dm), 10 (monotonic clock for idle). Decided: fix all.
ocr: high/medium, the same card fallback and refused-flag points as the reviewer's 2 and 3: one item each.
ocr: medium, SilentUpdater.kt:57: CancellationException swallowed. Decided: fix.
ocr: medium, SilentUpdater.kt:202: a missing Content-Length counts as a bad release. Decided: fix (verify the file instead).
ocr: medium, SilentUpdater.kt:76: wall clock in the idle rule. Same item as reviewer 10.
ocr: low (dm bytes() before size check). Decided: fix, it is two lines.
