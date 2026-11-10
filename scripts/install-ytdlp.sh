#!/bin/sh
set -eu
version=2026.11.04.232659
checksum=21f87ead735de22248806d9a55202eefbff98da25ed3a054ee03ae55878920ac
archive=$(mktemp)
trap 'rm -f "$archive"' EXIT
curl --fail --location --retry 3 --connect-timeout 15 --max-time 120 "https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/download/$version/yt-dlp" -o "$archive"
printf '%s  %s\n' "$checksum" "$archive" | sha256sum -c -
python3 -m venv /opt/ytdlp-python
/opt/ytdlp-python/bin/python -m pip --disable-pip-version-check install --no-cache-dir --only-binary=:all: --require-hashes -r /tmp/ytdlp-network-requirements.txt
install -m 0644 "$archive" /opt/ytdlp-python/yt-dlp.pyz
printf '#!/bin/sh\nexec /opt/ytdlp-python/bin/python /opt/ytdlp-python/yt-dlp.pyz "$@"\n' > /usr/local/bin/yt-dlp
chmod 0755 /usr/local/bin/yt-dlp
/usr/local/bin/yt-dlp --version

/usr/local/bin/yt-dlp --no-colors --list-impersonate-targets | grep -q curl_cffi
