#!/usr/bin/env bash
# KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
# Builds the current tree as a signed release with the version Kevin's TV already runs and installs
# it over adb, keeping the app's data. Usage: tools/kevbox/tv-install.sh [A.B.C.K]
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

tv="${KEVBOX_TV:-192.168.68.71:5555}"
pkg=tv.kevbox.live
keys_dir="$HOME/secrets/kevbox-keys"
signing_env="$keys_dir/release.env"
supabase_env="$keys_dir/kevbox-live.env"
apk=app/build/outputs/apk/standard/release/app-standard-release.apk
die() { echo "tv-install: $*" >&2; exit 1; }

adb connect "$tv" >/dev/null 2>&1 || true
adb -s "$tv" get-state >/dev/null 2>&1 || die "the TV at $tv does not answer; wake it and run this again"
version="${1:-$(adb -s "$tv" shell dumpsys package "$pkg" | sed -n 's/.*versionName=\([0-9.]*\).*/\1/p' | head -n 1)}"
[[ -n "$version" ]] || die "could not read the versionName of $pkg on the TV; pass the version as the first argument"
code="$(bash tools/kevbox/version.sh code "$version")"
[[ -f "$signing_env" ]] || die "missing $signing_env (the NUVIO_RELEASE_* signing values)"
[[ -f "$supabase_env" ]] || die "missing $supabase_env; create it with KEVBOX_SUPABASE_URL and KEVBOX_SUPABASE_ANON_KEY (see UPSTREAM-SYNC.md)"
# The same checks release.sh makes. Gradle builds with a blank Supabase config when a value is
# missing, and that APK would replace the working app on the TV. Subshells keep the secrets out of
# this shell, and nothing here prints a value.
(
  # shellcheck source=/dev/null
  source "$signing_env"
  [[ -n "${NUVIO_RELEASE_STORE_FILE:-}" && -f "${NUVIO_RELEASE_STORE_FILE}" ]] || exit 1
  [[ -n "${NUVIO_RELEASE_STORE_PASSWORD:-}" && -n "${NUVIO_RELEASE_KEY_ALIAS:-}" && -n "${NUVIO_RELEASE_KEY_PASSWORD:-}" ]] || exit 1
) || die "$signing_env must set NUVIO_RELEASE_STORE_FILE (an existing keystore), NUVIO_RELEASE_STORE_PASSWORD, NUVIO_RELEASE_KEY_ALIAS and NUVIO_RELEASE_KEY_PASSWORD; nothing was built or installed"
(
  # shellcheck source=/dev/null
  source "$supabase_env"
  [[ "${KEVBOX_SUPABASE_URL:-}" == https://* && -n "${KEVBOX_SUPABASE_ANON_KEY:-}" ]] || exit 1
) || die "$supabase_env must set KEVBOX_SUPABASE_URL to an https:// address and a non-blank KEVBOX_SUPABASE_ANON_KEY; nothing was built or installed"

rm -f -- "$apk"
echo "tv-install: building KevBox Live $version (code $code)"
(
  # shellcheck source=/dev/null
  source "$signing_env"
  # shellcheck source=/dev/null
  source "$supabase_env"
  export KEYSTORE_FILE="$NUVIO_RELEASE_STORE_FILE"
  export KEYSTORE_PASSWORD="$NUVIO_RELEASE_STORE_PASSWORD"
  export KEY_ALIAS="$NUVIO_RELEASE_KEY_ALIAS"
  export KEY_PASSWORD="$NUVIO_RELEASE_KEY_PASSWORD"
  export KEVBOX_SUPABASE_URL KEVBOX_SUPABASE_ANON_KEY
  export VERSION_NAME="$version" VERSION_CODE="$code"
  GRADLE_USER_HOME=/home/kevin/.gradle ./gradlew assembleStandardRelease
) || die "the Gradle build failed; nothing was installed"
[[ -f "$apk" ]] || die "no signed APK at $apk (an -unsigned APK means signing was not set up)"

adb -s "$tv" install -r "$apk"
adb -s "$tv" shell cmd package compile -m speed-profile -f "$pkg"
adb -s "$tv" shell am force-stop "$pkg"
echo "tv-install: KevBox Live $version (code $code) is on $tv"
