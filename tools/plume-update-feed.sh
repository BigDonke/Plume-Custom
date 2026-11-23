#!/bin/sh
set -eu

fail() { printf '%s\n' "$*" >&2; exit 1; }

[ "$#" -eq 1 ] || fail "Usage: sh tools/plume-update-feed.sh FEED_DIRECTORY"
repo=BigDonke/Plume-Custom
build_tools=${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}/build-tools/36.0.0
expected_cert=${PLUME_UPDATE_CERT_SHA256:?Set PLUME_UPDATE_CERT_SHA256 to the installed APK signing certificate digest}
expected_cert=$(printf '%s' "$expected_cert" | tr '[:upper:]' '[:lower:]')
printf '%s\n' "$expected_cert" | grep -Eq '^[0-9a-f]{64}$' || fail "Invalid signing certificate digest"

feed=$1
mkdir -p "$feed"
# Stage in a fresh temp directory, not in the feed: the feed is often the web root, and a run that is killed
# must not leave anything there that blocks the next run.
staging=$(mktemp -d "${TMPDIR:-/tmp}/plume-feed.XXXXXX") || fail "Cannot create a staging directory"
trap 'rm -rf "$staging"' EXIT
trap 'exit 1' HUP INT TERM

tag=$(gh api --hostname github.com "repos/$repo/releases/latest" --jq .tag_name)
printf '%s\n' "$tag" | grep -Eq '^v[0-9]+(\.[0-9]+)+$' || fail "Expected a version tag such as v1.7.2, got $tag"
gh release download "$tag" --repo "github.com/$repo" --pattern Plume-Custom.apk --dir "$staging"
apk=$staging/Plume-Custom.apk

certificate=$("$build_tools/apksigner" verify --min-sdk-version 26 --print-certs "$apk")
signer=$(printf '%s\n' "$certificate" | sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p')
[ "$signer" = "$expected_cert" ] || fail "APK was signed with a different certificate"

badging=$("$build_tools/aapt" dump badging "$apk")
package_line=$(printf '%s\n' "$badging" | sed -n '/^package: /p')
package=$(printf '%s\n' "$package_line" | cut -d "'" -f 2)
code=$(printf '%s\n' "$package_line" | cut -d "'" -f 4)
version=$(printf '%s\n' "$package_line" | cut -d "'" -f 6)
[ "$package" = dev.plume.custom ] || fail "Expected APK package dev.plume.custom, got $package"
[ "$version" = "${tag#v}" ] || fail "Release tag $tag does not match APK version $version"
printf '%s\n' "$code" | grep -Eq '^[1-9][0-9]{0,9}$' || fail "Invalid APK versionCode"
[ "$code" -le 2100000000 ] || fail "APK versionCode exceeds 2100000000"

if command -v sha256sum >/dev/null 2>&1; then
    sha256=$(sha256sum "$apk" | cut -d ' ' -f 1)
elif command -v shasum >/dev/null 2>&1; then
    sha256=$(shasum -a 256 "$apk" | cut -d ' ' -f 1)
else
    sha256=$(openssl dgst -sha256 -r "$apk" | cut -d ' ' -f 1)
fi
printf '%s\n' "$sha256" | grep -Eq '^[0-9a-f]{64}$' || fail "Unable to hash APK"
manifest=$staging/latest.properties
printf 'versionCode=%s\nversionName=%s\nsha256=%s\n' "$code" "$version" "$sha256" > "$manifest"

if [ -f "$feed/latest.properties" ]; then
    previous=$(sed -n 's/^versionCode=//p' "$feed/latest.properties")
    printf '%s\n' "$previous" | grep -Eq '^[1-9][0-9]{0,9}$' || fail "Existing feed has an invalid versionCode"
    [ "$code" -ge "$previous" ] || fail "APK versionCode $code would roll back the feed from $previous"
    if [ "$code" -eq "$previous" ]; then
        cmp -s "$manifest" "$feed/latest.properties" || fail "Bump versionCode before publishing a different APK"
    fi
fi

asset=$feed/Plume-Custom-$code.apk
if [ -f "$asset" ]; then
    cmp -s "$apk" "$asset" || fail "A different APK already exists for versionCode $code"
else
    # Copy under a hidden name, then rename inside the feed, so the server never serves a partial file.
    cp "$apk" "$feed/.Plume-Custom-$code.apk.tmp"
    chmod 644 "$feed/.Plume-Custom-$code.apk.tmp"
    mv "$feed/.Plume-Custom-$code.apk.tmp" "$asset"
fi
cp "$manifest" "$feed/.latest.properties.tmp"
chmod 644 "$feed/.latest.properties.tmp"
mv "$feed/.latest.properties.tmp" "$feed/latest.properties"
printf 'Prepared %s (%s) in %s\n' "$tag" "$code" "$feed"
