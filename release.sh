#!/usr/bin/env bash
# KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
#
# Builds, verifies and publishes one KevBox Live release (AC-4, AC-7). Kevin runs it by hand; it
# is the only way the fork publishes (the upstream workflows skip the fork, AC-9).
#
#   ./release.sh [--no-silent] <A.B.C.K> [release notes...]
#
# By default the release carries kevbox-silent.json, which lets Android 12+ boxes install it with no
# prompt (kevbox/release/SilentUpdater.kt): {"after": <publish time + 24 h, epoch ms>, "earlyUsers":
# [<Supabase user ids>]}. Boxes signed in as an early user install it at once, the rest from "after".
# The ids come from SILENT_EARLY_USERS in ~/secrets/kevbox-keys/release.env, comma-separated; blank
# or missing means no early users. KevBox TV's release.sh reads the same line, so one list serves both
# apps. --no-silent publishes without the file: every box then gets Core's update card instead.
# KevBox: boxes on v5.2.1.4 or older ignore kevbox-silent.json: they install any newer release by
#         their old rule (24 h after it is published, early users by email), --no-silent or not.
#         So --no-silent holds only on boxes already running a build that reads the file, and the
#         first release with this code must be a normal one.
#
# Every check below runs before anything is built or created, and a failed check stops the script
# with a message and exit 1:
# KevBox: check_release_args: --no-silent counts only as the first argument; anywhere else, or any
#         other argument starting with --, stops the script, so a typo cannot publish a release that
#         installs by itself.
# KevBox: an argument is required: the version A.B.C.K (notes default to "KevBox Live <version>").
# KevBox: check_clean_tree: no uncommitted or untracked files, so the release is exactly HEAD.
# KevBox: check_version: A.B.C.K with A, B, C in 0..99 and K in 1..99 (tools/kevbox/version.sh, AC-5).
# KevBox: check_upstream_base: A.B.C equals the newest upstream vX.Y.Z tag reachable from HEAD, so
#         a typo cannot misstate the upstream base on every TV. KevBox's own four-number tags never count.
# KevBox: HEAD is on origin/kevbox-live: the release must point at a pushed commit. Pushing is
#         Kevin's step; this script never pushes.
# KevBox: check_code_above_latest: the version code is above the latest release on
#         kevinchiha/OwnTV, so Core's updater offers it to every TV.
# KevBox: both secret files exist: ~/secrets/kevbox-keys/release.env (the NUVIO_RELEASE_* signing
#         values) and ~/secrets/kevbox-keys/kevbox-live.env (KEVBOX_SUPABASE_URL and
#         KEVBOX_SUPABASE_ANON_KEY); the keystore exists; the Supabase URL starts with https:// and
#         the anon key is not blank. The files are sourced inside subshells and never printed.
# KevBox: release.env sources cleanly (an unreadable file or a syntax error stops the script
#         instead of reading as "no early users"); read_early_users (unless --no-silent) and
#         check_early_users: every SILENT_EARLY_USERS entry is a UUID; a bad entry is named by its
#         position, never its value.
# KevBox: check_sentry_settings: when kevbox-live.env sets SENTRY_AUTH_TOKEN, it sets SENTRY_ORG and
#         SENTRY_PROJECT too. The four SENTRY_* lines are optional; without them the release has no
#         Sentry key and uploads no symbols.
# KevBox: check_error_codes_match: the app's tools/kevbox/live-error-codes.json equals kevbox's
#         src/lib/live-error-codes.json, so the admin labels every code the TVs send (a warning only
#         when the kevbox checkout is missing).
#
# Then it deletes any release APK an earlier build left in Gradle's output folders (so a stale
# APK can never ship under a new tag), builds the standard (arm) and x86_64 release APKs, copies
# them to build/kevbox-release/KevBoxLive.apk and build/kevbox-release/KevBoxLive-x86_64.apk (fixed
# names, so https://github.com/kevinchiha/OwnTV/releases/latest/download/KevBoxLive.apk always
# works), checks both carry versionName <version>, checks both signatures against the KevBox TV
# key's SHA-256 (AC-4), runs tools/kevbox/check-brand.sh on the arm APK (AC-3), and only then
# creates GitHub release v<version> on kevinchiha/OwnTV with those two APKs, their compile profiles
# (.dm) and, unless --no-silent, build/kevbox-release/kevbox-silent.json, written just before.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$repo_root"
# shellcheck source=tools/kevbox/release-checks.sh
source tools/kevbox/release-checks.sh

