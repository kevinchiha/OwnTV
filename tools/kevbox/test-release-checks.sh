#!/usr/bin/env bash
# KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
#
# Test for the release pre-flight checks in tools/kevbox/release-checks.sh (AC-7's refusals).
# Builds a throwaway git repo under mktemp -d with tags v5.0.4, v5.1.0 and a later v5.1.0.1, runs
# every check against it and asserts the exit code of each case, then the argument rule, the
# early-user check, kevbox-silent.json and reading release.env (throwaway files), which need no repo. A second throwaway repo checks
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

# The app's error-code list against kevbox's copy, both in throwaway folders under $tmp.
mkdir -p tools/kevbox "$tmp/kevbox/src/lib"
echo '{"codes":["http_404"]}' > tools/kevbox/live-error-codes.json
cp tools/kevbox/live-error-codes.json "$tmp/kevbox/src/lib/live-error-codes.json"
expect 0 "equal error-code lists pass"               check_error_codes_match "$tmp/kevbox"
echo '{"codes":["http_404","http_403"]}' > "$tmp/kevbox/src/lib/live-error-codes.json"
expect 1 "different error-code lists refused"        check_error_codes_match "$tmp/kevbox"
rm "$tmp/kevbox/src/lib/live-error-codes.json"
expect 0 "a missing kevbox list warns and passes"    check_error_codes_match "$tmp/kevbox"
warning="$(check_error_codes_match "$tmp/kevbox" 2>&1 >/dev/null)"
expect 0 "the missing-list case prints a warning"    test -n "$warning"

expect 0 "no Sentry token needs no org or project"   check_sentry_settings "" "" ""
expect 0 "token with org and project passes"         check_sentry_settings tok org proj
expect 1 "token without org refused"                 check_sentry_settings tok "" proj
expect 1 "token without project refused"             check_sentry_settings tok org ""

# release.sh's arguments: --no-silent counts only first; any other --option stops the script.
expect 0 "version alone passes"                      check_release_args 5.1.0.2
expect 0 "version and notes pass"                    check_release_args 5.1.0.2 "Fixes the guide" more
expect 0 "--no-silent first passes"                  check_release_args --no-silent 5.1.0.2 notes
expect 0 "notes holding a dash pass"                 check_release_args 5.1.0.2 "guide - fixed"
expect 0 "no arguments pass (usage is release.sh's)" check_release_args
expect 1 "--no-silent after the version refused"     check_release_args 5.1.0.2 --no-silent
expect 1 "--no-silent after the notes refused"       check_release_args 5.1.0.2 notes --no-silent
expect 1 "--no-silent twice refused"                 check_release_args --no-silent --no-silent 5.1.0.2
expect 1 "unknown option first refused"              check_release_args --draft 5.1.0.2
expect 1 "unknown option after the version refused"  check_release_args 5.1.0.2 --draft
expect 1 "unknown option after --no-silent refused"  check_release_args --no-silent --draft 5.1.0.2
expect 1 "a version like -h refused"                 check_release_args -h
expect 1 "a version like -x after --no-silent refused" check_release_args --no-silent -x
args_message="$(check_release_args 5.1.0.2 notes --no-silent 2>&1 >/dev/null || true)"
expect 0 "the message names the argument position"   grep -q 'argument 3' <<<"$args_message"

# SILENT_EARLY_USERS: Supabase user ids (UUIDs). A refusal names the position, never the value.
u1="0f8fad5b-d9cb-469f-a165-70867728950e"
u2="7C9E6679-7425-40DE-944B-E07FC1F90AE7"
expect 0 "no early users pass"                       check_early_users ""
expect 0 "blank early users pass"                    check_early_users "   "
expect 0 "one id passes"                             check_early_users "$u1"
expect 0 "two ids with spaces, upper case, pass"     check_early_users " $u1 , $u2 "
expect 1 "an email refused"                          check_early_users "$u1,kevin@example.com"
expect 1 "an empty entry refused"                    check_early_users "$u1,,$u2"
expect 1 "a trailing comma refused"                  check_early_users "$u1,"
expect 1 "a short id refused"                        check_early_users "${u1%?}"
expect 1 "a non-hex id refused"                      check_early_users "${u1/0f/0g}"
expect 1 "an id without dashes refused"              check_early_users "${u1//-/}"
users_message="$(check_early_users "$u1,kevin@example.com" 2>&1 >/dev/null || true)"
expect 0 "the message names the entry position"      grep -q 'entry 2' <<<"$users_message"
expect 1 "the message never prints the value"        grep -q 'example' <<<"$users_message"

