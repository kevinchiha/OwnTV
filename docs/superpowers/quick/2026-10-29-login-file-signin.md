# Login file sign-in

## Goal
KevBox Setup on a phone signs a customer in to KevBox Live by leaving `kevbox-login.json` in the
app's folder, with no typing on the TV, following `~/projects/kevbox-setup/docs/login-file.md`.

## Scope
New `kevbox/session/LoginFileSignIn.kt`, a small change to `KevBoxCoordinator.signIn`, Koin wiring
in `kevbox/di/KevBoxModule.kt`, an `onResume` in `MainActivity.kt`, UPSTREAM-SYNC.md, tests.

**Gate:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew testStandardDebugUnitTest lintStandardDebug && bash tools/kevbox/check-divergence.sh` (commit first: check-divergence reads committed work only)

## Acceptance criteria
- **AC-1** On every `MainActivity.onResume` (which also runs at app start), the app looks for
  `getExternalFilesDir(null)/kevbox-login.json`. The debug and release ids are both `tv.kevbox.live`.
- **AC-2** A file whose `requestedAt` is more than 600 000 ms older than `System.currentTimeMillis()`
  is deleted; no sign-in runs and no result file is written. Exactly 600 000 ms old still counts.
- **AC-3** A file that is unreadable, not a JSON object, over 64 KB, or missing `email` (non-blank
  text), `password` (text) or `requestedAt` (a number) is deleted with no sign-in and no result.
- **AC-4** A good file is deleted before the sign-in call starts. If the delete fails, no sign-in
  runs and the result is `{"ok": false, "error": "Sign-in failed. Try again."}`.
- **AC-5** The sign-in goes through `KevBoxCoordinator.signIn`, the same call the sign-in screen's
  view model makes (it calls `KevBoxAuth.signIn`, then the access and playlist checks).
- **AC-6** Result file `kevbox-login.result.json` in the same folder: `{"ok":true}` on success;
  otherwise `{"ok":false,"error":E}` with E = "Wrong email or password" (wrong credentials),
  "No internet connection" (no network), "Sign-in failed. Try again." (any other failure). Same
  words as the screen's English strings.
- **AC-7** The result is written as soon as the server answers the sign-in, before the playlist
  check that follows it finishes.
- **AC-8** If no answer has come 20 s after the file was read, the result is
  `{"ok":false,"error":"Sign-in took too long. Check the box's internet."}`. Only one result is
  ever written per file; a late answer does not overwrite it.
- **AC-9** The result file appears whole: written to a temporary file in the same folder, then
  renamed.
- **AC-10** Already signed in as a different email (compared trimmed and lowercased): sign out
  first through `KevBoxCoordinator.signOut` (what a person must do before the screen shows), then
  sign in. Already signed in as the same email: sign in again without signing out, so favourites
  and the chosen line stay.
- **AC-11** One file at a time: an `onResume` while a file sign-in runs does nothing.
- **AC-12** Nothing logs, reports to Sentry, or puts in the result the password or the file's
  contents. Parsing errors (whose messages quote the input) are swallowed, never rethrown.
- **AC-13** JVM tests cover: fresh file, stale file, bad JSON, missing key, delete before sign-in,
  each result shape, timeout, different and same account.
- **AC-14** Emulator: on `kevbox_tv` with a debug build, a hand-pushed file with a fresh
  `requestedAt` signs in; the login file is gone and the result file reads `{"ok":true}`.

## Non-goals
- No change to the contract file or to KevBox Setup.
- No new on-screen text. A file sign-in shows what a typed one shows next.
- No localized `error` text: it goes to the phone and Kevin's Telegram report, both English.

## Decisions
- `onResume` only, not also `Application.onCreate`: Setup starts the activity (monkey,
  LEANBACK_LAUNCHER), so `onResume` runs at every start; the app process alone has nothing to show.
- Sign out first when the file names another account, over signing in straight on top: the old
  member's lines and proxy must go, and `signOut` is the one place that wipes them. Cost: a wrong
  password on a box signed in as someone else leaves it signed out, which is what typing would do.
- Coordinator `signIn` gains an `onAnswer` callback over a second sign-in path, so the screen and
  the file share one call and the result is not held back by the playlist fetch (up to 15 s more).
- 20 s deadline from the file read, leaving Setup's 30 s window room for app start.

## Tasks

### Task 1: Read, delete, sign in, write the result
**Delivers:** AC-2, AC-3, AC-4, AC-6, AC-7, AC-8, AC-9, AC-10, AC-11, AC-12, AC-13
**Files:** `app/src/main/java/tv/own/owntv/kevbox/session/LoginFileSignIn.kt` (new, fork-only header),
`app/src/main/java/tv/own/owntv/kevbox/KevBoxCoordinator.kt`,
`app/src/test/java/tv/own/owntv/kevbox/session/LoginFileSignInTest.kt` (new),
`app/src/test/java/tv/own/owntv/kevbox/KevBoxCoordinatorTest.kt`
**Interfaces:**
- `KevBoxCoordinator.signIn(email: String, password: String, onAnswer: (SignInResult) -> Unit = {}): SignInResult`,
  `onAnswer` called once, right after `auth.signIn` returns, before the access/playlist checks.
- `class LoginFileSignIn(dir: () -> File?, now: () -> Long, signedInEmail: () -> String?, signOut: suspend () -> Unit, signIn: suspend (email: String, password: String, onAnswer: (SignInResult) -> Unit) -> SignInResult, scope: CoroutineScope, io: CoroutineDispatcher, deadlineMs: Long = 20_000)`
  with `fun check()` (returns at once; work runs in `scope` on `io`) and a test hook
  `suspend fun checkNow()` doing the same work and returning when the result is written or none is due.
**Does:**
- Reads, validates and deletes the file as AC-2 to AC-4 say, then the account rule of AC-10.
- Writes one result per AC-6 to AC-9; busy guard per AC-11; no logging per AC-12.
**Tests:**
- File cases: fresh signs in and writes ok; stale, bad JSON, missing key each delete with no call and no result.
- Order and shapes: file already gone inside the sign-in call; each SignInResult maps to its exact JSON; timeout text; late answer ignored.
- Accounts: different email signs out first; same email does not; coordinator `onAnswer` fires before the playlist check ends.
**Verify:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew testStandardDebugUnitTest --tests '*LoginFileSignInTest' --tests '*KevBoxCoordinatorTest'`

