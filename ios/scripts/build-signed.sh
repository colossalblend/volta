#!/usr/bin/env bash
# A properly signed IPA, built when the repository has the signing secrets:
#
#   IOS_CERT_P12_BASE64     the signing certificate with its private key (.p12), base64
#   IOS_CERT_PASSWORD       the .p12 password
#   IOS_APP_PROFILE_BASE64  provisioning profile of the app, base64
#   IOS_EXT_PROFILE_BASE64  provisioning profile of the VPN extension, base64
#   IOS_TEAM_ID             the ten-character team ID (read from the profile if empty)
#
# Both profiles need the Network Extensions capability (Packet Tunnel) and one App
# Group. Bundle IDs and the group come from the profiles, so a fork can sign with its
# own IDs without editing the project. The export method follows the profile type.
#
#   ios/scripts/build-signed.sh out/volta-ios.ipa
set -euo pipefail

OUT_IPA="${1:?output .ipa}"
mkdir -p "$(dirname "$OUT_IPA")"
OUT_IPA="$(cd "$(dirname "$OUT_IPA")" && pwd)/$(basename "$OUT_IPA")"
TMP="${RUNNER_TEMP:-$(mktemp -d)}"
LOG="$TMP/signed.log"
W="$TMP/signing"
rm -rf "$W"; mkdir -p "$W"

KC="$W/volta-signing.keychain-db"
KCPW="$(uuidgen)"
cleanup() {
  security delete-keychain "$KC" > /dev/null 2>&1 || true
  rm -f "$W"/*.p12 "$W"/*.mobileprovision
}
trap cleanup EXIT

printf '%s' "$IOS_CERT_P12_BASE64" | base64 --decode > "$W/cert.p12"
printf '%s' "$IOS_APP_PROFILE_BASE64" | base64 --decode > "$W/app.mobileprovision"
printf '%s' "$IOS_EXT_PROFILE_BASE64" | base64 --decode > "$W/ext.mobileprovision"

# A keychain of its own for the certificate, unlocked for codesign.
security create-keychain -p "$KCPW" "$KC"
security set-keychain-settings -lut 21600 "$KC"
security unlock-keychain -p "$KCPW" "$KC"
security import "$W/cert.p12" -k "$KC" -P "${IOS_CERT_PASSWORD:-}" -A -t cert -f pkcs12
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KCPW" "$KC" > /dev/null
# shellcheck disable=SC2046
security list-keychains -d user -s "$KC" $(security list-keychains -d user | sed 's/"//g')
IDENTITY="$(security find-identity -v -p codesigning "$KC" | awk 'NR==1 && $2 ~ /^[0-9A-F]{40}$/ {print $2}')"
if [ -z "$IDENTITY" ]; then
  echo "::error::no code signing identity in IOS_CERT_P12_BASE64 (wrong password, or a certificate without its private key)"
  exit 1
fi
security find-identity -v -p codesigning "$KC" | head -3

pb() { /usr/libexec/PlistBuddy -c "Print :$2" "$1" 2>/dev/null || true; }
security cms -D -i "$W/app.mobileprovision" > "$W/app.plist"
security cms -D -i "$W/ext.mobileprovision" > "$W/ext.plist"
APP_UUID="$(pb "$W/app.plist" UUID)"
EXT_UUID="$(pb "$W/ext.plist" UUID)"
TEAM="${IOS_TEAM_ID:-$(pb "$W/app.plist" TeamIdentifier:0)}"
APP_ID="$(pb "$W/app.plist" Entitlements:application-identifier)"; APP_ID="${APP_ID#*.}"
EXT_ID="$(pb "$W/ext.plist" Entitlements:application-identifier)"; EXT_ID="${EXT_ID#*.}"
GROUP="$(pb "$W/app.plist" Entitlements:com.apple.security.application-groups:0)"
# A wildcard profile (TEAM.*) names no bundle: keep Volta's own IDs then.
case "$APP_ID" in *'*'*|'') APP_ID=com.ars3nb.volta ;; esac
case "$EXT_ID" in *'*'*|'') EXT_ID="$APP_ID.tunnel" ;; esac
[ -n "$GROUP" ] || GROUP="group.$APP_ID"

if [ -z "$(pb "$W/app.plist" Entitlements:com.apple.developer.networking.networkextension:0)" ]; then
  echo "::warning title=Signed IPA::the app profile has no Network Extensions entitlement; the VPN will not start"
fi

for DIR in "$HOME/Library/MobileDevice/Provisioning Profiles" "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"; do
  mkdir -p "$DIR"
  cp "$W/app.mobileprovision" "$DIR/$APP_UUID.mobileprovision"
  cp "$W/ext.mobileprovision" "$DIR/$EXT_UUID.mobileprovision"
done

if [ "$(pb "$W/app.plist" ProvisionsAllDevices)" = true ]; then
  METHOD=enterprise
elif [ -n "$(pb "$W/app.plist" ProvisionedDevices:0)" ]; then
  if [ "$(pb "$W/app.plist" Entitlements:get-task-allow)" = true ]; then METHOD=debugging; else METHOD=release-testing; fi
else
  METHOD=app-store-connect
fi
echo "Signing $APP_ID + $EXT_ID, group $GROUP, team $TEAM, method $METHOD"

BEAUTIFY=cat; command -v xcbeautify > /dev/null && BEAUTIFY="xcbeautify --quiet --disable-logging"
set -o pipefail
xcodebuild archive \
  -project ios/Volta.xcodeproj -scheme Volta -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath "$W/signed.xcarchive" \
  -skipPackagePluginValidation -skipMacroValidation \
  MARKETING_VERSION="${VOLTA_MARKETING:-1.0.0}" CURRENT_PROJECT_VERSION="${VOLTA_BUILD:-1}" \
  CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM="$TEAM" CODE_SIGN_IDENTITY="$IDENTITY" \
  VOLTA_BUNDLE_ID="$APP_ID" VOLTA_EXT_BUNDLE_ID="$EXT_ID" VOLTA_APP_GROUP="$GROUP" \
  VOLTA_APP_PROFILE="$APP_UUID" VOLTA_EXT_PROFILE="$EXT_UUID" \
  OTHER_CODE_SIGN_FLAGS="--keychain $KC" \
  2>&1 | tee "$LOG" | $BEAUTIFY

cat > "$W/export.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>method</key><string>$METHOD</string>
	<key>teamID</key><string>$TEAM</string>
	<key>signingStyle</key><string>manual</string>
	<key>signingCertificate</key><string>$IDENTITY</string>
	<key>provisioningProfiles</key>
	<dict>
		<key>$APP_ID</key><string>$APP_UUID</string>
		<key>$EXT_ID</key><string>$EXT_UUID</string>
	</dict>
	<key>stripSwiftSymbols</key><true/>
	<key>destination</key><string>export</string>
</dict>
</plist>
EOF

xcodebuild -exportArchive \
  -archivePath "$W/signed.xcarchive" \
  -exportOptionsPlist "$W/export.plist" \
  -exportPath "$W/export" \
  2>&1 | tee -a "$LOG" | $BEAUTIFY

IPA_FILE="$(find "$W/export" -name '*.ipa' | head -1)"
[ -n "$IPA_FILE" ] || { echo "::error::the export produced no IPA"; exit 1; }
cp "$IPA_FILE" "$OUT_IPA"
ls -la "$OUT_IPA"
