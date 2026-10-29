#!/usr/bin/env bash
# KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
#
# The pre-flight checks of ./release.sh (AC-7), kept apart so tools/kevbox/test-release-checks.sh
# can run each one against a throwaway repo. Source it; do not run it. Every function works on the
# git repo of the current directory, prints why it refuses on stderr, and returns 1 when it refuses.
#
#   check_clean_tree                          refuses when `git status --porcelain` prints anything
#   check_version <v>                         refuses unless tools/kevbox/version.sh accepts <v>
#   check_upstream_base <v>                   refuses unless A.B.C equals the newest vX.Y.Z tag
#                                             reachable from HEAD
#   check_code_above_latest <v> <latest-tag>  refuses unless code(v) > code(latest); an empty
#                                             latest tag (no release yet) passes
#   check_error_codes_match <kevbox repo>     refuses when <kevbox repo>/src/lib/live-error-codes.json
#                                             differs from tools/kevbox/live-error-codes.json; warns
#                                             and passes when the kevbox file is missing
#   check_sentry_settings <token> <org> <project>
#                                             refuses when a Sentry auth token is set but the org
#                                             or project is blank (an upload would fail mid-build)
#   check_release_args <release.sh arguments...>
#                                             refuses --no-silent anywhere but first, any other
#                                             argument starting with --, and a version starting
#                                             with -; names the argument's position
#   check_early_users <comma list>            refuses an entry that is not a UUID (8-4-4-4-12 hex),
#                                             naming its position, never its value; blank passes
#   render_silent_json <now ms> <comma list>  prints kevbox-silent.json: {"after": now + 24 h,
#                                             "earlyUsers": [...]}, entries trimmed
#   read_early_users <env file>               prints SILENT_EARLY_USERS from <env file> (blank when
#                                             the line is missing); refuses when the file cannot be
#                                             sourced, and never shows its lines or values
#
# check_release_args, check_early_users and render_silent_json copy KevBox TV's
# scripts/release_manifest.py, so one SILENT_EARLY_USERS line (Supabase user ids) and one argument
# rule serve both apps.

# Both callers run with these options already; setting them here keeps the file safe on its own.
set -euo pipefail

# Absolute path, so the checks work from any directory, including the test's throwaway repo.
KEVBOX_RELEASE_CHECKS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

check_clean_tree() {
  local status
  status="$(git status --porcelain)" || return 1
  if [[ -n "$status" ]]; then
    echo "release: the working tree has uncommitted or untracked files; commit or remove them first:" >&2
    echo "$status" >&2
    return 1
  fi
}

check_version() {
  local version="${1:-}"
  # version.sh prints its own reason on stderr.
  bash "$KEVBOX_RELEASE_CHECKS_DIR/version.sh" code "$version" >/dev/null || return 1
}

check_upstream_base() {
  local version="${1:-}" base newest
  base="$(bash "$KEVBOX_RELEASE_CHECKS_DIR/version.sh" base "$version")" || return 1
  # Upstream tags only: three numbers. KevBox's own vA.B.C.K tags never count.
  newest="$(git tag --merged HEAD | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sed 's/^v//' | sort -V | tail -n 1 || true)"
  if [[ -z "$newest" ]]; then
    echo "release: no upstream vX.Y.Z tag is reachable from HEAD; run git fetch upstream --tags" >&2
    return 1
  fi
  if [[ "$base" != "$newest" ]]; then
    echo "release: $version is built on $base, but the newest upstream tag in this branch is v$newest; use $newest.K" >&2
    return 1
  fi
}

