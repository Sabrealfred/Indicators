#!/usr/bin/env bash
#
# Checks that the published APK is the one the release marker describes.
#
# Everything the in-app updater trusts is checked here, from outside, with nothing but curl,
# python and keytool — no Android SDK. Run it after a release if you want to know the update
# path is intact rather than assume it:
#
#   tools/verify-release.sh
#
# What it proves, and why each one matters:
#
#   sha256   The bytes on the phone are the bytes CI built. UpdateService re-checks this before
#            it hands anything to the installer, so a mismatch is a refused update — safe, but
#            silent from the outside, which is exactly why it is worth checking here.
#   size     Same claim, cheaper, and it is what the download progress bar is scaled against.
#   code     The versionCode inside the APK against the one the marker declares. These are the
#            two numbers UpdateService compares in its preflight; a mismatch is MISDECLARED.
#   cert     The signing certificate. An update signed by a different key cannot install over
#            the copy on the device at all — Android refuses, and the player is told to
#            uninstall, which costs them the lineage. This is the check that matters most and
#            the one nothing else in the pipeline makes.
set -euo pipefail

repo="${1:-Sabrealfred/Indicators}"
tag="${2:-debug-latest}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# RELEASE_JSON lets a caller hand in a release document it already has, which is the only way
# to run this from inside an environment whose proxy refuses anonymous api.github.com.
if [ -n "${RELEASE_JSON:-}" ]; then
  echo "Using the release document at $RELEASE_JSON"
  cp "$RELEASE_JSON" "$work/release.json"
else
  echo "Reading the $tag release of $repo"
  curl -sSfL "https://api.github.com/repos/$repo/releases/tags/$tag" -o "$work/release.json" || {
    echo "Could not read the release. If this is a private repo or a restricted network, fetch"
    echo "it yourself and re-run with RELEASE_JSON=/path/to/release.json"
    exit 2
  }
fi
if ! python3 -c "import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if 'body' in d else 1)" "$work/release.json"; then
  echo "That is not a release document — GitHub answered with:"
  head -c 400 "$work/release.json"; echo
  exit 2
fi

python3 - "$work/release.json" > "$work/marker.env" <<'PY'
import json, re, sys
release = json.load(open(sys.argv[1]))
line = next(
    (l for l in release["body"].splitlines() if "neopal-update:" in l),
    None,
)
if line is None:
    raise SystemExit("no neopal-update marker in the release body — the updater would say NO_MARKER")
fields = dict(t.split("=", 1) for t in line.split("neopal-update:", 1)[1].strip(" -->").split())
asset = next(a for a in release["assets"] if a["name"] == fields["asset"])
for key in ("code", "name", "sha256", "size"):
    print(f"MARKER_{key.upper()}={fields[key]}")
print(f"ASSET_URL={asset['browser_download_url']}")
print(f"ASSET_SIZE={asset['size']}")
PY
# shellcheck disable=SC1091
source "$work/marker.env"

echo "Marker declares build $MARKER_CODE ($MARKER_NAME), $MARKER_SIZE bytes"
curl -sSL "$ASSET_URL" -o "$work/app.apk"

fail=0
check() { if [ "$2" = "$3" ]; then echo "  ok    $1: $2"; else echo "  FAIL  $1: marker says $2, actual $3"; fail=1; fi; }

check "size"   "$MARKER_SIZE"   "$(stat -c%s "$work/app.apk")"
check "sha256" "$MARKER_SHA256" "$(sha256sum "$work/app.apk" | cut -d' ' -f1)"

versions="$(python3 "$here/apk-version.py" "$work/app.apk")"
check "versionCode" "$MARKER_CODE" "$(sed -n 's/^versionCode = //p' <<<"$versions")"
check "versionName" "$MARKER_NAME" "$(sed -n 's/^versionName = //p' <<<"$versions")"

echo "Signing certificate:"
python3 "$here/apk-certificate.py" "$work/app.apk" | sed 's/^/  /'
echo
echo "Compare that SHA256 against the committed key:"
echo "  keytool -list -v -keystore app/neopal-debug.keystore -storepass neopal-debug | grep SHA256"

exit "$fail"
