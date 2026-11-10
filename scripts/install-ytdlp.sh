#!/bin/sh
set -eu
version=2026.10.26
checksum=e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3
archive=$(mktemp)
trap 'rm -f "$archive"' EXIT
curl --fail --location --retry 3 --connect-timeout 15 --max-time 120 "https://github.com/yt-dlp/yt-dlp/releases/download/$version/yt-dlp" -o "$archive"
printf '%s  %s\n' "$checksum" "$archive" | sha256sum -c -
install -m 0755 "$archive" /usr/local/bin/yt-dlp
/usr/local/bin/yt-dlp --version
