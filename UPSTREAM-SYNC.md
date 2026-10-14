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

5. `apksigner` and `aapt2` come from the newest `$ANDROID_HOME/build-tools/*` (default
   `/home/kevin/Android/Sdk`).

## Where the fork starts

`kevbox-live` is not built on an upstream release tag. It branched from upstream `main` at
`fd74926d` ("Merge pull request #256 from ahXN00/bump/core-1.0.67"), which `git describe` calls
`v5.1.0-25-gfd74926d`: 25 commits past `v5.1.0`, with Core pinned at `1.0.67` where `v5.1.0` pins
`1.0.64`. The version rule below counts only release tags, so the first KevBox Live release is
still labelled `5.1.0.1`, and it carries those 25 upstream commits and the newer Core.

No upstream tag has been merged yet. The first merge will be the first upstream release after
`fd74926d` (for example `v5.2.0`), and every step below works out the starting point with
`git merge-base` rather than a tag name, so it holds for that first merge and every later one.

## Merge steps for each upstream release

Merge upstream's release tag, never `upstream/main`. Upstream's `main` moves ahead of its last
release every time Core publishes and the pin bump merges itself. Only merge a tag that comes
after the fork's starting point: `git merge-base --is-ancestor fd74926d vX.Y.Z` must exit 0. A
tag that fails it (a hotfix such as `v5.1.1` cut from `v5.1.0`) misses some of the 25 commits the
fork already has; read its changes before deciding to take it.

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
| `app/build.gradle.kts` | `grep -n 'tv.kevbox.live' app/build.gradle.kts && grep -n 'id("io.sentry.android.gradle") version "6.23.0"' app/build.gradle.kts && grep -n 'io.sentry:sentry-android-ndk:8.60.0' app/build.gradle.kts && grep -n 'kevbox.sentryDsnDebug' app/build.gradle.kts && grep -n 'ignoredBuildTypes.set(setOf("debug"))' app/build.gradle.kts` (the KevBox package name; Sentry plugin, SDK, `SENTRY_DSN` and the `sentry { }` block, 0.3) |
| `app/src/main/AndroidManifest.xml` | `grep -n 'io.sentry.auto-init' app/src/main/AndroidManifest.xml && grep -n 'SentryTestReceiver' app/src/main/AndroidManifest.xml && grep -n 'android:permission="android.permission.DUMP"' app/src/main/AndroidManifest.xml` (Sentry starts by hand after CrashRecorder; the shell-only Sentry test receiver, 0.3) |
| `app/src/main/java/tv/own/owntv/MainActivity.kt` | `grep -n 'kevbox.onForeground()' app/src/main/java/tv/own/owntv/MainActivity.kt && grep -n 'kevbox.ui.QuietAccessibility.install(this)' app/src/main/java/tv/own/owntv/MainActivity.kt && grep -c 'KevBoxAutoSetup(' app/src/main/java/tv/own/owntv/MainActivity.kt && grep -n 'LaunchedEffect(profile) { gateSession.authenticateProfile(profile) }' app/src/main/java/tv/own/owntv/MainActivity.kt` (the count must be 2) |
| `app/src/main/java/tv/own/owntv/OwnTVApp.kt` | `grep -n 'releaseRepo = "kevinchiha/OwnTV"' app/src/main/java/tv/own/owntv/OwnTVApp.kt && grep -n 'epg.GuideFeeds>().start()' app/src/main/java/tv/own/owntv/OwnTVApp.kt && grep -n 'release.UpdateProfileAttacher>().start()' app/src/main/java/tv/own/owntv/OwnTVApp.kt && grep -n 'network.DnsDefault>().applyOnce()' app/src/main/java/tv/own/owntv/OwnTVApp.kt && grep -n 'refresh.PlaylistRefreshSetting>().start()' app/src/main/java/tv/own/owntv/OwnTVApp.kt && grep -n 'KevBoxSentry.start(this, BuildConfig.SENTRY_DSN)' app/src/main/java/tv/own/owntv/OwnTVApp.kt && grep -n 'usage.LiveCheckin>().start()' app/src/main/java/tv/own/owntv/OwnTVApp.kt && grep -n 'errors.ErrorWatchers>().start()' app/src/main/java/tv/own/owntv/OwnTVApp.kt && grep -n 'KevBoxSentry.bind(GlobalContext.get().get(), GlobalContext.get().get(), appScope)' app/src/main/java/tv/own/owntv/OwnTVApp.kt` (0.3: Sentry starts on the line after `CrashRecorder.install(this)`; the usage check-in; the error watchers, which start the reporter; Sentry's user and breadcrumbs, bound on the line before the watchers start so the reporter's first entries become breadcrumbs too) |
| `app/src/main/java/tv/own/owntv/features/shell/ShellViewModel.kt` | `grep -n 'LiveOnly.browseSections' app/src/main/java/tv/own/owntv/features/shell/ShellViewModel.kt && grep -n 'flowOf(tv.own.owntv.kevbox.settings.LiveOnlySettings.ACCENT_HEX)' app/src/main/java/tv/own/owntv/features/shell/ShellViewModel.kt && grep -n 'flowOf(tv.own.owntv.kevbox.settings.LiveOnlySettings.FOCUS_HIGHLIGHT_HEX)' app/src/main/java/tv/own/owntv/features/shell/ShellViewModel.kt` |
| `app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt` | `grep -n 'onOpenMenu = focusRail' app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt && grep -n 'KevBox: Live TV is always the grid' app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt && grep -n 'kevbox.live.LiveVideoLayer(' app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt && grep -n 'liveVideoSite(liveVideoStage' app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt && grep -n 'if (playerChromeReady) PlayerHud(' app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt && grep -n 'kevbox.live.isBackToGrid(' app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt && grep -n 'exitPlayerWith(false)' app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt && grep -n 'liveVm.keepInBox(playingChannel)' app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt && grep -n 'kevbox.ui.KeptHidden' app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt && grep -n 'if (playerMode != PlayerMode.NONE) previewChannelState.value else null' app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt && grep -n 'RemoteShortcutAction.OPEN_PROFILE_SWITCHER -> Unit' app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt && grep -n 'liveGuideExpanded && (!railHasFocus' app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt && grep -n 'menuInUse = railFocused' app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt && grep -n 'selectedSection == MainSection.LIVE_TV -> 0.dp' app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt && grep -n 'val multiviewEnabled = false' app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt && grep -n 'val recordWatchingEnabled = false' app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt && grep -n 'takeIf { tv.own.owntv.kevbox.live.LiveShortcuts.continueChannel(it) != null }' app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt && grep -n 'R.string.kevbox_settings_startup_channel_unavailable' app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt && grep -n 'when (tv.own.owntv.kevbox.live.LiveShortcuts.resolve(action))' app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt && grep -n 'if (false && isFull && playerChromeReady' app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt && grep -n 'koinInject<tv.own.owntv.kevbox.errors.PlaybackScene>()' app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt && grep -n 'kevboxScene.update(channel, category, inBox' app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt` (the last two: the error reporter's scene hook after `liveVideoStage`, 0.3) |
| `app/src/main/java/tv/own/owntv/ui/stage/StageDraw.kt` | `grep -c 'this.bakedBackground(listOf(' app/src/main/java/tv/own/owntv/ui/stage/StageDraw.kt` (the count must be 2) |
| `app/src/main/java/tv/own/owntv/features/shell/components/StageRail.kt` | `grep -n 'KevBox: no profile at the bottom of the menu' app/src/main/java/tv/own/owntv/features/shell/components/StageRail.kt && grep -n 'state == RailState.HIDDEN && edgeGlow' app/src/main/java/tv/own/owntv/features/shell/components/StageRail.kt` |
| `app/src/main/java/tv/own/owntv/features/epg/GuideStage.kt` | `grep -n 'liveVideoSite(videoStage' app/src/main/java/tv/own/owntv/features/epg/GuideStage.kt && grep -n 'private const val GUIDE_MENU_RECORD_GROUP = false' app/src/main/java/tv/own/owntv/features/epg/GuideStage.kt && grep -n 'if (GUIDE_MENU_RECORD_GROUP)' app/src/main/java/tv/own/owntv/features/epg/GuideStage.kt` |
| `app/src/main/java/tv/own/owntv/player/MpvVideoSurface.kt` | `grep -n 'if (subOn && subtitles)' app/src/main/java/tv/own/owntv/player/MpvVideoSurface.kt && grep -n 'fun ExoSubtitleLayer' app/src/main/java/tv/own/owntv/player/MpvVideoSurface.kt` |
| `app/src/main/java/tv/own/owntv/features/epg/EpgScreen.kt` | `grep -n 'if (!liveMode) Row(' app/src/main/java/tv/own/owntv/features/epg/EpgScreen.kt && grep -n 'rememberPlaylistFirstSync' app/src/main/java/tv/own/owntv/features/epg/EpgScreen.kt && grep -n 'liveVm.reveal.collectAsStateWithLifecycle' app/src/main/java/tv/own/owntv/features/epg/EpgScreen.kt && grep -n 'class SidebarExit(val row: Int, val oldState' app/src/main/java/tv/own/owntv/features/epg/EpgScreen.kt && grep -n 'if (liveMode) { enterColumn(); return@invoke true }' app/src/main/java/tv/own/owntv/features/epg/EpgScreen.kt && grep -n 'GuideSidebar.expanded(expanded, focusAt, entryUnderWay)' app/src/main/java/tv/own/owntv/features/epg/EpgScreen.kt && grep -n 'to < 0 -> liveMode' app/src/main/java/tv/own/owntv/features/epg/EpgScreen.kt && grep -n 'focusFollowsSelection = false' app/src/main/java/tv/own/owntv/features/epg/EpgScreen.kt && grep -n 'GuideSidebar.rightOrOk(key, liveSelected' app/src/main/java/tv/own/owntv/features/epg/EpgScreen.kt && grep -n 'videoStage?.boxSlide = ' app/src/main/java/tv/own/owntv/features/epg/EpgScreen.kt && grep -n 'okOnRow = liveMode' app/src/main/java/tv/own/owntv/features/epg/EpgScreen.kt && grep -n 'LocalLiveVideoStage.current' app/src/main/java/tv/own/owntv/features/epg/EpgScreen.kt && grep -n 'val previewChannelState = liveVm.previewChannel' app/src/main/java/tv/own/owntv/features/epg/EpgScreen.kt && grep -n 'val isSelectedRow by remember(index)' app/src/main/java/tv/own/owntv/features/epg/EpgScreen.kt && grep -n 'import tv.own.owntv.ui.components.chNavPaging' app/src/main/java/tv/own/owntv/features/epg/EpgScreen.kt && grep -n 'enabled = gridChNavEnabled, upSkip = gridChNavUpSkip, downSkip = gridChNavDownSkip' app/src/main/java/tv/own/owntv/features/epg/EpgScreen.kt && grep -n 'selectedIndex = GuideSidebar.markedRow(openIndex, columnCursor, columnHasFocus)' app/src/main/java/tv/own/owntv/features/epg/EpgScreen.kt && grep -n 'headingAt\[index\]?.let { GuideGroupHeading(it, first = index == 0) }' app/src/main/java/tv/own/owntv/features/epg/EpgScreen.kt && grep -n 'matchText = matchText, // KevBox: matching shows get an accent outline' app/src/main/java/tv/own/owntv/features/epg/EpgScreen.kt && grep -n 'private fun GuideGroupHeading(' app/src/main/java/tv/own/owntv/features/epg/EpgScreen.kt` (the guide search's group headings sit inside their first row's item, so rows still count channels only; matching shows are outlined) |
| `app/src/main/java/tv/own/owntv/features/live/LiveViewModel.kt` | `grep -n 'fun onFullscreenExited(keepStream: Boolean' app/src/main/java/tv/own/owntv/features/live/LiveViewModel.kt && grep -n 'fun keepInBox(channel: ChannelEntity)' app/src/main/java/tv/own/owntv/features/live/LiveViewModel.kt && grep -n 'MutableStateFlow(tv.own.owntv.kevbox.settings.LiveOnlySettings.PREVIEW_SOUND)' app/src/main/java/tv/own/owntv/features/live/LiveViewModel.kt && grep -n 'LiveOnlySettings.REMEMBER_CATEGORY_LIVE) return@launch' app/src/main/java/tv/own/owntv/features/live/LiveViewModel.kt && grep -n 'LiveOnlySettings.REMEMBER_CHANNEL_LIVE) return@launch' app/src/main/java/tv/own/owntv/features/live/LiveViewModel.kt && grep -n 'private fun kevBoxLiveHost(' app/src/main/java/tv/own/owntv/features/live/LiveViewModel.kt && grep -n 'vodEngineOverride = tv.own.owntv.kevbox.settings.LiveOnlySettings.CATCHUP_ENGINE' app/src/main/java/tv/own/owntv/features/live/LiveViewModel.kt && grep -n 'kevbox.errors.LadderEvents.record(reason)' app/src/main/java/tv/own/owntv/features/live/LiveViewModel.kt` |
| `app/src/main/java/tv/own/owntv/features/epg/EpgViewModel.kt` | `grep -n 'KevBox: every channel shows' app/src/main/java/tv/own/owntv/features/epg/EpgViewModel.kt && grep -n 'KevBox: reload when a playlist finishes a sync' app/src/main/java/tv/own/owntv/features/epg/EpgViewModel.kt && grep -n 'channelDao.channelsAfterCategory(filter.id' app/src/main/java/tv/own/owntv/features/epg/EpgViewModel.kt && grep -n 'MAX_CHANNELS = 40_000' app/src/main/java/tv/own/owntv/features/epg/EpgViewModel.kt && grep -n 'val headings: List<tv.own.owntv.kevbox.live.GuideHeading>' app/src/main/java/tv/own/owntv/features/epg/EpgViewModel.kt && grep -n 'private val showSearch = tv.own.owntv.kevbox.live.ShowSearch(' app/src/main/java/tv/own/owntv/features/epg/EpgViewModel.kt && grep -n 'fun inCategory(ch: ChannelEntity): Boolean' app/src/main/java/tv/own/owntv/features/epg/EpgViewModel.kt && grep -n 'private suspend fun guideSearchGroups(' app/src/main/java/tv/own/owntv/features/epg/EpgViewModel.kt` (the guide's search also finds channels by a show they carry, grouped Channels / On now / Next 24 hours) |
| `app/src/main/java/tv/own/owntv/features/live/LiveStage.kt` | `grep -n 'LiveOnly.categoryName' app/src/main/java/tv/own/owntv/features/live/LiveStage.kt && grep -n 'stepper.stepTo(tv.own.owntv.kevbox.live.CategorySteps.itemIndex' app/src/main/java/tv/own/owntv/features/live/LiveStage.kt && grep -n 'val entries = remember(railItems, railCounts, recentLabel' app/src/main/java/tv/own/owntv/features/live/LiveStage.kt && grep -n 'if (!focusFollowsSelection' app/src/main/java/tv/own/owntv/features/live/LiveStage.kt && grep -n 'liveVideoSite(videoStage, tv.own.owntv.kevbox.live.VideoSite.BOX' app/src/main/java/tv/own/owntv/features/live/LiveStage.kt` |
| `app/src/main/java/tv/own/owntv/features/shell/components/CategoryBrowserOverlay.kt` | `grep -n 'jumpToBlocks = true, // KevBox' app/src/main/java/tv/own/owntv/features/shell/components/CategoryBrowserOverlay.kt` |
| `app/src/main/java/tv/own/owntv/ui/components/StageGlyphs.kt` | `grep -n 'OwnTVIcon.GEAR to "M12.22 2h' app/src/main/java/tv/own/owntv/ui/components/StageGlyphs.kt` (KevBox's cog; upstream's GEAR drawing read as a sun) |
| `app/src/main/java/tv/own/owntv/features/search/SearchScreen.kt` | `grep -n 'LiveOnly.categoryName' app/src/main/java/tv/own/owntv/features/search/SearchScreen.kt && grep -n 'copy(movies = emptyList(), series = emptyList())' app/src/main/java/tv/own/owntv/features/search/SearchScreen.kt && grep -n 'filter { it == SearchIntent.CHANNELS }' app/src/main/java/tv/own/owntv/features/search/SearchScreen.kt && grep -n 'kevbox_search_field_hint' app/src/main/java/tv/own/owntv/features/search/SearchScreen.kt && grep -n 'onLongClick = { openInGuide(e) }' app/src/main/java/tv/own/owntv/features/search/SearchScreen.kt && grep -n 'tv.own.owntv.kevbox.live.pickShowTab(chosenShowTab' app/src/main/java/tv/own/owntv/features/search/SearchScreen.kt && grep -n 'R.string.kevbox_search_tab_guide' app/src/main/java/tv/own/owntv/features/search/SearchScreen.kt && grep -n 'results.copy(programmes = emptyList())' app/src/main/java/tv/own/owntv/features/search/SearchScreen.kt` (the Channels and TV guide tabs, which list KevBox's own shows instead of core's "On TV") |
| `app/src/main/java/tv/own/owntv/features/search/SearchViewModel.kt` | `grep -n 'val shows: StateFlow<tv.own.owntv.kevbox.live.ShowMatches>' app/src/main/java/tv/own/owntv/features/search/SearchViewModel.kt && grep -n 'private val showSearch: tv.own.owntv.kevbox.live.ShowSearch' app/src/main/java/tv/own/owntv/features/search/SearchViewModel.kt && grep -n '_showTab.value = null' app/src/main/java/tv/own/owntv/features/search/SearchViewModel.kt` (the TV guide tab's shows, and the tab a new query forgets) |
| `app/src/main/java/tv/own/owntv/features/epg/GuideCore.kt` | `grep -n 'const val ChannelColShare = 424f / 1920f' app/src/main/java/tv/own/owntv/features/epg/GuideCore.kt && grep -n 'matchText: String? = null' app/src/main/java/tv/own/owntv/features/epg/GuideCore.kt && grep -n 'if (i in matching)' app/src/main/java/tv/own/owntv/features/epg/GuideCore.kt` (upstream's share is `296f / 1920f`; `ProgrammeStripCanvas` outlines programmes matching the guide search) |
| `app/src/main/java/tv/own/owntv/features/settings/SettingsStage.kt` | `grep -n 'passwordShownAtStart' app/src/main/java/tv/own/owntv/features/settings/SettingsStage.kt` |
| `app/src/main/java/tv/own/owntv/player/PlayerHud.kt` | `grep -n 'kevbox.onChannelFailed()' app/src/main/java/tv/own/owntv/player/PlayerHud.kt && grep -n 'KevBox: a channel opens on the picture alone' app/src/main/java/tv/own/owntv/player/PlayerHud.kt && grep -n 'KevBox: CH+ / CH- change the channel and show nothing' app/src/main/java/tv/own/owntv/player/PlayerHud.kt` |
| `app/src/main/java/tv/own/owntv/features/shell/components/SettingsScreen.kt` | `grep -n 'KevBoxChoosePlaylistSettingsScreen' app/src/main/java/tv/own/owntv/features/shell/components/SettingsScreen.kt && grep -n 'KevBox: no Proxy row' app/src/main/java/tv/own/owntv/features/shell/components/SettingsScreen.kt && grep -n 'KevBox: no Profiles entry' app/src/main/java/tv/own/owntv/features/shell/components/SettingsScreen.kt && grep -n '.let(::kevboxShownRootItems)' app/src/main/java/tv/own/owntv/features/shell/components/SettingsScreen.kt && grep -n '.let(::kevboxMoveRows)' app/src/main/java/tv/own/owntv/features/shell/components/SettingsScreen.kt && grep -n 'LiveOnlySettings.shows(e.key)' app/src/main/java/tv/own/owntv/features/shell/components/SettingsScreen.kt && grep -n 'if (onGroupCounts != null) { ReportGroupCounts(' app/src/main/java/tv/own/owntv/features/shell/components/SettingsScreen.kt && grep -n 'kevboxAndroidTvHomeRows(settingsVm)' app/src/main/java/tv/own/owntv/features/shell/components/SettingsScreen.kt && grep -n 'private const val KEVBOX_RECORDING = false' app/src/main/java/tv/own/owntv/features/shell/components/SettingsScreen.kt && grep -n 'internal fun rememberSettingsGroupCounts()' app/src/main/java/tv/own/owntv/features/shell/components/SettingsScreen.kt && grep -n '*kevboxPlaylistRefreshRows(stageSettings' app/src/main/java/tv/own/owntv/features/shell/components/SettingsScreen.kt && grep -n 'StageSettingsDialog.PLAYLIST_REFRESH -> KevBoxPlaylistRefreshDialog(close)' app/src/main/java/tv/own/owntv/features/shell/components/SettingsScreen.kt && grep -n 'kevboxRowEntry(rootItems, KEVBOX_PLAYLIST_UPDATE_NOW' app/src/main/java/tv/own/owntv/features/shell/components/SettingsScreen.kt` |
| `app/src/main/java/tv/own/owntv/features/shell/components/ContentPane.kt` | `grep -n 'KevBox: no "add source" action' app/src/main/java/tv/own/owntv/features/shell/components/ContentPane.kt` |
| `app/src/main/java/tv/own/owntv/features/more/MoreScreen.kt` | `grep -n 'MoreItem.ACCOUNT' app/src/main/java/tv/own/owntv/features/more/MoreScreen.kt && grep -n 'KevBox: no profile row' app/src/main/java/tv/own/owntv/features/more/MoreScreen.kt && grep -n 'history.live.toString()' app/src/main/java/tv/own/owntv/features/more/MoreScreen.kt && grep -n 'onOpenSettings(SettingsGroup.APP.ordinal, false)' app/src/main/java/tv/own/owntv/features/more/MoreScreen.kt && grep -n 'MoreItem.RESTART -> tv.own.owntv.kevbox.ui.KevBoxRestartMorePage' app/src/main/java/tv/own/owntv/features/more/MoreScreen.kt` |
| `app/src/main/java/tv/own/owntv/features/more/MorePages.kt` | `grep -n 'KevBox: the GitHub contributions line' app/src/main/java/tv/own/owntv/features/more/MorePages.kt && grep -n 'filterIndexed { i, _ -> i != 1 && i != 5 }' app/src/main/java/tv/own/owntv/features/more/MorePages.kt && grep -n 'R.string.kevbox_settings_card_watching' app/src/main/java/tv/own/owntv/features/more/MorePages.kt && grep -n 'rememberSettingsGroupCounts()' app/src/main/java/tv/own/owntv/features/more/MorePages.kt && grep -n 'private const val KEVBOX_VOD_SHOWN = false' app/src/main/java/tv/own/owntv/features/more/MorePages.kt && grep -n 'R.string.kevbox_more_fav_empty_body' app/src/main/java/tv/own/owntv/features/more/MorePages.kt && grep -n 'ClearLiveHistoryDialog(' app/src/main/java/tv/own/owntv/features/more/MorePages.kt` |
| `app/src/main/java/tv/own/owntv/features/setup/SetupWizard.kt` | `grep -n 'KEVBOX_OPEN_STEPS' app/src/main/java/tv/own/owntv/features/setup/SetupWizard.kt` |
| `app/src/main/java/tv/own/owntv/features/settings/EpgSourcesScreen.kt` | `grep -n 'HttpClient.redactUrl' app/src/main/java/tv/own/owntv/features/settings/EpgSourcesScreen.kt && grep -n 'private const val SHOW_FILL_FROM_PLAYLIST = false' app/src/main/java/tv/own/owntv/features/settings/EpgSourcesScreen.kt && grep -n 'canDelete = automaticIds?.contains(source.id) == false' app/src/main/java/tv/own/owntv/features/settings/EpgSourcesScreen.kt && grep -n 'R.string.kevbox_epg_automatic' app/src/main/java/tv/own/owntv/features/settings/EpgSourcesScreen.kt` |
| `app/src/main/java/tv/own/owntv/features/settings/VideoPlayerSettingsScreen.kt` | `grep -n 'LiveOnlySettings.shows(quickKey)) return' app/src/main/java/tv/own/owntv/features/settings/VideoPlayerSettingsScreen.kt && grep -n 'private fun kevboxRowDrawn(' app/src/main/java/tv/own/owntv/features/settings/VideoPlayerSettingsScreen.kt && grep -n 'R.string.kevbox_settings_vp_cat_controls_catchup' app/src/main/java/tv/own/owntv/features/settings/VideoPlayerSettingsScreen.kt && grep -n 'R.string.kevbox_settings_catchup_buffer_description' app/src/main/java/tv/own/owntv/features/settings/VideoPlayerSettingsScreen.kt && grep -n 'externalPlayerChip(live, movies = false, series = false)' app/src/main/java/tv/own/owntv/features/settings/VideoPlayerSettingsScreen.kt && grep -n 'ExternalPlayerSection.LIVE_TV } // KevBox' app/src/main/java/tv/own/owntv/features/settings/VideoPlayerSettingsScreen.kt` |
| `app/src/main/java/tv/own/owntv/features/settings/SettingsHelpTable.kt` | `grep -n '"vp_autoplay" to R.string.kevbox_settings_line_catchup_next' app/src/main/java/tv/own/owntv/features/settings/SettingsHelpTable.kt && grep -n '"app_startup" to R.string.kevbox_settings_help_startup' app/src/main/java/tv/own/owntv/features/settings/SettingsHelpTable.kt && grep -n '"vp_live_preview" to R.string.kevbox_settings_help_live_preview' app/src/main/java/tv/own/owntv/features/settings/SettingsHelpTable.kt` |
| `app/src/main/java/tv/own/owntv/features/settings/SettingsViewModel.kt` | `grep -n 'livePreviewPanelActive: StateFlow<Boolean> = MutableStateFlow(true)' app/src/main/java/tv/own/owntv/features/settings/SettingsViewModel.kt && grep -n 'if (enabled) clearLivePanelWidthOverride()' app/src/main/java/tv/own/owntv/features/settings/SettingsViewModel.kt && grep -n 'LiveOnlySettings::quickPins' app/src/main/java/tv/own/owntv/features/settings/SettingsViewModel.kt` |
| `app/src/main/java/tv/own/owntv/features/shell/components/SettingsGroup.kt` | `grep -n 'WATCHING(R.string.kevbox_settings_group_watching, VideoGroup.WATCHING)' app/src/main/java/tv/own/owntv/features/shell/components/SettingsGroup.kt` |
| `app/src/main/java/tv/own/owntv/features/settings/NavigationSettingsPopup.kt` | `grep -n 'if (LiveOnly.browseSections.size > 1)' app/src/main/java/tv/own/owntv/features/settings/NavigationSettingsPopup.kt` |
| `app/src/main/java/tv/own/owntv/features/customize/CustomizeScreen.kt` | `grep -n 'val liveOnly = LiveOnly.browseSections.size == 1' app/src/main/java/tv/own/owntv/features/customize/CustomizeScreen.kt && grep -n 'parents = listOf(stringResource(R.string.settings_live_tv))' app/src/main/java/tv/own/owntv/features/customize/CustomizeScreen.kt && grep -n 'if (liveOnly) Modifier.focusRequester(firstFocus)' app/src/main/java/tv/own/owntv/features/customize/CustomizeScreen.kt` |
| `app/src/main/java/tv/own/owntv/features/settings/ChNavSettingsScreen.kt` | `grep -n 'LiveShortcuts.offered(RemoteShortcutAction.entries)' app/src/main/java/tv/own/owntv/features/settings/ChNavSettingsScreen.kt && grep -n 'RemoteShortcutBinding(keyCode, press, RemoteShortcutAction.OPEN_LIVE_TV)' app/src/main/java/tv/own/owntv/features/settings/ChNavSettingsScreen.kt && grep -n 'selected = LiveShortcuts.resolve(binding.action).name' app/src/main/java/tv/own/owntv/features/settings/ChNavSettingsScreen.kt` |
| `app/src/main/java/tv/own/owntv/features/settings/EpgSourcesViewModel.kt` | `grep -n 'val automaticIds: StateFlow<Set<Long>?>' app/src/main/java/tv/own/owntv/features/settings/EpgSourcesViewModel.kt && grep -n 'private fun activePlaylistEpgs()' app/src/main/java/tv/own/owntv/features/settings/EpgSourcesViewModel.kt` |
| `app/src/main/java/tv/own/owntv/features/settings/LanguageSettingsScreen.kt` | `grep -n 'KevBox: no Help translate panel' app/src/main/java/tv/own/owntv/features/settings/LanguageSettingsScreen.kt && test "$(grep -c 'extra = translate)' app/src/main/java/tv/own/owntv/features/settings/LanguageSettingsScreen.kt)" = 0 && echo 'no row passes extra = translate'` (upstream's rows end `extra = translate)`; the KevBox comments do not, so the last line prints only while no row draws the panel) |
| `app/src/main/java/tv/own/owntv/features/settings/SettingsLivePreview.kt` | `grep -n 'tv.own.owntv.kevbox.ui.KevBoxMiniGuidePreview()' app/src/main/java/tv/own/owntv/features/settings/SettingsLivePreview.kt` |
| `app/src/main/java/tv/own/owntv/features/shell/components/GlassEffectPage.kt` | `grep -n 'val miniGuide = tv.own.owntv.kevbox.ui.rememberMiniGuideData()' app/src/main/java/tv/own/owntv/features/shell/components/GlassEffectPage.kt && grep -n 'KevBoxMiniGuidePreview(data = miniGuide)' app/src/main/java/tv/own/owntv/features/shell/components/GlassEffectPage.kt` |
| `app/src/test/java/tv/own/owntv/features/shell/SettingsSearchCoverageTest.kt` | `grep -n '"ManageSourcesScreen.kt"' app/src/test/java/tv/own/owntv/features/shell/SettingsSearchCoverageTest.kt && grep -n '"NetworkSettingsScreen.kt"' app/src/test/java/tv/own/owntv/features/shell/SettingsSearchCoverageTest.kt && grep -n '"LOCAL_SYNC", "NETWORK"' app/src/test/java/tv/own/owntv/features/shell/SettingsSearchCoverageTest.kt && grep -n 'in LiveOnlySettings.HIDDEN_KEYS' app/src/test/java/tv/own/owntv/features/shell/SettingsSearchCoverageTest.kt && grep -n 'private fun rootRowKey(' app/src/test/java/tv/own/owntv/features/shell/SettingsSearchCoverageTest.kt` |
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
the upstream commit `kevbox-live` holds now and the new tag. `old` is the newest upstream commit
the branch shares with that tag: `fd74926d` before the first merge, the last merged tag after it.

```sh
old="$(git merge-base kevbox-live vX.Y.Z)"

# New calls into the locked screens, companion modes or deep links
git diff "$old" vX.Y.Z -- app/src/main/java app/src/main/AndroidManifest.xml \
  | grep -nE '^\+.*\b(ManageSourcesScreen|AddSourceScreen|AddSourceChooserScreen|RemoteSetupScreen|LocalSyncScreen|SetupLocalSyncScreen|BackupScreen|RemoteBackupRestoreScreen|CompanionController|CompanionMode|LauncherDeepLink|android:scheme|android:host)\b'

# New Settings tabs, More items and setup wizard steps
git diff "$old" vX.Y.Z -- \
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

Two known gaps, checked at every merge:

- **Home-screen cards can open a chooser.** Core puts KevBox Live's Android TV home-screen cards
  (channels, movies, episodes) on the launcher as implicit `owntv://` links, built in Core's
  `LauncherDeepLink`. When stock OwnTV is installed on the same TV, both apps answer `owntv://`,
  so picking a card can show a "which app?" chooser. Core is not forked, so this is accepted. If a
  new Core starts sending these links to a named app, check that KevBox Live still gets them.
- **The Pixel app icon must stay unreachable.** With the Pixel icon applied, the in-app mark is
  the OwnTV dot-matrix TV drawn in code (`PixelMark` in `BrandLockup.kt`), which no resource
  override can rebrand. Today no member can apply it: every caller of `setAppIcon` (the setup
  wizard's icon step, the Settings icon picker) and backup restore, which could bring the setting
  back, are hidden. Keep it so. Check with
  `grep -rn 'setAppIcon' app/src/main/java --include='*.kt'`, and treat any caller a member can
  reach as a new route to cut.

### Live TV only

Since 2026-10-14 KevBox Live is a live TV app with no onboarding:

- The left menu shows Search, Live TV and More. `ShellViewModel.visibleSections` returns
  `LiveOnly.browseSections` instead of Core's `NavVisibility`, and `selectSection` turns any other
  browse section (Home, Movies, Series, Downloads, TV Guide) into Live TV. The playlist is added
  with `syncMovies = false, syncSeries = false`, so no movie or series catalogue downloads. The
  movie and series screens stay in the tree, unreachable, like the locked source screens. A new
  upstream route into them that does not go through `selectSection` (a new `MainSection`, a deep
  link, a More page) is a route to cut.
- Live TV is always the guide grid (`OwnTVShell` ignores the stored List/Guide choice). The grid
  has no control line. Live TV's category list is a column (`LiveCategories`' flat column, with its
  search), always composed right of the main menu: the content area starts at the screen edge on
  Live TV and `EpgScreen` gets the menu's room (`menuRoom`). The guide keeps its full-screen layout
  and slides on one layer translation: right by the menu's room plus the 420 mpx column while they
  show, back to 0 once the user moves into the grid, in 320 ms with the menu's own slide
  (`kevbox/live/GuideSidebar.kt` holds the rules). ▶ from the main menu moves to the column, never
  the grid, with nothing moving (the screen's rail entry hook); the column asks for focus over a
  few frames, since from the menu the shell content's `focusRestorer` turns the first request into
  the grid row last focused. The cursor resting 300 ms on a category shows it in the guide behind
  (`LiveCategories`' `focusFollowsSelection = false` keeps a load from pulling the cursor back). ▶
  or OK on a category selects it if the guide does not show it yet and moves to the grid through
  `SidebarExit`, which waits for a new guide state after a category change (upstream's direct
  `focusGrid()` let focus fall to the main menu); an empty category keeps the cursor in the column.
  The layout follows focus (`GuideSidebar.expanded`): a channel row with focus expands the guide,
  the column or the user on the main menu (the shell's `railFocused`, not the visits focus makes
  there by itself) collapses it, anything else leaves it. A move the screen has started outranks
  where focus passes on its way: ▶ from the menu and ◀ / Back on a row hold it collapsed until the
  column has focus, and every move into the grid (▶ / OK, Back from full screen, a reveal) goes
  through `SidebarExit` and holds it expanded until the row has focus. Back from full screen parks
  focus on the menu (`focusRail()` in `exitPlayerWith`); the guide spends `restoreFocus` only once
  its row has focus, and the shell keeps the menu hidden meanwhile. The shell hides the menu
  (`railState` HIDDEN, no edge glow) while the guide is expanded and the menu does not hold focus.
  ▲ on the first row stays there. ◀ or Back on a channel row goes back to the column, ◀ or Back in
  the column moves to the main menu. The TV Guide section keeps upstream's glass category sheet. OK on a
  channel that is not in the top video box previews it there (`boxChannel`, held on the video
  stage below); only OK on the channel already in the box opens it full screen, on its row
  (`GuideRow`'s `okOnRow`) and inside its programmes alike, and ▶ steps into the programmes. If upstream
  reworks `EpgScreen`'s control line, its layout, `LiveCategories` or `GuideRow`'s ◀ handling, check all of these keys
  again on a TV.
- The playlist's own guide feed is added and downloaded by `kevbox/epg/GuideFeeds.kt`, which
  stands in for the onboarding's "sync the guide?" question. If Core starts adding the Xtream
  feed by itself (its `ImportFinalizer` only does so for Stalker portals today), drop GuideFeeds.
- Provider category names show verbatim (`LiveOnly.categoryName`), channel names still go through
  `ProviderTags`.
- ▲ ▼ in every `LiveCategories` list (Live TV's category column among them) step by row number through
  `VodStepper`, as the channel grid does (`kevbox/live/CategorySteps.kt` maps a row to its list item
  past the search field and the "GROUPS" heading). Upstream leaves them to focus search, which a held
  key outran on a long provider list: the list stopped until its scroll caught up. If upstream adds
  its own stepping to `LiveCategories`, drop this.
- Seamless Live video (`kevbox/live/LiveVideoStage.kt`). Live TV on ExoPlayer has one video
  window, a single `ExoPreviewSurface` in `LiveVideoLayer`, composed first in the shell's root
  `Box` so the whole UI draws above it. It moves, without being handed to a new surface, between the
  grid's top box (`GuideVideo`), full screen and the mini-player (the shell's player box), over
  320 ms or at once with Animations Off. The window is laid out once at the root box's full size and
  placed by a graphics layer (translation and scale from the top-left corner, corner clip divided by
  the scale): a SurfaceView resized by layout lags the frame and trailed a black strip. The UI shows
  the video through a see-through patch (a `BlendMode.Clear` draw): a site the window has settled on
  cuts its own, so its overlays draw on top; while the window moves, `LiveVideoPatch` cuts one at
  the window's current rectangle. The box slides with Live TV's guide on a layer translation: the
  stage keeps the box's rectangle without the slide and adds it where the window and the patch are
  drawn (`boxSlide`, read in the same frame as the guide's layer), since a report from
  `onGloballyPositioned` lands a frame after the layer moved. When the last site leaves, the
  window goes at once, so nothing shows through a see-through background. The one exception is an
  armed bridge (`armBridge`, which only takes while the player is on screen): after Back to the
  grid the window stays at the player's rectangle for up to 600 ms (`GRACE_MS`) until the box
  reports. Back to the grid (`isBackToGrid`) is only the user's own exit from full screen (Back,
  the HUD's back) on a Live TV channel opened in the grid, live on ExoPlayer at the live edge. It
  keeps the stream, sound on (`onFullscreenExited(keepStream = true)`), puts the playing channel in
  the box, records it as core's previewed channel (`keepInBox`, which also drops a CH+/CH− tune
  still waiting) so the next OK opens full screen at once, and after CH+/CH− moves the grid's
  cursor to it (`revealChannel`). Every other exit stops the stream and empties the box as before:
  catch-up, a rewind (archive or saved copy), mpv, Audio Mode, the mini-player's Close, Multiview's
  exit and the sleep timer (`exitPlayerWith(false)`). The TV Guide section's `GuideVideo` also uses
  the stage; KevBox hides that section. If upstream changes `ExoPreviewSurface`, the shell's player
  box, `GuideVideo` or `exitPlayer`, check the grow and the shrink on a TV.
- Live TV speed (2026-10-14, measured on Kevin's TCL). The shell keeps the browse UI composed behind
  full screen while Live TV is open, unplaced through `kevbox/ui/KeptHidden.kt` (not drawn, out of
  focus search): upstream drops it, and Back rebuilt the whole grid before the video could shrink
  (1.5-1.8 s on the TV, now about 0.2 s). A video move's clock starts at its first frame, not at the
  request, because the frame after OK or Back comes late on a slow TV and the move was over before it
  was drawn. `EpgViewModel` reads one provider category's channels alone
  (`channelsAfterCategory`) instead of the whole playlist filtered in memory, and `MAX_CHANNELS` is
  40,000 (was 20,000, which left later categories of Kevin's 20,000+ channel playlist empty).
- More Live TV speed (2026-10-14, 5.1.0.5). `stageBackground` (StageDraw.kt) paints the Stage wash and
  its lights as one baked quarter-size picture (`kevbox/ui/BakedBackground.kt`): the overdraw view on
  the TCL showed the page background painted five times before any row. `QuietAccessibility` empties
  Compose's cached list of accessibility services while no screen reader is on, through a field kept
  by name in `app/src/main/keepRules/kevbox.keep`; after a Compose update, check that
  `AndroidComposeViewAccessibilityDelegateCompat._enabledServices` still exists, or it silently does
  nothing. The shell reads the focused channel only while a player is up, grid rows read only
  whether they are the selected row, and `liveCategoryEntries` is remembered per input.
- In-app updates start compiled (5.1.0.5): `release.sh` publishes each APK's `.dm` profile next to it
  and `kevbox/release/UpdateProfileAttacher.kt` writes it into Core's install session under Core's
  entry name `owntv-update` + `.dm`. If Core renames `UpdateManager.SESSION_ENTRY`, change
  `SESSION_PROFILE_ENTRY` to match, or updates install uncompiled again (they still install).
- Proxy and DNS (5.1.0.5): kevbox.dev sets each customer's HTTP proxy beside the playlist (kevbox
  migration 0014) and `kevbox/network/ManagedProxy.kt` applies it to Core's global proxy at each
  playlist check; the Proxy row is hidden. `SupabaseApi` always connects direct. `DnsDefault` turns on
  Cloudflare DNS-over-HTTPS once per install unless a DNS of the member's own is on.
- Live-only Settings (0.2). `LiveOnlySettings` (`kevbox/settings/LiveOnlySettings.kt`) is the hide
  list: the keys of every Settings row, Video player row and search entry KevBox Live does not show,
  and the fixed values those rows used to set. At each upstream merge, check every new Settings root
  row, Video player row and More card against it, and add its key when it leads somewhere KevBox Live
  removed. Four hooks in upstream files read the list; keep all four through a merge: the `Row2`
  hook in `VideoPlayerSettingsScreen.kt` (a hidden Video player row draws nothing, on every page),
  the root filter in `SettingsScreen.kt` (`kevboxShownRootItems`), the search `key` filter there
  (`LiveOnlySettings.shows(e.key)`), and the counts-only mode of `SettingsScreen` (`onGroupCounts`,
  which More's Settings cards read through `rememberSettingsGroupCounts`). Lose one and a hidden row
  comes back, or a card's count stops matching its page. `SettingsLivePreview.kt` and
  `GlassEffectPage.kt` delete upstream's Home preview drawing (95 and 81 lines) and draw the mini
  guide instead, so an upstream edit to that drawing will conflict there: drop the upstream side.
- Refresh playlist, Update now and Restart app (2026-10-14). Settings › Playlists › Refresh
  playlist (`kevbox/refresh/RefreshChoice.kt`, stored by `PlaylistRefreshSetting`) sets Core's
  per-playlist auto-refresh timer on every playlist on the TV: every app start, 6 h, 12 h, 24 h,
  4 days (the default, also for TVs updating from 5.1.0.8, which had no channel timer) or never. It
  covers the channel list only; the guide keeps its own 24-hour timer from `GuideFeeds`. Core's
  `AutoRefresh` does the refreshing, so if upstream changes `PlaylistAutoRefresh` or `AutoRefresh`,
  check the mapping. Update now (`PlaylistUpdater` + `CoreSyncSteps`) checks kevbox.dev, resyncs the
  channels (or waits for a download already making progress; offline it only queues one), waits for
  that to end, then resyncs the playlist's own guide feeds. In the category column the open-category
  mark follows the cursor (`GuideSidebar.markedRow`); the guide still waits `REST_SELECT_MS`. More › Restart
  app (`kevbox/ui/RestartPage.kt`) calls Core's `AppIconSwitcher.restartWith` with the icon already
  in use, which goes through Core's `AppRestartActivity` (own `:restart` process): if Core renames
  or drops either, the button stops working.

## Error reporting (0.3)

KevBox Live reports two kinds of trouble. Crashes, freezes (ANRs) and native crashes go to KevBox
Live's own Sentry project, and only when the release build carries a key
(`docs/kevbox/sentry-setup.md`). Every playback, playlist and guide failure goes to kevbox.dev's
`live_error` table, with a usage check-in to `live_device`. The code is fork-only, under
`app/src/main/java/tv/own/owntv/kevbox/errors/` and `kevbox/usage/`. Four upstream files carry the
hooks, each with a row in the table above:

- `OwnTVApp.kt` starts Sentry on the line right after `CrashRecorder.install(this)`, so Sentry's
  crash handler is the outer one: it sees each crash first and hands it on to Core's, which still
  writes `files/diagnostics/last_crash.txt`. Keep that order through a merge. After Koin it starts
  the usage check-in (`LiveCheckin`), the error watchers (`ErrorWatchers`, which start the
  reporter) and `KevBoxSentry.bind` (the member's id as Sentry's user, never the email).
- `OwnTVShell.kt` has one effect right after `val liveVideoStage = remember { ... }`. It tells
  `PlaybackScene` which channel and category Live TV shows, whether the video sits in the grid's
  small box or full screen, and whether catch-up plays. It draws nothing.
- `app/build.gradle.kts` has the Sentry Gradle plugin and SDK, with versions written inline
  because `gradle/libs.versions.toml` stays unchanged, plus `BuildConfig.SENTRY_DSN` and the
  `sentry { }` block. Debug builds have a blank DSN unless the command line passes
  `-Pkevbox.sentryDsnDebug`, which only the emulator run uses, against a fake receiver.
- `AndroidManifest.xml` turns Sentry's auto-start off (`io.sentry.auto-init` false) and declares
  `SentryTestReceiver`, the adb-only trigger for the three test events, behind the DUMP
  permission.

`MainActivity.onStart` already calls `KevBoxCoordinator.onForeground()` (0.1), which now also runs
the usage check-in's foreground check, so that needs no new upstream line.

Core is read, never patched. The watchers collect Core's public failure feeds and change none of
them: `LivePreviewEngine.errorInfo` and `OwnTVPlayer.errorInfo` (both players),
`CatalogSyncScheduler.observeSync` with `SourceRepository.getLastSyncStats` (channel-list syncs),
`EpgSourceStore.sources` and each feed's `lastError` (guide feeds), and
`XtreamClient.fetchAccountStatus` for the account check. No Core class is subclassed, wrapped or
copied, and Core's own on-screen messages and on-TV error log stay as they are.

After every Core bump (an `owntvCore` change in `gradle/libs.versions.toml`), run the Core
contract test before anything else:

```sh
./gradlew :app:testStandardDebugUnitTest --tests 'tv.own.owntv.kevbox.errors.CoreContractTest'
```

It holds a typed reference to each Core member above, so it stops compiling when Core renames one
or changes its type, and it checks that `PlayerErrors.httpStatusIn` still finds the status in
Core's error texts. A failure there means the reporter would have gone quiet on the TV: fix the
watcher before releasing. The test cannot see a change in when Core sets `errorInfo` (today only
after its own retries give up). If Core's release notes mention retries or error handling in the
players, play a dead channel on the emulator and check that one `live_error` row arrives
(`docs/superpowers/2026-10-14-0.3-error-reporting-e2e.md` shows how).

The error codes live in `tools/kevbox/live-error-codes.json`, and kevbox keeps a byte-identical
copy for its labels (`kevbox:src/lib/live-error-codes.json`). `release.sh` refuses to release when
the two differ, so a new code goes into kevbox first.

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
the latest release on `kevinchiha/OwnTV`, a secret file is missing or incomplete, or `apksigner`
or `aapt2` is missing. Then it deletes any release APK an earlier build left behind, builds the
arm and x86_64 release APKs, checks that both carry versionName `<version>` and the KevBox TV
key's signature
(SHA-256 `9A:E0:71:8C:CC:F9:F6:83:90:93:49:31:D3:FE:0E:52:F4:57:EF:B5:81:D5:AC:C1:14:16:F1:51:2D:BB:1D:6F`),
runs `check-brand.sh` on the arm APK, and creates release `v<version>` with the assets `KevBoxLive.apk` and `KevBoxLive-x86_64.apk`.
Those names never change: the short link below and Core's updater both rely on them.

`bash tools/kevbox/test-release-checks.sh` tests the refusals against a throwaway repo, and
checks that `check-divergence.sh` fails when its `git diff` fails.

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
