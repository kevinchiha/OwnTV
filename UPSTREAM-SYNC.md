# Keeping KevBox Live in step with upstream OwnTV

KevBox Live is a fork of [ahXN00/OwnTV](https://github.com/ahXN00/OwnTV). The fork lives on the
`kevbox-live` branch of `kevinchiha/OwnTV` and changes as few upstream files as it can: new code
sits under `app/src/main/java/tv/own/owntv/kevbox/`, the rebrand overrides Core's strings and art
by resource name, and every edit to an upstream file carries a `KevBox:` comment. This file says
how to take a new upstream release, check that nothing KevBox did got lost, and publish.

## One-time setup

1. Add upstream as a remote and fetch its tags:

   ```sh
   git remote add upstream https://github.com/ahXN00/OwnTV.git
   git fetch upstream --tags
   ```

2. Sign `gh` in with an account that can create releases on `kevinchiha/OwnTV`:
   `gh auth login`, then `gh auth status` to confirm.

3. Create `~/secrets/kevbox-keys/kevbox-live.env` with exactly these two lines, filled from the
   KevBox Supabase dashboard (Project settings, API), then `chmod 600` it:

   ```sh
   KEVBOX_SUPABASE_URL=https://<project-ref>.supabase.co
   KEVBOX_SUPABASE_ANON_KEY=<the anon public key>
   ```

   The anon key ships inside the APK, but it stays out of the repo. Only `release.sh` reads this
   file, and it never prints it.

4. The release keystore needs nothing new. `~/secrets/kevbox-keys/release.env` already holds the
   `NUVIO_RELEASE_*` values for `kevboxtv.jks`, the KevBox TV key, and `release.sh` maps them to
   the `KEYSTORE_*` variables `app/build.gradle.kts` reads.

5. `apksigner` comes from the newest `$ANDROID_HOME/build-tools/*` (default
   `/home/kevin/Android/Sdk`).

## Merge steps for each upstream release

Merge upstream's release tag, never `upstream/main`. Upstream's `main` moves ahead of its last
release every time Core publishes and the pin bump merges itself.

1. Fetch and update:

   ```sh
   git fetch upstream --tags
   git switch kevbox-live
   git pull --ff-only
   ```

2. Run the hidden-features sweep below on the new tag before you merge, so you know what to look
   for.

3. Merge the tag: `git merge vX.Y.Z`.

4. Resolve conflicts by keeping upstream's change and every `KevBox:` block. The proof commands in
   the table below show which KevBox change each file must still hold. Generated files
   (`kevbox_brand_strings.xml`, the art overrides) are never merged by hand: take either side and
   regenerate them in the next step.

5. If `owntvCore` in `gradle/libs.versions.toml` moved, rerun both rebrand scripts and commit what
   they write (see "Regenerating the rebrand").

6. Check the result:

   ```sh
   bash tools/kevbox/check-divergence.sh
   python3 tools/kevbox/rebrand-strings.py --check
   python3 tools/kevbox/rebrand-art.py --check
   ./gradlew assembleStandardRelease
   bash tools/kevbox/check-brand.sh app/build/outputs/apk/standard/release/app-standard-release-unsigned.apk
   ./gradlew testStandardDebugUnitTest lintStandardDebug
   ```

   Then run every proof command in the table. `check-divergence.sh` fails on any changed upstream
   file that has a diff hunk without a `KevBox:` comment or is missing from the table, so a file
   that upstream's merge rewrote shows up there.

7. Push `kevbox-live` (`git push origin kevbox-live`), then release.

## Changed upstream files

Every upstream file this fork changes, and a command that proves the KevBox change survived a
merge. Each `grep` must print at least one line. `check-divergence.sh` reads the first column, so
keep one file per row, with its full path.

| File | Proof the change survived |
| --- | --- |
| `app/build.gradle.kts` | `grep -n 'tv.kevbox.live' app/build.gradle.kts` |
| `app/src/main/java/tv/own/owntv/MainActivity.kt` | `grep -n 'kevbox.onForeground()' app/src/main/java/tv/own/owntv/MainActivity.kt` |
| `app/src/main/java/tv/own/owntv/OwnTVApp.kt` | `grep -n 'releaseRepo = "kevinchiha/OwnTV"' app/src/main/java/tv/own/owntv/OwnTVApp.kt` |
| `app/src/main/java/tv/own/owntv/player/PlayerHud.kt` | `grep -n 'kevbox.onChannelFailed()' app/src/main/java/tv/own/owntv/player/PlayerHud.kt` |
| `app/src/main/java/tv/own/owntv/features/shell/components/SettingsScreen.kt` | `grep -n 'KevBoxChoosePlaylistSettingsScreen' app/src/main/java/tv/own/owntv/features/shell/components/SettingsScreen.kt` |
| `app/src/main/java/tv/own/owntv/features/shell/components/ContentPane.kt` | `grep -n 'KevBox: no "add source" action' app/src/main/java/tv/own/owntv/features/shell/components/ContentPane.kt` |
| `app/src/main/java/tv/own/owntv/features/more/MoreScreen.kt` | `grep -n 'MoreItem.ACCOUNT' app/src/main/java/tv/own/owntv/features/more/MoreScreen.kt` |
| `app/src/main/java/tv/own/owntv/features/more/MorePages.kt` | `grep -n 'KevBox: the GitHub contributions line' app/src/main/java/tv/own/owntv/features/more/MorePages.kt` |
| `app/src/main/java/tv/own/owntv/features/setup/SetupWizard.kt` | `grep -n 'KEVBOX_OPEN_STEPS' app/src/main/java/tv/own/owntv/features/setup/SetupWizard.kt` |
| `app/src/main/java/tv/own/owntv/features/settings/EpgSourcesScreen.kt` | `grep -n 'HttpClient.redactUrl' app/src/main/java/tv/own/owntv/features/settings/EpgSourcesScreen.kt` |
| `app/src/test/java/tv/own/owntv/features/shell/SettingsSearchCoverageTest.kt` | `grep -n '"ManageSourcesScreen.kt"' app/src/test/java/tv/own/owntv/features/shell/SettingsSearchCoverageTest.kt` |
| `.github/workflows/release-notes.yml` | `grep -n "github.repository == 'ahXN00/OwnTV'" .github/workflows/release-notes.yml` |
| `.github/workflows/automerge-core-bump.yml` | `grep -n "github.repository == 'ahXN00/OwnTV'" .github/workflows/automerge-core-bump.yml` |
| `.github/workflows/android.yml` | `grep -n "github.repository == 'ahXN00/OwnTV'" .github/workflows/android.yml` |
| `.gitignore` | `grep -n 'tools/kevbox/__pycache__/' .gitignore` |
| `gradle/libs.versions.toml` | Unchanged on purpose (Core is not forked): `git diff --exit-code upstream/main...HEAD -- gradle/libs.versions.toml` prints nothing and exits 0 |

The three dots in the last command compare against the last upstream commit merged into the
branch. Two dots would compare against `upstream/main`'s tip, which moves ahead after every Core
pin bump.

## Divergence exemptions

`tools/kevbox/divergence-exempt.txt` lists the upstream files that may change without a
`KevBox:` comment and without a row in the table above, because their format cannot hold a
comment:

- `tools/i18n/safe_literals.txt`: `check_hardcoded_strings.py classify-safe` writes it and owns
  its format, which keeps no free comments. KevBox's technical literals (JSON keys, URL paths,
  preference keys) are classified there.

