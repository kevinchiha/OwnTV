#!/usr/bin/env bash
# KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
#
# Test for the release pre-flight checks in tools/kevbox/release-checks.sh (AC-7's refusals).
# Builds a throwaway git repo under mktemp -d with tags v5.0.4, v5.1.0 and a later v5.1.0.1, runs
# every check against it and asserts the exit code of each case. A second throwaway repo checks
# that tools/kevbox/check-divergence.sh fails when its git diff fails. Removes only that directory.
#
#   bash tools/kevbox/test-release-checks.sh
set -euo pipefail

checks="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/release-checks.sh"
# shellcheck source=tools/kevbox/release-checks.sh
source "$checks"

tmp="$(mktemp -d)"
trap 'rm -rf -- "${tmp:?}"' EXIT
cd "$tmp"

git_q() { git -c user.name=test -c user.email=test@example.invalid -c commit.gpgsign=false -c tag.gpgsign=false "$@" >/dev/null; }
git_q init -q
commit() {
  echo "$1" >> history.txt
  git_q add history.txt
  git_q commit -q -m "$1"
}
commit "upstream 5.0.4"; git_q tag v5.0.4
commit "upstream 5.1.0"; git_q tag v5.1.0
commit "kevbox 5.1.0.1"; git_q tag v5.1.0.1

passed=0
failed=0
# expect <0|1> <description> <command...>: runs the command in a subshell and compares its exit code.
expect() {
  local want="$1" what="$2" got
  shift 2
  if ( "$@" ) 2>/dev/null; then got=0; else got=1; fi
  if [[ "$got" == "$want" ]]; then
    echo "ok   $what"
    passed=$((passed + 1))
  else
    echo "FAIL $what (exit $got, wanted $want)"
    failed=$((failed + 1))
  fi
}

expect 0 "clean tree passes"                         check_clean_tree
echo "stray" > untracked.txt
expect 1 "untracked file refused"                    check_clean_tree
rm untracked.txt
echo "edit" >> history.txt
expect 1 "modified file refused"                     check_clean_tree
git_q checkout -q -- history.txt
expect 0 "clean again after the revert"              check_clean_tree

expect 0 "version 5.1.0.1 accepted"                  check_version 5.1.0.1
expect 1 "version 5.1.0 refused (three numbers)"     check_version 5.1.0
expect 1 "version 5.1.0.0 refused (K is 0)"          check_version 5.1.0.0
expect 1 "version v5.1.0.1 refused (v prefix)"       check_version v5.1.0.1
expect 1 "version 5.1.100.1 refused (C over 99)"     check_version 5.1.100.1
expect 1 "missing version refused"                   check_version

expect 0 "base 5.1.0 matches newest upstream v5.1.0" check_upstream_base 5.1.0.1
expect 0 "the KevBox tag v5.1.0.1 is not a base"     check_upstream_base 5.1.0.2
expect 1 "base 5.2.0 refused"                        check_upstream_base 5.2.0.1
expect 1 "older base 5.0.4 refused"                  check_upstream_base 5.0.4.1
expect 1 "invalid version refused before the base"   check_upstream_base 5.1.0

expect 0 "5.1.0.2 above v5.1.0.1"                    check_code_above_latest 5.1.0.2 v5.1.0.1
expect 1 "5.1.0.1 not above v5.1.0.1"                check_code_above_latest 5.1.0.1 v5.1.0.1
expect 1 "5.1.0.1 not above v5.1.0.2"                check_code_above_latest 5.1.0.1 v5.1.0.2
expect 0 "5.2.0.1 above v5.1.0.9"                    check_code_above_latest 5.2.0.1 v5.1.0.9
expect 0 "5.1.0.1 above an empty latest"             check_code_above_latest 5.1.0.1 ""
expect 0 "99.99.99.99 above an empty latest"         check_code_above_latest 99.99.99.99 ""
expect 1 "invalid version refused with no latest"    check_code_above_latest 5.1.0 ""
expect 0 "5.1.0.1 above a three-number tag v5.1.0"   check_code_above_latest 5.1.0.1 v5.1.0
expect 1 "unreadable latest tag refused"             check_code_above_latest 5.1.0.1 nightly

git_q tag -d v5.0.4
git_q tag -d v5.1.0
expect 1 "no upstream tag reachable refused"         check_upstream_base 5.1.0.1

# check-divergence.sh guards every upstream merge, and its failure path is tested here too: a git
# diff that fails must fail the check, never read as "0 changed". A throwaway repo with one upstream
# commit and one fork-only file passes; deleting the upstream commit's root tree object then breaks
# the diff, while merge-base (which reads only commits) still works.
divergence="$(dirname "$checks")/check-divergence.sh"
run_divergence() { bash "$divergence" >/dev/null; }
mkdir "$tmp/divergence"
cd "$tmp/divergence"
git_q init -q
echo "upstream" > upstream.txt
git_q add upstream.txt
git_q commit -q -m "upstream"
git_q remote add upstream https://example.invalid/upstream.git
git_q update-ref refs/remotes/upstream/main HEAD
echo "fork" > fork.txt
git_q add fork.txt
git_q commit -q -m "fork"
expect 0 "check-divergence passes a fork-only file"   run_divergence
upstream_tree="$(git rev-parse 'refs/remotes/upstream/main^{tree}')"
rm -f -- ".git/objects/${upstream_tree:0:2}/${upstream_tree:2}"
expect 1 "check-divergence fails when git diff fails" run_divergence

echo "test-release-checks: $passed passed, $failed failed"
[[ "$failed" -eq 0 ]]
