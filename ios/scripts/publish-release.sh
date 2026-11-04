#!/usr/bin/env bash
# Puts the iOS IPAs on GitHub Releases, the way the Android workflow publishes APKs:
#  - a v… tag: the release of that tag;
#  - a branch: its rolling pre-release apk-<branch>.
# Only *-ios*.ipa files and the iOS part of the notes are touched. If the release does
# not exist yet it is created as a pre-release; the Android build, when it comes, takes
# it over (uploads its APKs and update.json, marks a tag release as the latest).
#
#   ios/scripts/publish-release.sh <dir with the .ipa files>
# Needs GH_TOKEN, GH_REPO and the VOLTA_* variables from the workflow.
set -euo pipefail

OUT="${1:?directory with the IPAs}"
TAG="$VOLTA_TAG"
SHA="${GITHUB_SHA:?}"
shopt -s nullglob
FILES=("$OUT"/*.ipa)
[ ${#FILES[@]} -gt 0 ] || { echo "::error::no IPA to publish"; exit 1; }

UNSIGNED="$VOLTA_STEM-ios-unsigned.ipa"
SIGNED_NAME="$VOLTA_STEM-ios.ipa"

# What the app's «Проверка обновлений» reads (Shared/Updates.swift): this build's number
# (100 + run number, like Android's versionCode) and the IPA to point at.
IPA_NAME="$UNSIGNED"; SIGNED_FLAG=false
if [ -f "$OUT/$SIGNED_NAME" ]; then IPA_NAME="$SIGNED_NAME"; SIGNED_FLAG=true; fi
python3 - "$OUT/ios-update.json" <<PY
import json, sys
json.dump({
    "versionCode": int("$VOLTA_BUILD"), "versionName": "$VOLTA_VERSION", "tag": "$TAG",
    "commit": "$SHA", "branch": "$VOLTA_BRANCH", "xray": "${LIBXRAY_VERSION:-}",
    "ipa": "$IPA_NAME", "signed": $([ "$SIGNED_FLAG" = true ] && echo True || echo False),
}, open(sys.argv[1], "w"), indent=2)
PY
cat "$OUT/ios-update.json"
FILES+=("$OUT/ios-update.json")
NOTES="$(mktemp)"
{
  echo "<!-- volta-ios -->"
  echo "### iOS"
  echo
  if [ -f "$OUT/$SIGNED_NAME" ]; then
    echo "- **\`$SIGNED_NAME\`** — подписанная сборка, ставится на iPhone из списка профиля."
  fi
  echo "- \`$UNSIGNED\` — сборка без подписи. iPhone не установит её как есть: сначала её нужно подписать (AltStore, Sideloadly и похожие программы)."
  echo
  echo "VPN на iPhone работает только в сборке, подписанной платным аккаунтом разработчика Apple (Apple Developer Program): бесплатный Apple ID не даёт приложению права на VPN, и с ним Volta откроется, но не подключится."
  echo "Подробности — в [ios/README.md](https://github.com/$GH_REPO/blob/$SHA/ios/README.md)."
  echo
  echo "iOS $VOLTA_VERSION (сборка $VOLTA_BUILD) из коммита $SHA. Ядро: LibXray \`$LIBXRAY_VERSION\` (Xray-core)."
  echo "<!-- /volta-ios -->"
} > "$NOTES"

if gh release view "$TAG" > /dev/null 2>&1; then
  ASSETS="$(gh release view "$TAG" --json assets -q '.assets[].name')"
  OTHERS="$(grep -v -E -- '-ios(-unsigned|-screens)?\.(ipa|zip)$|^ios-update\.json$' <<<"$ASSETS" || true)"
  if [ "$VOLTA_STABLE" != yes ] && [ -z "$OTHERS" ]; then
    # Nothing but earlier iOS builds: start the rolling pre-release over at this commit.
    gh release delete "$TAG" --cleanup-tag --yes
  else
    # Earlier iOS builds of other commits go; the Android files stay.
    while IFS= read -r name; do
      [ -n "$name" ] || continue
      if [[ "$name" =~ -ios(-unsigned)?\.ipa$|-ios-screens\.zip$ ]] && [ ! -f "$OUT/$name" ]; then
        gh release delete-asset "$TAG" "$name" --yes || true
      fi
    done <<<"$ASSETS"
    gh release upload "$TAG" "${FILES[@]}" --clobber
    BODY="$(mktemp)"
    gh release view "$TAG" --json body -q .body > "$BODY"
    python3 - "$BODY" "$NOTES" <<'PY'
import re, sys
body = open(sys.argv[1], encoding="utf-8").read()
ios = open(sys.argv[2], encoding="utf-8").read().strip()
body = re.sub(r"\n*<!-- volta-ios -->.*?<!-- /volta-ios -->\n*", "\n", body, flags=re.S).rstrip()
open(sys.argv[1], "w", encoding="utf-8").write((body + "\n\n" if body else "") + ios + "\n")
PY
    gh release edit "$TAG" --notes-file "$BODY"
    echo "### iOS: https://github.com/$GH_REPO/releases/tag/$TAG" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
    exit 0
  fi
fi

if [ "$VOLTA_STABLE" = yes ]; then
  TITLE="Volta $VOLTA_VERSION"
  CREATE=(--verify-tag)
else
  TITLE="Volta $VOLTA_VERSION · $VOLTA_BRANCH · $VOLTA_SHORT (тестовая)"
  CREATE=(--target "$SHA")
fi
if ! gh release create "$TAG" "${FILES[@]}" "${CREATE[@]}" --prerelease --title "$TITLE" --notes-file "$NOTES"; then
  # The Android build made it in the meantime.
  sleep 15
  gh release upload "$TAG" "${FILES[@]}" --clobber
fi
echo "### iOS: https://github.com/$GH_REPO/releases/tag/$TAG" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
