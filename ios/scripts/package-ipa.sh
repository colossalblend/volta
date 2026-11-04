#!/usr/bin/env bash
# Turns an archive built with CODE_SIGNING_ALLOWED=NO into an IPA:
# Payload/Volta.app (with PlugIns/PacketTunnel.appex), zipped.
#
#   ios/scripts/package-ipa.sh build/Volta.xcarchive out/volta-ios-unsigned.ipa
#
# Both bundles get an ad-hoc signature ("-": no certificate) that carries their
# entitlements, the VPN extension and the app group. iOS still refuses to install it as
# it is; it has to be re-signed (AltStore, Sideloadly, a developer certificate), and the
# re-signing tools take the entitlements from this signature. TrollStore installs it
# directly and keeps them.
set -euo pipefail

ARCHIVE="${1:?xcarchive}"
IPA="${2:?output .ipa}"
mkdir -p "$(dirname "$IPA")"
IPA="$(cd "$(dirname "$IPA")" && pwd)/$(basename "$IPA")"

APP="$ARCHIVE/Products/Applications/Volta.app"
if [ ! -d "$APP" ]; then
  echo "::error::Volta.app is not in the archive"
  find "$ARCHIVE" -maxdepth 4 | head -40
  exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/Payload"
cp -R "$APP" "$WORK/Payload/"
APPDIR="$WORK/Payload/Volta.app"
EXT="$APPDIR/PlugIns/PacketTunnel.appex"
if [ ! -d "$EXT" ]; then
  echo "::error::the VPN extension (PlugIns/PacketTunnel.appex) is missing from Volta.app"
  exit 1
fi

pb() { /usr/libexec/PlistBuddy -c "Print :$2" "$1" 2>/dev/null || true; }
GROUP="$(pb "$APPDIR/Info.plist" VoltaAppGroup)"
[ -n "$GROUP" ] || GROUP="group.$(pb "$APPDIR/Info.plist" CFBundleIdentifier)"

cat > "$WORK/entitlements.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>com.apple.developer.networking.networkextension</key>
	<array>
		<string>packet-tunnel-provider</string>
	</array>
	<key>com.apple.security.application-groups</key>
	<array>
		<string>$GROUP</string>
	</array>
</dict>
</plist>
EOF

# Inside out: the extension first, then the app that contains it.
codesign --force --sign - --timestamp=none --generate-entitlement-der \
  --entitlements "$WORK/entitlements.plist" "$EXT"
codesign --force --sign - --timestamp=none --generate-entitlement-der \
  --entitlements "$WORK/entitlements.plist" "$APPDIR"
codesign -d --entitlements - "$APPDIR" 2>/dev/null | head -20 || true

rm -f "$IPA"
(cd "$WORK" && zip -qry "$IPA" Payload)
ls -la "$IPA"
unzip -l "$IPA" | grep -E 'Volta$|PacketTunnel$|Info.plist$' || true
