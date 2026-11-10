#!/usr/bin/env bash
# KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
#
# Builds the OpenVPN engine KevBox Live embeds (ics-openvpn, "OpenVPN for Android") from a pinned
# upstream tag and copies the result into extras/openvpn/. Gradle never runs this script: the app
# build only reads the committed AAR. Run it by hand when the engine needs a rebuild:
#
#   bash tools/kevbox/openvpn/build-engine.sh [work-folder]
#
# The work folder (default ~/.cache/kevbox-openvpn) holds the engine checkout and its git
# submodules, outside this repo. What it does:
#   1. clones ics-openvpn at TAG and checks the commit is COMMIT;
#   2. fetches only the submodules the OpenVPN 2.x build needs (openvpn, openssl, lz4, fmt);
#      OpenVPN 3, asio and mbed TLS are never built (OpenVPN 3 needs SWIG and adds size);
#   3. applies the patches next to this script, in name order:
#        0001-engine-as-library.patch       the app module becomes an Android library: library
#                                            plugin, no application id/version/signing, no
#                                            Application class, no android:process, only the
#                                            three ABIs we ship, the skeleton flavour
#                                            told to CMake and NativeUtils, catalog
#                                            names inline
#        0002-no-tv-toast-no-keepalive-job.patch
#                                            no status toasts on a TV, no keep-alive job
#   4. builds the skeleton + ovpn2 release AAR (native libraries stripped by AGP);
#   5. copies the AAR and the engine's licence into extras/openvpn/.
# extras/openvpn/SOURCE.txt records the same tag, commit, patches and this command.
#
# Needs: git, JDK 17+, the Android SDK (ANDROID_HOME or ~/Android/Sdk) with NDK 29.0.14206865 and
# CMake 3.22.1 (sdkmanager "ndk;29.0.14206865" "cmake;3.22.1").
set -euo pipefail

TAG="v0.7.64"
COMMIT="73ce3c99dde39207990d66c21be8201228cd1f09"
REPO_URL="https://github.com/schwabe/ics-openvpn.git"
SUBMODULES=(
  main/src/main/cpp/openvpn
  main/src/main/cpp/openssl
  main/src/main/cpp/lz4
  main/src/main/cpp/fmt
)
AAR_NAME="ics-openvpn-${TAG}-skeleton-ovpn2.aar"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../../.." && pwd)"
work="${1:-$HOME/.cache/kevbox-openvpn}"
src="$work/ics-openvpn-$TAG"
out="$repo_root/extras/openvpn"
sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"

mkdir -p "$work" "$out"

if [[ ! -d "$src/.git" ]]; then
  git clone --depth 1 --branch "$TAG" "$REPO_URL" "$src"
fi
actual="$(git -C "$src" rev-parse HEAD)"
if [[ "$actual" != "$COMMIT" ]]; then
  echo "build-engine: $TAG is $actual, expected $COMMIT" >&2
  exit 1
fi

# A clean tree every time, so the patches always apply to the pinned sources.
git -C "$src" checkout -- .
git -C "$src" submodule update --init --depth 50 "${SUBMODULES[@]}"

for patch in "$script_dir"/*.patch; do
  git -C "$src" apply --whitespace=nowarn "$patch"
done

echo "sdk.dir=$sdk" > "$src/local.properties"
(cd "$src" && ANDROID_HOME="$sdk" ./gradlew --no-daemon clean :main:assembleSkeletonOvpn2Release)

cp "$src/main/build/outputs/aar/main-skeleton-ovpn2-release.aar" "$out/$AAR_NAME"
cp "$src/doc/LICENSE.txt" "$out/LICENSE-ics-openvpn.txt"
echo "build-engine: wrote $out/$AAR_NAME"