# kevbox-silent.json: {"after": now + 24 h, "earlyUsers": [...]}, nothing else.
# json_is <json> <expected json>: equal once parsed, and "after" a JSON integer.
json_is() {
  python3 -c 'import json, sys
got, want = json.loads(sys.argv[1]), json.loads(sys.argv[2])
assert type(got["after"]) is int and got == want' "$1" "$2"
}
valid_json() { python3 -m json.tool >/dev/null; }
two_ids="$(render_silent_json 1793190452000 " $u1 , $u2 ")"
expect 0 "two ids: valid JSON"                       valid_json <<<"$two_ids"
expect 0 "two ids: after is now + 24 h, ids trimmed" json_is "$two_ids" "{\"after\": 1793276852000, \"earlyUsers\": [\"$u1\", \"$u2\"]}"
blank_ids="$(render_silent_json 1793190452000 "")"
expect 0 "blank: valid JSON"                         valid_json <<<"$blank_ids"
expect 0 "blank: earlyUsers is empty"                json_is "$blank_ids" '{"after": 1793276852000, "earlyUsers": []}'
expect 1 "an email is refused, no JSON"              render_silent_json 1793190452000 "kevin@example.com"
expect 1 "a time that is not a number is refused"    render_silent_json soon "$u1"
expect 1 "a missing time is refused"                 render_silent_json "" "$u1"

# read_early_users: release.env sourced in a subshell; a file that cannot be sourced stops the
# release instead of reading as "no early users", and none of its lines or values is printed.
envs="$tmp/envs"
mkdir "$envs"
printf 'NUVIO_RELEASE_KEY_PASSWORD=hunter2\nSILENT_EARLY_USERS=" %s , %s "\n' "$u1" "$u2" > "$envs/ok.env"
printf 'NUVIO_RELEASE_KEY_PASSWORD=hunter2\n' > "$envs/no-line.env"
printf 'NUVIO_RELEASE_KEY_PASSWORD=hunter2)\nSILENT_EARLY_USERS=%s\n' "$u1" > "$envs/syntax.env"
printf 'NUVIO_RELEASE_KEY_PASSWORD=(hunter2\nSILENT_EARLY_USERS=%s\n' "$u1" > "$envs/unclosed.env"
printf 'echo hunter2\nSILENT_EARLY_USERS=%s\n' "$u1" > "$envs/noisy.env"
printf 'SILENT_EARLY_USERS=%s\n' "$u1" > "$envs/unreadable.env"
chmod 000 "$envs/unreadable.env"
read_is() { [[ "$(read_early_users "$1")" == "$2" ]]; }
expect 0 "env: the line is read as written"          read_is "$envs/ok.env" " $u1 , $u2 "
expect 0 "env: a missing line reads as blank"        read_is "$envs/no-line.env" ""
expect 0 "env: the file's own output is dropped"     read_is "$envs/noisy.env" "$u1"
expect 1 "env: a syntax error refused"               read_early_users "$envs/syntax.env"
expect 1 "env: an unclosed quote refused"            read_early_users "$envs/unclosed.env"
expect 1 "env: a missing file refused"               read_early_users "$envs/absent.env"
# Root reads a mode-000 file anyway; there the case says nothing.
if [[ ! -r "$envs/unreadable.env" ]]; then
  expect 1 "env: an unreadable file refused"         read_early_users "$envs/unreadable.env"
fi
env_output="$( { read_early_users "$envs/syntax.env"; read_early_users "$envs/ok.env"; } 2>&1 || true)"
expect 1 "env: no value is ever printed"             grep -q 'hunter2' <<<"$env_output"
expect 0 "env: the refusal names the file"           grep -q 'syntax.env could not be sourced' <<<"$env_output"
read_early_users "$envs/ok.env" >/dev/null
expect 0 "env: the signing values stay in the subshell" test -z "${NUVIO_RELEASE_KEY_PASSWORD:-}"
# Exactly how release.sh calls it: the || after the substitution must fire.
release_read() { local users; users="$(read_early_users "$1")" || return 1; echo "$users" >/dev/null; }
expect 1 "env: release.sh's call stops on a syntax error" release_read "$envs/syntax.env"
expect 0 "env: release.sh's call passes a good file"  release_read "$envs/ok.env"

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