### Task 2: Wire it to the app
**Delivers:** AC-1, AC-5
**Files:** `app/src/main/java/tv/own/owntv/kevbox/di/KevBoxModule.kt`, `app/src/main/java/tv/own/owntv/MainActivity.kt`, `UPSTREAM-SYNC.md`
**Does:**
- Koin `single` for `LoginFileSignIn` with `dir = { androidContext().getExternalFilesDir(null) }`,
  `now = System::currentTimeMillis`, `signedInEmail = { get<KevBoxAuth>().email.value }`,
  `signOut`/`signIn` from the `KevBoxCoordinator`, the app scope `get()`, `Dispatchers.IO`.
- `MainActivity.onResume` override calling its `check()`, with a `KevBox:` comment; its
  UPSTREAM-SYNC.md row gains a grep for that call and a short paragraph says why.
**Tests:** none beyond Task 1 (wiring; the emulator check covers it).
**Verify:** `GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew compileStandardDebugKotlin`

### Task 3: Emulator check (run by the orchestrator, after the final review)
**Delivers:** AC-14
**Files:** none
**Does:**
- Builds `assembleX86_64Debug` with `~/secrets/kevbox-keys/kevbox-live.env` sourced (the AVD is
  x86_64; the `standard` flavor packs only arm ABIs). Kevin chose to uninstall the release copy on
  `kevbox_tv` and install this debug build (2026-10-29).
- Pushes a login file built from `~/secrets/kevbox-live-test.json` (Kevin's own account) with
  `requestedAt` from `adb shell date +%s%3N`, starts the app with monkey, polls like Setup does.
- Also checks a wrong password and a stale file, and the already-signed-in case.
**Verify:** login file gone; result file `{"ok":true}`; screen shows "All channels".

## Build log