Binary files would go there too, each by path with its reason. The fork changes none today: the
rebrand adds new override files and leaves the app's own upstream images alone.

## Hidden-features sweep

KevBox Live keeps members away from these screens (AC-33): Add source, Manage sources, source
edit, add from phone (QR and PIN companion), Local sync, and Backup and restore, local and remote.
A new upstream release can add a new way in. Before each merge, list what upstream added between
the release you have and the new one (`vOLD` is the upstream tag `kevbox-live` is on now):

```sh
# New calls into the locked screens, companion modes or deep links
git diff vOLD vX.Y.Z -- app/src/main/java app/src/main/AndroidManifest.xml \
  | grep -nE '^\+.*\b(ManageSourcesScreen|AddSourceScreen|AddSourceChooserScreen|RemoteSetupScreen|LocalSyncScreen|SetupLocalSyncScreen|BackupScreen|RemoteBackupRestoreScreen|CompanionController|CompanionMode|LauncherDeepLink|android:scheme|android:host)\b'

# New Settings tabs, More items and setup wizard steps
git diff vOLD vX.Y.Z -- \
  app/src/main/java/tv/own/owntv/features/shell/components/SettingsScreen.kt \
  app/src/main/java/tv/own/owntv/features/more/MoreScreen.kt \
  app/src/main/java/tv/own/owntv/features/setup/SetupWizard.kt \
  | grep -nE '^[-+].*enum class (SettingsTab|MoreItem|Step)\b'
```

After the merge, the same names across the whole app:

```sh
grep -rnwE 'ManageSourcesScreen|AddSourceScreen|AddSourceChooserScreen|RemoteSetupScreen|LocalSyncScreen|SetupLocalSyncScreen|BackupScreen|RemoteBackupRestoreScreen' \
  app/src/main/java --include='*.kt' | grep -v 'KevBox:'
grep -rnE 'CompanionController|CompanionMode|LauncherDeepLink' app/src/main/java --include='*.kt'
```