check_code_above_latest() {
  local version="${1:-}" latest="${2:-}" code latest_code latest_name
  code="$(bash "$KEVBOX_RELEASE_CHECKS_DIR/version.sh" code "$version")" || return 1
  [[ -z "$latest" ]] && return 0
  latest_name="${latest#v}"
  if [[ "$latest_name" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    # A three-number tag reads as A.B.C.0 under the same formula.
    latest_code=$((10#${BASH_REMATCH[1]} * 1000000 + 10#${BASH_REMATCH[2]} * 10000 + 10#${BASH_REMATCH[3]} * 100))
  elif ! latest_code="$(bash "$KEVBOX_RELEASE_CHECKS_DIR/version.sh" code "$latest_name" 2>/dev/null)"; then
    echo "release: cannot read a version from the latest release tag '$latest'; check the releases by hand" >&2
    return 1
  fi
  if (( code <= latest_code )); then
    echo "release: $version (code $code) is not above the latest release $latest (code $latest_code)" >&2
    return 1
  fi
}

check_error_codes_match() {
  local kevbox_repo="${1:-}" theirs ours="tools/kevbox/live-error-codes.json"
  theirs="$kevbox_repo/src/lib/live-error-codes.json"
  if [[ ! -f "$theirs" ]]; then
    echo "release: warning: $theirs not found, so the error codes were not compared with kevbox's label list" >&2
    return 0
  fi
  if ! cmp -s "$theirs" "$ours"; then
    echo "release: the app's error codes differ from kevbox's label list; update kevbox first ($theirs vs $ours)" >&2
    return 1
  fi
}

check_sentry_settings() {
  local token="${1:-}" org="${2:-}" project="${3:-}" missing=()
  [[ -z "$token" ]] && return 0
  [[ -z "$org" ]] && missing+=("SENTRY_ORG")
  [[ -z "$project" ]] && missing+=("SENTRY_PROJECT")
  if (( ${#missing[@]} > 0 )); then
    echo "release: SENTRY_AUTH_TOKEN is set but ${missing[*]} is blank; add the missing line to kevbox-live.env or remove the token" >&2
    return 1
  fi
}

# Silent install is the default, so a misplaced --no-silent would publish a release that installs
# by itself; it stops the script instead.
check_release_args() {
  local skip=0 position=0 arg
  [[ "${1:-}" == "--no-silent" ]] && skip=1
  for arg in "$@"; do
    position=$((position + 1))
    (( position <= skip )) && continue
    if [[ "$arg" == "--no-silent" ]]; then
      echo "release: argument $position: --no-silent must come first, before the version" >&2
      return 1
    fi
    # Any --option, and a version that looks like an option (-h, -v).
    if [[ "$arg" == --* ]] || { (( position == skip + 1 )) && [[ "$arg" == -* ]]; }; then
      echo "release: argument $position: unknown option '$arg'" >&2
      return 1
    fi
  done
}

# The early-user list: comma-separated Supabase user ids. Blank (or only spaces) is no early users.
check_early_users() {
  local position=0 entry
  release_early_users "${1:-}"
  for entry in "${RELEASE_EARLY_USERS[@]+"${RELEASE_EARLY_USERS[@]}"}"; do
    position=$((position + 1))
    if [[ ! "$entry" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]; then
      echo "release: SILENT_EARLY_USERS entry $position is not a UUID" >&2
      return 1
    fi
  done
}

render_silent_json() {
  local now_ms="${1:-}" raw="${2:-}" entry users=""
  if [[ ! "$now_ms" =~ ^[0-9]{1,15}$ ]]; then
    echo "release: the time for kevbox-silent.json is not a number of milliseconds" >&2
    return 1
  fi
  check_early_users "$raw" || return 1
  release_early_users "$raw"
  for entry in "${RELEASE_EARLY_USERS[@]+"${RELEASE_EARLY_USERS[@]}"}"; do
    # Checked above: every entry is hex and dashes, so it needs no JSON escaping.
    users+="${users:+, }\"$entry\""
  done
  printf '{"after": %d, "earlyUsers": [%s]}\n' "$((10#$now_ms + 86400000))" "$users"
}

# The env file is sourced in a subshell, so only this one line leaves it. Its own output is
# dropped: bash prints the offending line, value included, on a syntax error. The `|| exit 1` is
# needed because errexit does not reach a command substitution, nor anything left of `||`: without
# it an unreadable file or a syntax error still printed an empty list and passed.
read_early_users() {
  local env_file="${1:-}"
  if ! (
    unset SILENT_EARLY_USERS
    # shellcheck source=/dev/null
    source "$env_file" >/dev/null 2>&1 || exit 1
    printf '%s' "${SILENT_EARLY_USERS:-}"
  ); then
    echo "release: $env_file could not be sourced (unreadable, or a line bash cannot run; check its quoting)" >&2
    return 1
  fi
}

# Splits <comma list> into RELEASE_EARLY_USERS, each entry trimmed. Like Python's split(","), it
# keeps empty entries ("a,,b" and "a," have an empty one), so the UUID check refuses them.
release_early_users() {
  local rest="$1" piece
  RELEASE_EARLY_USERS=()
  [[ -z "${rest//[[:space:]]/}" ]] && return 0
  while :; do
    piece="${rest%%,*}"
    piece="${piece#"${piece%%[![:space:]]*}"}"
    piece="${piece%"${piece##*[![:space:]]}"}"
    RELEASE_EARLY_USERS+=("$piece")
    [[ "$rest" == *,* ]] || break
    rest="${rest#*,}"
  done
}
