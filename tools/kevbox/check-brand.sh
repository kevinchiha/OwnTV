#!/usr/bin/env bash
# KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
#
# AC-3 check: fail when a built APK still ships "OwnTV" in any string, plural or array value, in
# any configuration, apart from the names listed in tools/kevbox/brand-allowlist.txt. Resource
# names are never checked (owntv_icon_* is a name, not text).
#
#   bash tools/kevbox/check-brand.sh <apk>
#
# Uses the newest $ANDROID_HOME/build-tools/*/aapt2 (ANDROID_HOME defaults to
# /home/kevin/Android/Sdk). Prints each hit as "name [config]: value" and exits 1 if any remain.
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: bash tools/kevbox/check-brand.sh <apk>" >&2
  exit 2
fi
apk="$1"
if [[ ! -f "$apk" ]]; then
  echo "check-brand: no such APK: $apk" >&2
  exit 2
fi

android_home="${ANDROID_HOME:-/home/kevin/Android/Sdk}"
aapt2="$(ls -d "$android_home"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n 1 || true)"
if [[ -z "$aapt2" || ! -x "$aapt2" ]]; then
  echo "check-brand: no aapt2 under $android_home/build-tools" >&2
  exit 2
fi

allowlist="$(dirname "${BASH_SOURCE[0]}")/brand-allowlist.txt"
dump="$(mktemp)"
trap 'rm -f "$dump"' EXIT
"$aapt2" dump resources "$apk" > "$dump"

python3 - "$dump" "$allowlist" <<'PY'
import re
import sys

dump_path, allowlist_path = sys.argv[1], sys.argv[2]
allowed = set()
with open(allowlist_path, encoding="utf-8") as handle:
    for line in handle:
        name = line.split("#", 1)[0].strip()
        if name:
            allowed.add(name)

# aapt2 layout: "    resource 0x7f... type/name", then per configuration "      (config) value"
# (strings carry the value on that line; plurals and arrays list items on deeper lines).
resource_line = re.compile(r"^\s*resource 0x[0-9a-fA-F]+ ([^/\s]+)/(\S+)")
config_line = re.compile(r"^ {6}\(([^)]*)\)\s?(.*)$")
checked_types = {"string", "plurals", "array"}

hits = []
res_type = res_name = config = None
with open(dump_path, encoding="utf-8", errors="replace") as handle:
    for raw in handle:
        line = raw.rstrip("\n")
        match = resource_line.match(line)
        if match:
            res_type, res_name = match.groups()
            config = None
            continue
        if res_type not in checked_types or res_name in allowed:
            continue
        match = config_line.match(line)
        if match:
            config, value = match.group(1), match.group(2)
        else:
            value = line.strip()
        if "OwnTV" in value:
            hits.append(f"{res_name} [{config or 'default'}]: {value}")

for hit in hits:
    print(hit)
if hits:
    print(f"check-brand: {len(hits)} shipped value(s) still say OwnTV", file=sys.stderr)
    sys.exit(1)
print("check-brand: no OwnTV in shipped strings")
PY
