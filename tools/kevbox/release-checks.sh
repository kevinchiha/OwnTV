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
