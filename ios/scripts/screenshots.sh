#!/usr/bin/env bash
# Pictures of the screens: builds the app for the simulator, starts it with the sample
# data of Demo.swift and takes a screenshot of each screen and state.
#
#   ios/scripts/screenshots.sh out/screens
#
# Run after `xcodegen generate` and with ios/Frameworks/LibXray.xcframework in place.
set -uo pipefail

OUT="${1:?output directory}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
DD="${RUNNER_TEMP:-/tmp}/volta-sim"

if ! xcodebuild build \
  -project ios/Volta.xcodeproj -scheme Volta -configuration Debug \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath "$DD" \
  -skipPackagePluginValidation -skipMacroValidation \
  CODE_SIGNING_ALLOWED=NO ARCHS="$(uname -m)" ONLY_ACTIVE_ARCH=NO > "$OUT/build.log" 2>&1; then
  grep -E "error:|Undefined|ld:" "$OUT/build.log" | head -40
  echo "::warning title=Screenshots::the simulator build failed (see build.log in the ios-screens artifact)"
  exit 1
fi

APP="$(find "$DD/Build/Products" -maxdepth 2 -name Volta.app -type d | head -1)"
[ -d "$APP" ] || { echo "::warning title=Screenshots::no Volta.app"; exit 1; }
BUNDLE="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$APP/Info.plist")"

UDID="$(xcrun simctl list devices available -j | python3 -c '
import json, re, sys
devices = json.load(sys.stdin)["devices"]
best = None
for runtime, items in devices.items():
    m = re.search(r"iOS-(\d+)-(\d+)", runtime)
    if not m:
        continue
    version = (int(m.group(1)), int(m.group(2)))
    for d in items:
        name = d.get("name", "")
        if not d.get("isAvailable", True) or not name.startswith("iPhone"):
            continue
        plain = name.count(" ") == 2 and not name.endswith(("Max", "Plus", "Pro", "e", "mini"))
        key = (version, plain, "Pro" in name and "Max" not in name, name)
        if best is None or key > best[0]:
            best = (key, d["udid"], name, runtime)
if best:
    print(best[1])
    print(best[2], best[3], file=sys.stderr)
')"
[ -n "$UDID" ] || { echo "::warning title=Screenshots::no iPhone simulator"; exit 1; }

xcrun simctl boot "$UDID" 2>/dev/null || true
xcrun simctl bootstatus "$UDID" -b > /dev/null
xcrun simctl status_bar "$UDID" override --time "9:41" --dataNetwork wifi --wifiMode active --wifiBars 3 \
  --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100 2>/dev/null || true
xcrun simctl install "$UDID" "$APP"

shot() {
  local name="$1"
  shift
  xcrun simctl terminate "$UDID" "$BUNDLE" > /dev/null 2>&1 || true
  xcrun simctl launch "$UDID" "$BUNDLE" -volta-demo "$@" > /dev/null
  sleep 5
  xcrun simctl io "$UDID" screenshot --type=png "$OUT/$name.png" > /dev/null 2>&1 && echo "shot $name"
}

RU=(-AppleLanguages "(ru)" -AppleLocale ru_RU)
shot 1-home-off -volta-tab home "${RU[@]}"
shot 2-home-connecting -volta-tab home -volta-state connecting "${RU[@]}"
shot 3-home-on -volta-tab home -volta-state connected "${RU[@]}"
shot 4-servers -volta-tab servers "${RU[@]}"
shot 5-subscriptions -volta-tab subscriptions "${RU[@]}"
shot 6-add -volta-tab import "${RU[@]}"
shot 7-settings -volta-tab settings "${RU[@]}"
shot 8-home-error -volta-tab home -volta-state error "${RU[@]}"
shot 9-home-light -volta-tab home -volta-state connected -volta-theme light "${RU[@]}"
shot 10-servers-ocean -volta-tab servers -volta-theme ocean "${RU[@]}"
shot 11-home-en -volta-tab home -volta-state connected -AppleLanguages "(en)" -AppleLocale en_US
shot 12-settings-fa -volta-tab settings -AppleLanguages "(fa)" -AppleLocale fa_IR
xcrun simctl terminate "$UDID" "$BUNDLE" > /dev/null 2>&1 || true
ls -la "$OUT"