release_repo="kevinchiha/OwnTV"
release_branch="origin/kevbox-live"
keys_dir="$HOME/secrets/kevbox-keys"
kevbox_repo="$HOME/projects/kevbox"
signing_env="$keys_dir/release.env"
supabase_env="$keys_dir/kevbox-live.env"
expected_sha256="9A:E0:71:8C:CC:F9:F6:83:90:93:49:31:D3:FE:0E:52:F4:57:EF:B5:81:D5:AC:C1:14:16:F1:51:2D:BB:1D:6F"
out_dir="build/kevbox-release"
arm_apk="$out_dir/KevBoxLive.apk"
x86_apk="$out_dir/KevBoxLive-x86_64.apk"
silent_json="$out_dir/kevbox-silent.json"

die() {
  echo "release: $*" >&2
  exit 1
}

# --- Pre-flight checks: nothing is built or created until all of them pass. ---

check_release_args "$@" || die "usage: ./release.sh [--no-silent] <A.B.C.K> [release notes...]"
no_silent=0
if [[ "${1:-}" == "--no-silent" ]]; then
  no_silent=1
  shift
fi
[[ $# -ge 1 && -n "$1" ]] || die "usage: ./release.sh [--no-silent] <A.B.C.K> [release notes...]"
version="$1"
shift
notes="$*"
[[ -n "$notes" ]] || notes="KevBox Live $version"

check_clean_tree || die "stopped: dirty working tree"
check_version "$version" || die "stopped: invalid version $version"
check_upstream_base "$version" || die "stopped: wrong upstream base"

remote_branches="$(git branch -r --contains HEAD --format='%(refname:short)')"
if ! grep -qx "$release_branch" <<<"$remote_branches"; then
  die "HEAD ($(git rev-parse --short HEAD)) is not on $release_branch; push kevbox-live first (git push origin kevbox-live), then rerun"
fi

# gh exits non-zero both when the repo has no release yet and when it cannot reach GitHub; only
# the first means "no latest release".
gh_error="$(mktemp)"
trap 'rm -f "$gh_error"' EXIT
latest=""
if ! latest="$(gh release view --repo "$release_repo" --json tagName -q .tagName 2>"$gh_error")"; then
  if grep -qi 'release not found' "$gh_error"; then
    latest=""
  else
    cat "$gh_error" >&2
    die "could not read the latest release of $release_repo (gh auth status, network?)"
  fi
fi
check_code_above_latest "$version" "$latest" || die "stopped: version code not above the latest release"

[[ -f "$signing_env" ]] || die "missing $signing_env (the NUVIO_RELEASE_* signing values)"
[[ -f "$supabase_env" ]] || die "missing $supabase_env; create it with KEVBOX_SUPABASE_URL and KEVBOX_SUPABASE_ANON_KEY (see UPSTREAM-SYNC.md)"
# Subshells keep the secrets out of this shell and out of every later command (gh, git).
(
  # KevBox: `|| exit 1` because errexit is off left of `||`; output dropped because bash shows the
  # offending line, value included, on a syntax error (see read_early_users).
  # shellcheck source=/dev/null
  source "$signing_env" >/dev/null 2>&1 || exit 1
  [[ -n "${NUVIO_RELEASE_STORE_FILE:-}" && -f "${NUVIO_RELEASE_STORE_FILE}" ]] || exit 1
  [[ -n "${NUVIO_RELEASE_STORE_PASSWORD:-}" && -n "${NUVIO_RELEASE_KEY_ALIAS:-}" && -n "${NUVIO_RELEASE_KEY_PASSWORD:-}" ]] || exit 1
) || die "$signing_env must source cleanly and set NUVIO_RELEASE_STORE_FILE (an existing keystore), NUVIO_RELEASE_STORE_PASSWORD, NUVIO_RELEASE_KEY_ALIAS and NUVIO_RELEASE_KEY_PASSWORD"
(
  # shellcheck source=/dev/null
  source "$supabase_env"
  [[ "${KEVBOX_SUPABASE_URL:-}" == https://* && -n "${KEVBOX_SUPABASE_ANON_KEY:-}" ]] || exit 1
) || die "$supabase_env must set KEVBOX_SUPABASE_URL to an https:// address and a non-blank KEVBOX_SUPABASE_ANON_KEY"
(
  unset SENTRY_AUTH_TOKEN SENTRY_ORG SENTRY_PROJECT
  # shellcheck source=/dev/null
  source "$supabase_env"
  check_sentry_settings "${SENTRY_AUTH_TOKEN:-}" "${SENTRY_ORG:-}" "${SENTRY_PROJECT:-}"
) || die "stopped: incomplete Sentry settings in $supabase_env"
check_error_codes_match "$kevbox_repo" || die "stopped: error codes differ from kevbox"
early_users=""
if (( no_silent == 0 )); then
  # Only this line leaves read_early_users' subshell; the signing values stay inside it.
  early_users="$(read_early_users "$signing_env")" || die "stopped: could not read SILENT_EARLY_USERS from $signing_env"
  check_early_users "$early_users" || die "stopped: fix SILENT_EARLY_USERS in $signing_env, or pass --no-silent"
fi

version_code="$(bash tools/kevbox/version.sh code "$version")"

# KevBox: the SDK tools the checks after the build need, looked up before anything is built.
android_home="${ANDROID_HOME:-/home/kevin/Android/Sdk}"
apksigner="$(ls -d "$android_home"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n 1 || true)"
[[ -n "$apksigner" && -x "$apksigner" ]] || die "no apksigner under $android_home/build-tools"
aapt2="$(ls -d "$android_home"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n 1 || true)"
[[ -n "$aapt2" && -x "$aapt2" ]] || die "no aapt2 under $android_home/build-tools"

# --- Build. ---

built_arm="app/build/outputs/apk/standard/release/app-standard-release.apk"
built_x86="app/build/outputs/apk/x86_64/release/app-x86_64-release.apk"
# KevBox: delete every earlier release APK first. Gradle leaves the last build's APK in place when
# signing is not set up (it then writes only an -unsigned APK), and the copy below would ship it.
rm -f -- "$built_arm" "$built_x86" "$arm_apk" "$x86_apk"
# KevBox: the profiles too (see dex_metadata below), so an old one can never ship beside a new APK.
rm -rf -- app/build/outputs/apk/standard/release/baselineProfiles app/build/outputs/apk/x86_64/release/baselineProfiles
rm -f -- "$out_dir"/*.dm
# KevBox: and the silent block, so one from an earlier run can never ship, least of all with --no-silent.
rm -f -- "$silent_json"

echo "release: building KevBox Live $version (code $version_code)"
(
  # shellcheck source=/dev/null
  source "$signing_env"
  # KevBox: the SENTRY_* values come from kevbox-live.env alone, blank when it has none.
  unset SENTRY_DSN SENTRY_AUTH_TOKEN SENTRY_ORG SENTRY_PROJECT
  # shellcheck source=/dev/null
  source "$supabase_env"
  export SENTRY_DSN="${SENTRY_DSN:-}" SENTRY_AUTH_TOKEN="${SENTRY_AUTH_TOKEN:-}"
  export SENTRY_ORG="${SENTRY_ORG:-}" SENTRY_PROJECT="${SENTRY_PROJECT:-}"
  export KEYSTORE_FILE="$NUVIO_RELEASE_STORE_FILE"
  export KEYSTORE_PASSWORD="$NUVIO_RELEASE_STORE_PASSWORD"
  export KEY_ALIAS="$NUVIO_RELEASE_KEY_ALIAS"
  export KEY_PASSWORD="$NUVIO_RELEASE_KEY_PASSWORD"
  export KEVBOX_SUPABASE_URL KEVBOX_SUPABASE_ANON_KEY
  export VERSION_NAME="$version" VERSION_CODE="$version_code"
  ./gradlew assembleStandardRelease assembleX86_64Release
) || die "the Gradle build failed; nothing was published"

[[ -f "$built_arm" ]] || die "no signed arm APK at $built_arm (an -unsigned APK means signing was not set up)"
[[ -f "$built_x86" ]] || die "no signed x86_64 APK at $built_x86"
mkdir -p "$out_dir"
cp "$built_arm" "$arm_apk"
cp "$built_x86" "$x86_apk"

# KevBox: each APK's compile profile (a .dm file Gradle writes beside it), published next to the APK.
# UpdateProfileAttacher adds it to the in-app update's install, so an update starts compiled instead
# of slow. <apk>.dm is for Android 12 and later, <apk>-api28.dm for Android 9 to 11.
dex_metadata() { # <gradle apk dir> <published apk path>
  local dir="$1" base="${2%.apk}"
  python3 - "$dir" "$base" <<'PY' || die "no compile profiles in $1/output-metadata.json; nothing was published"
import json, shutil, sys
d, base = sys.argv[1], sys.argv[2]
meta = json.load(open(f"{d}/output-metadata.json"))
found = 0
for entry in meta.get("baselineProfiles", []):
    name = {31: ".dm", 28: "-api28.dm"}.get(entry["minApi"])
    if name:
        shutil.copy(f"{d}/{entry['baselineProfiles'][0]}", base + name)
        found += 1
sys.exit(0 if found == 2 else 1)
PY
}
dex_metadata app/build/outputs/apk/standard/release "$arm_apk"
dex_metadata app/build/outputs/apk/x86_64/release "$x86_apk"

# --- Version check: both APKs say the version this release is tagged with. ---

for apk in "$arm_apk" "$x86_apk"; do
  badging="$("$aapt2" dump badging "$apk")" || die "aapt2 could not read $apk; nothing was published"
  built_name="$(sed -n "s/^package: .* versionName='\([^']*\)'.*/\1/p" <<<"$badging")"
  [[ "$built_name" == "$version" ]] \
    || die "$apk has versionName '$built_name', not $version; nothing was published"
done

# --- Signature check (AC-4). ---

normalize() { tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]'; }
want="$(printf '%s' "$expected_sha256" | normalize)"
for apk in "$arm_apk" "$x86_apk"; do
  digests="$("$apksigner" verify --print-certs "$apk" | sed -n 's/.*certificate SHA-256 digest: *//p')" \
    || die "apksigner rejected $apk; nothing was published"
  [[ -n "$digests" ]] || die "apksigner printed no SHA-256 digest for $apk; nothing was published"
  while IFS= read -r digest; do
    [[ "$(printf '%s' "$digest" | normalize)" == "$want" ]] \
      || die "$apk is signed with SHA-256 $digest, not the KevBox TV key $expected_sha256; nothing was published"
  done <<<"$digests"
  echo "release: $apk signed with the KevBox TV key"
done

# --- Brand check (AC-3): no shipped string may still say OwnTV. ---

bash tools/kevbox/check-brand.sh "$arm_apk" \
  || die "$arm_apk still ships OwnTV text (see above); nothing was published"

# --- Publish. ---

assets=("$arm_apk" "$x86_apk" "$out_dir"/*.dm)
if (( no_silent == 0 )); then
  # Written last, so "after" is 24 h from now, as close to publishing as it gets.
  now_ms="$(date +%s%3N)"
  render_silent_json "$now_ms" "$early_users" > "$silent_json" \
    || die "could not write $silent_json; nothing was published"
  assets+=("$silent_json")
  echo "release: silent install from $(date -d "@$(( now_ms / 1000 + 86400 ))" '+%Y-%m-%d %H:%M %Z'), early users first"
else
  echo "release: --no-silent: no kevbox-silent.json, every box gets Core's update card"
fi

gh release create "v$version" --repo "$release_repo" --target "$(git rev-parse HEAD)" \
  --title "KevBox Live $version" --notes "$notes" "${assets[@]}"
echo "release: published v$version on $release_repo"
