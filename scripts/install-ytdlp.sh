#!/bin/sh
set -eu
version=2026.11.04.232659
checksum=21f87ead735de22248806d9a55202eefbff98da25ed3a054ee03ae55878920ac
archive=$(mktemp)
trap 'rm -f "$archive"' EXIT
curl --fail --location --retry 3 --connect-timeout 15 --max-time 120 "https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/download/$version/yt-dlp" -o "$archive"
printf '%s  %s\n' "$checksum" "$archive" | sha256sum -c -
install -m 0755 "$archive" /usr/local/bin/yt-dlp
/usr/local/bin/yt-dlp --version
