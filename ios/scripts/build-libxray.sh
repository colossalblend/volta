#!/usr/bin/env bash
# Builds LibXray.xcframework (Xray-core for iOS) with libXray's own build script and
# gomobile, at a pinned libXray release, into the directory given as the second argument.
#
#   ios/scripts/build-libxray.sh v26.10.31 ios/Frameworks
#
# Only the iOS device and simulator slices are built: Volta has no macOS or Mac Catalyst
# target, and each extra slice is several more minutes of Go compiling.
set -euo pipefail

VERSION="${1:?libXray tag, e.g. v26.10.31}"
OUT="$(mkdir -p "${2:?output directory}" && cd "$2" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

export GOTOOLCHAIN="${GOTOOLCHAIN:-auto}"
export PATH="$(go env GOPATH)/bin:$PATH"

git clone --quiet --depth 1 --branch "$VERSION" https://github.com/XTLS/libXray "$WORK/libXray"
cd "$WORK/libXray"
echo "libXray $(git rev-parse HEAD)"

SCRIPT=build/app/apple_gomobile.py
# iOS device + simulator only.
sed -i '' 's/"ios,iossimulator,macos,maccatalyst"/"ios,iossimulator"/' "$SCRIPT"
grep -q '"ios,iossimulator"' "$SCRIPT" || { echo "::error::could not narrow the gomobile targets"; exit 1; }
# Smaller binaries: no symbol table or DWARF in the Go part.
sed -i '' 's/"-iosversion",/"-ldflags=-s -w", "-iosversion",/' "$SCRIPT"
grep -n '"-target"' -A8 "$SCRIPT"

python3 build/main.py apple gomobile

test -d LibXray.xcframework || { echo "::error::LibXray.xcframework was not produced"; exit 1; }
rm -rf "$OUT/LibXray.xcframework"
cp -R LibXray.xcframework "$OUT/"
echo "$VERSION" > "$OUT/LibXray.version"

# What Swift will see, for the build log.
find "$OUT/LibXray.xcframework" -maxdepth 2 -print
HEADER=$(find "$OUT/LibXray.xcframework" -name 'LibXray.objc.h' | head -1)
[ -n "$HEADER" ] && grep -n 'FOUNDATION_EXPORT' "$HEADER" || true
du -sh "$OUT/LibXray.xcframework"
