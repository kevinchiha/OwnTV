#!/usr/bin/env bash
# KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
# KevBox: the four-number version rule in plain bash, so release.sh can use it before any build.
# A release is A.B.C.K (A, B, C in 0..99, K in 1..99, no leading zeros); its code is
# A*1000000 + B*10000 + C*100 + K. Kept in step with
# app/src/main/java/tv/own/owntv/kevbox/release/KevBoxVersion.kt by KevBoxVersionTest.
#
#   tools/kevbox/version.sh code <A.B.C.K>   prints the version code
#   tools/kevbox/version.sh base <A.B.C.K>   prints A.B.C
# Both exit 2 with a message on stderr when the version is invalid.
set -euo pipefail

usage() {
  echo "usage: $0 code|base <A.B.C.K>" >&2
  exit 2
}

[ "$#" -eq 2 ] || usage
cmd=$1
name=$2

segment='(0|[1-9][0-9]?)'
if [[ ! "$name" =~ ^${segment}\.${segment}\.${segment}\.${segment}$ ]]; then
  echo "invalid KevBox version '$name': expected A.B.C.K with A, B, C in 0..99 and K in 1..99" >&2
  exit 2
fi
a=${BASH_REMATCH[1]}
b=${BASH_REMATCH[2]}
c=${BASH_REMATCH[3]}
k=${BASH_REMATCH[4]}
if [ "$k" -lt 1 ]; then
  echo "invalid KevBox version '$name': K must be 1..99" >&2
  exit 2
fi

case "$cmd" in
  code) echo $((a * 1000000 + b * 10000 + c * 100 + k)) ;;
  base) echo "$a.$b.$c" ;;
  *) usage ;;
esac
