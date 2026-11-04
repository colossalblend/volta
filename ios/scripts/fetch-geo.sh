#!/usr/bin/env bash
# The geo lists the app ships, the same v2fly lists the Android core carries: geosite
# (domain-list-community) whole, geoip without the few country lists too big for the VPN
# extension (see tools/geo_trim.py). Into ios/Geo/, which project.yml bundles if present.
#
#   ios/scripts/fetch-geo.sh [ios/Geo]
set -euo pipefail
OUT="${1:-$(dirname "$0")/../Geo}"
mkdir -p "$OUT"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fetch() {
  for i in 1 2 3; do
    curl -fsSL --retry 3 -o "$2" "$1" && return 0
    sleep 5
  done
  return 1
}
fetch https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat "$WORK/geosite.dat"
fetch https://github.com/v2fly/geoip/releases/latest/download/geoip.dat "$WORK/geoip.dat"
cp "$WORK/geosite.dat" "$OUT/geosite.dat"
python3 "$(dirname "$0")/../tools/geo_trim.py" "$WORK/geoip.dat" "$OUT/geoip.dat" 1200000
python3 "$(dirname "$0")/../tools/geo_trim.py" "$OUT/geosite.dat" "$OUT/geosite.dat.tmp" 1200000 && mv "$OUT/geosite.dat.tmp" "$OUT/geosite.dat"
ls -la "$OUT"
