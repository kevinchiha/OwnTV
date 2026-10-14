# Sentry for KevBox Live: setup and test

Sentry is a website that collects app crashes. KevBox Live sends it three things, and only from a
release built with a key: crashes in the app's own code, crashes inside the video player's native
libraries (mpv, ffmpeg), and freezes (Android's "isn't responding"). Playback, playlist and guide
errors go to kevbox.dev instead, not to Sentry.

Until you do the steps below, nothing changes: `release.sh` builds a release with no key, and that
release never contacts Sentry.

## 1. Create the account and the project

1. Sign up at <https://sentry.io/signup/>. When it asks where to store data, pick the United
   States. With the EU region, Sentry's upload tool has had trouble with tokens
   ([getsentry/sentry-cli#3385](https://github.com/getsentry/sentry-cli/issues/3385)), and
   `release.sh` passes no region setting.
2. Sentry creates an organisation for you. Note its slug, the short name in the address bar
   (`https://sentry.io/organizations/<slug>/`). You can also read it under Settings → General
   Settings → Organization Slug.
3. Create a project: Projects → Create Project → platform **Android** → name it `kevbox-live`.
   Skip the setup wizard it offers afterwards; the app is already wired.

## 2. Copy the two values

1. **The DSN** (the address the app sends crashes to). Settings → Projects → `kevbox-live` →
   Client Keys (DSN). Copy the DSN, which looks like `https://<key>@o<number>.ingest.sentry.io/<number>`.
   It ends up inside the APK; that is normal for Sentry.
2. **An auth token** (lets the release build upload the files Sentry needs to turn scrambled
   crash reports back into readable code). Click your name at the bottom left → User Settings →
   Personal Tokens → Create New Token. Give it the scopes `project:releases` and `org:read` and
   nothing else. Copy it when it shows; Sentry shows it once. Treat it like a password: it never
   goes into the APK or the repo.

## 3. Put them in the secrets file

Add four lines to `~/secrets/kevbox-keys/kevbox-live.env` (the file that already holds the KevBox
Supabase URL and key), never to the repo:

```sh
SENTRY_DSN=<the DSN from step 2.1>
SENTRY_AUTH_TOKEN=<the token from step 2.2>
SENTRY_ORG=<your organisation slug>
SENTRY_PROJECT=kevbox-live
```

The file should stay readable by you alone (`chmod 600 ~/secrets/kevbox-keys/kevbox-live.env`).

## 4. What `release.sh` does with them

- Before building, it stops if `SENTRY_AUTH_TOKEN` is set but `SENTRY_ORG` or `SENTRY_PROJECT` is
  blank, and names the missing line.
- It hands the four values to the Gradle build and never prints them.
- `SENTRY_DSN` goes into the APK, so the app knows where to send crashes. Without it the app never
  starts Sentry.
- With the token, the build uploads the code map (so Java crash lines read as real class and method
  names) and the native symbol files to your Sentry project. Without it, nothing is uploaded and
  crash reports show scrambled names.
- Native crashes inside mpv or ffmpeg show the library and an offset, not function names. Core
  ships those libraries stripped, so no setting fixes that.
- It also stops if the app's error code list differs from kevbox.dev's copy, so update kevbox
  first when a code changes.

Debug builds never have a key, unless someone passes `-Pkevbox.sentryDsnDebug` on the command
line, which only the emulator test does, against a fake receiver on this PC.

## 5. Make the three test events

After installing a release built with the key on a TV, sign in so the events carry the member's
id, then run these from the PC over adb. Each one appears in Sentry under Issues with the
member's id as the user and no email. The crash is sent as it happens; the native crash and the
freeze are sent when the app next starts.

A crash in the app's code (the app closes; open it again):

```sh
adb shell am broadcast -a tv.kevbox.live.SENTRY_TEST --es kind crash -n tv.kevbox.live/tv.own.owntv.kevbox.errors.SentryTestReceiver
```

A native crash, like one inside the video player (the app closes; open it again, which is when
the event is sent):

```sh
adb shell am broadcast -a tv.kevbox.live.SENTRY_TEST --es kind native -n tv.kevbox.live/tv.own.owntv.kevbox.errors.SentryTestReceiver
```

A freeze. The app's screen stops responding for 30 seconds:

```sh
adb shell am broadcast -a tv.kevbox.live.SENTRY_TEST --es kind freeze -n tv.kevbox.live/tv.own.owntv.kevbox.errors.SentryTestReceiver
```

1. Within those 30 seconds, press any key on the remote (or `adb shell input keyevent DPAD_DOWN`).
   Android only declares a freeze when a key goes unanswered.
2. Wait for "KevBox Live isn't responding". If it shows, choose **Close app**. Some Android
   builds close the app on their own without showing the box; that counts too.
3. Open the app again. Android records the freeze, and Sentry reads it at that start and sends it.

Do not use Force stop (in Settings or `adb shell am force-stop`) for the freeze test. Android then
records "stopped by the user" instead of a freeze, and Sentry sends nothing.

The trigger needs adb: it is guarded by a permission only adb and the system hold, so no other
app can fire it and members never see it.

## 6. If an event does not show

- Was the APK built by `release.sh` after you added the lines? An older APK has no key.
- Is the TV online, and was the app opened again after the crash? Anything Sentry could not send
  at the time goes out at the next start.
- In Sentry, check Issues for the `kevbox-live` project with "All environments" (the app reports
  as `production`).