Today the first command finds only the screens' own files, calls between those screens (none of
which a member can open), comments, and the `SetupWizard.kt` branches for steps that
`KEVBOX_OPEN_STEPS` blocks. Any other hit is a new route: cut it with a `// KevBox:` comment where
the code was, and add a row to the table if the file is new to it. A new `SettingsTab`,
`MoreItem` or `Step` entry needs a look too: hide it the same way if it leads to a locked screen.
Leave the locked screens' files in place. Deleting upstream files turns every later merge into a
conflict.

## Regenerating the rebrand

Both scripts read Core's resources at the pinned version (`owntvCore`), downloaded once into
`build/kevbox/`. Run them after any Core pin change, then commit what they write:

```sh
python3 tools/kevbox/rebrand-strings.py    # app/src/main/res/values*/kevbox_brand_strings.xml
python3 tools/kevbox/rebrand-art.py        # the icon, banner, splash, mark and wordmark overrides
python3 tools/kevbox/rebrand-strings.py --check
python3 tools/kevbox/rebrand-art.py --check
```

`rebrand-art.py --masters` rebuilds the masters in `tools/kevbox/art/` from the KevBox logo first.
It is only needed when the logo changes. Never edit a generated file by hand.

The brand check (AC-3) then runs on a release build:

```sh
./gradlew assembleStandardRelease
bash tools/kevbox/check-brand.sh app/build/outputs/apk/standard/release/app-standard-release-unsigned.apk
```

### Allowed "OwnTV" text

`tools/kevbox/brand-allowlist.txt` names the only strings that may still say "OwnTV" in the APK.
They credit the upstream project, as its GPLv3 licence asks:

- `settings_about_license`: "© 2026 OwnTV · GPLv3 License".

Add a name only for licence or attribution text, with its reason.

## Releasing

A KevBox Live version is `A.B.C.K` (AC-5). `A.B.C` is the upstream release the branch is built on,
and `K` is KevBox's own number, 1 to 99, which starts again at 1 on each new upstream base. The
first release is `5.1.0.1` on upstream `5.1.0`; the next on that base is `5.1.0.2`; the first
after merging upstream `v5.2.0` is `5.2.0.1`. The version code is
`A*1000000 + B*10000 + C*100 + K` (`tools/kevbox/version.sh code <version>` prints it).

`./release.sh` is Kevin's to run, after `kevbox-live` is pushed:

```sh
./release.sh 5.1.0.1 "First KevBox Live release"
```

Before it builds anything, it refuses (exit 1, nothing created) when the tree has uncommitted or
untracked files, the version breaks the rule above, `A.B.C` is not the newest upstream `vX.Y.Z`
tag reachable from `HEAD`, `HEAD` is not on `origin/kevbox-live`, the version code is not above
the latest release on `kevinchiha/OwnTV`, or a secret file is missing or incomplete. Then it
builds the arm and x86_64 release APKs, checks that both carry the KevBox TV key's signature
(SHA-256 `9A:E0:71:8C:CC:F9:F6:83:90:93:49:31:D3:FE:0E:52:F4:57:EF:B5:81:D5:AC:C1:14:16:F1:51:2D:BB:1D:6F`),
and creates release `v<version>` with the assets `KevBoxLive.apk` and `KevBoxLive-x86_64.apk`.
Those names never change: the short link below and Core's updater both rely on them.

`bash tools/kevbox/test-release-checks.sh` tests the refusals against a throwaway repo.

The fork's GitHub workflows publish nothing. `release-notes.yml` and `automerge-core-bump.yml` run
only on `ahXN00/OwnTV`, and so does the build job of `android.yml`, which on upstream turns a
`v*` tag into an OwnTV-named release (AC-9).

## The tv.kevbox.dev/live short link

`tools/kevbox/nginx/tv-kevbox-dev-live.conf` makes `https://tv.kevbox.dev/live` answer 302 to
`https://github.com/kevinchiha/OwnTV/releases/latest/download/KevBoxLive.apk`. Installing it is a
gated step for Kevin on persovps:

1. Copy the snippet to persovps and add an `include` of it inside the `tv.kevbox.dev`
   `server { listen 443 ... }` block.
2. `sudo nginx -t`, then `sudo systemctl reload nginx`.
3. Check from anywhere: `curl -sI https://tv.kevbox.dev/live` shows `302` and the `location` above.

## Why Core is not forked

The app pulls `tv.own.owntv:core` and `tv.own.owntv:player-core` as built libraries from upstream's
Maven repository, at the version `owntvCore` pins in `gradle/libs.versions.toml` (AC-41). A Core
fork would mean building and hosting our own Core, and merging Core as well as the app at every
upstream release. Nothing KevBox Live does needs it: the app sets
`CoreBuildInfo.releaseRepo` so the updater reads this fork's releases, overrides Core's strings and
art by resource name, and calls Core's public classes (`HttpClient.redactUrl`, the source and
playback APIs) from fork-only code. The proof is the last row of the table: the pin file has no
KevBox change.
