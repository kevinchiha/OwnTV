#!/usr/bin/env bash
# KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
#
# AC-37, AC-38, AC-39 check. Lists every file that differs from the last upstream commit merged
# into this branch and fails on fork changes that a later upstream merge could lose unnoticed.
#
#   bash tools/kevbox/check-divergence.sh
#
# It compares the merge base with upstream/main against HEAD (`git diff upstream/main...HEAD`,
# three dots). The two-dot form would compare against upstream/main's tip, which moves ahead of the
# merged release after every Core pin bump, and every upstream-only change would then show up here.
#
# One line per file:
#   fork-only <path>   the file does not exist upstream
#   changed <path>     an upstream file this fork changes
#   deleted <path>     an upstream file this fork deletes
#   exempt <path>      changed, but listed in tools/kevbox/divergence-exempt.txt
#   FAIL <path>: <why> a rule below is broken
#
# Rules (exit 1 when any is broken):
#   - every diff hunk (3 lines of context) of a changed or deleted upstream file has "KevBox:" on
#     at least one of its lines (AC-37), unless the file is exempt;
#   - every changed or deleted upstream file that is not exempt is named in the first column of
#     the changed-files table in UPSTREAM-SYNC.md;
#   - fork-only Kotlin under app/src/main/java lives under tv/own/owntv/kevbox/ and starts with
#     the fork-only header line (AC-39).
#
# It only reads: it never fetches, merges or changes a file. Uncommitted changes are not checked.
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

exempt_file="tools/kevbox/divergence-exempt.txt"
sync_doc="UPSTREAM-SYNC.md"
table_heading="## Changed upstream files"
kotlin_root="app/src/main/java/"
kevbox_kotlin_root="app/src/main/java/tv/own/owntv/kevbox/"
fork_only_header="// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV)."

if ! git remote get-url upstream >/dev/null 2>&1; then
  echo "check-divergence: no 'upstream' remote. Add it with:" >&2
  echo "  git remote add upstream https://github.com/ahXN00/OwnTV.git && git fetch upstream --tags" >&2
  exit 1
fi
if ! git rev-parse --verify --quiet refs/remotes/upstream/main >/dev/null; then
  echo "check-divergence: upstream/main is unknown here. Run: git fetch upstream --tags" >&2
  exit 1
fi
base="$(git merge-base upstream/main HEAD)" || {
  echo "check-divergence: HEAD shares no history with upstream/main" >&2
  exit 1
}

# Exempt patterns: one path or glob per line, "#" starts a comment.
exempt_patterns=()
if [[ -f "$exempt_file" ]]; then
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%%#*}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -n "$line" ]] && exempt_patterns+=("$line")
  done < "$exempt_file"
fi

is_exempt() {
  local path="$1" pattern
  for pattern in "${exempt_patterns[@]+"${exempt_patterns[@]}"}"; do
    # shellcheck disable=SC2053  # the pattern is a glob on purpose
    [[ "$path" == $pattern ]] && return 0
  done
  return 1
}

# Paths named in the first column of the changed-files table, between its heading and the next
# "## " heading. A cell may hold the path in backticks.
table_paths=""
if [[ -f "$sync_doc" ]]; then
  table_paths="$(awk -v heading="$table_heading" '
    $0 == heading { inside = 1; next }
    inside && /^## / { inside = 0 }
    inside && /^\|/ {
      split($0, cells, "|")
      cell = cells[2]
      gsub(/`/, "", cell)
      gsub(/^[ \t]+|[ \t]+$/, "", cell)
      if (cell != "" && cell !~ /^-+$/) print cell
    }
  ' "$sync_doc")"
fi

in_table() {
  [[ -n "$table_paths" ]] && grep -Fxq -- "$1" <<<"$table_paths"
}

# Prints nothing when every hunk of the file's diff has "KevBox:" on one of its lines, otherwise the
# header of the first hunk without one. Binary diffs have no hunks and are reported separately.
first_unmarked_hunk() {
  git diff --no-color --no-renames -U3 upstream/main...HEAD -- "$1" | awk '
    function close_hunk() { if (open && !marked && first == "") first = header; open = 0 }
    /^@@ / { close_hunk(); open = 1; marked = 0; header = $0; next }
    open && /KevBox:/ { marked = 1 }
    END { close_hunk(); if (first != "") print first }
  '
}

is_binary() {
  [[ "$(git diff --no-renames --numstat upstream/main...HEAD -- "$1" | cut -f1)" == "-" ]]
}

failures=0
fail() {
  echo "FAIL $1: $2"
  failures=$((failures + 1))
}

# Captured first, with its exit status: read through process substitution, a failing git command
# would look like an empty diff and pass.
if ! name_status="$(git -c core.quotePath=false diff --no-renames --name-status upstream/main...HEAD)"; then
  echo "check-divergence: git diff against upstream/main failed" >&2
  exit 1
fi

fork_only=0
changed=0
while IFS=$'\t' read -r status path; do
  [[ -n "$status" ]] || continue
  case "$status" in
    A)
      echo "fork-only $path"
      fork_only=$((fork_only + 1))
      if [[ "$path" == "$kotlin_root"*.kt ]]; then
        if [[ "$path" != "$kevbox_kotlin_root"* ]]; then
          fail "$path" "fork-only Kotlin must live under $kevbox_kotlin_root (AC-39)"
        elif [[ "$(git show "HEAD:$path" | head -n 1)" != "$fork_only_header" ]]; then
          fail "$path" "first line must be the fork-only header: $fork_only_header (AC-39)"
        fi
      fi
      ;;
    M | T | D)
      changed=$((changed + 1))
      if is_exempt "$path"; then
        echo "exempt $path"
        continue
      fi
      if [[ "$status" == D ]]; then echo "deleted $path"; else echo "changed $path"; fi
      if is_binary "$path"; then
        fail "$path" "binary file, cannot carry a KevBox: marker; list it in $exempt_file with a reason"
      else
        hunk="$(first_unmarked_hunk "$path")"
        [[ -z "$hunk" ]] || fail "$path" "diff hunk with no KevBox: comment: $hunk"
      fi
      in_table "$path" || fail "$path" "not named in the changed-files table of $sync_doc"
      ;;
    *)
      fail "$path" "unexpected diff status $status"
      ;;
  esac
done <<<"$name_status"

echo "check-divergence: $fork_only fork-only, $changed changed upstream file(s), $failures failure(s) against merge base ${base:0:12}"
[[ "$failures" -eq 0 ]]
