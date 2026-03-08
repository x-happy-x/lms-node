#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
CLIENT_ID="${CLIENT_ID:-router-main}"
CLIENT_SECRET="${CLIENT_SECRET:-change-me}"
JOB_TYPE="${JOB_TYPE:-DIRECT}"
JOB_URL="${JOB_URL:-https://example.com/file.bin}"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

require_cmd curl
require_cmd openssl
require_cmd sha256sum

sha256_hex() {
  printf '%s' "$1" | sha256sum | awk '{print $1}'
}

hmac_hex() {
  local payload="$1"
  printf '%s' "$payload" | openssl dgst -sha256 -hmac "$CLIENT_SECRET" | awk '{print $2}'
}

build_headers() {
  local method="$1"
  local path="$2"
  local body="$3"

  local ts nonce body_sha payload sig
  ts="$(date +%s)"
  nonce="$(cat /proc/sys/kernel/random/uuid)"
  body_sha="$(sha256_hex "$body")"
  printf -v payload '%s\n%s\n%s\n%s\n%s' "$method" "$path" "$ts" "$nonce" "$body_sha"
  sig="$(hmac_hex "$payload")"

  cat <<HDR
-H
X-Client-Id: ${CLIENT_ID}
-H
X-Timestamp: ${ts}
-H
X-Nonce: ${nonce}
-H
X-Body-Sha256: ${body_sha}
-H
X-Signature: ${sig}
HDR
}

request() {
  local method="$1"
  local path="$2"
  local body="$3"
  local out_file="$4"
  local canonical_path="${path%%\?*}"

  mapfile -t headers < <(build_headers "$method" "$canonical_path" "$body")

  local args=(
    -sS
    -o "$out_file"
    -w "%{http_code}"
    -X "$method"
    "${BASE_URL}${path}"
    "${headers[@]}"
  )

  if [[ -n "$body" ]]; then
    args+=( -H "Content-Type: application/json" --data "$body" )
  fi

  curl "${args[@]}"
}

expect_status() {
  local actual="$1"
  local expected="$2"
  local label="$3"
  if [[ "$actual" != "$expected" ]]; then
    echo "${label} failed: expected ${expected}, got ${actual}" >&2
    exit 1
  fi
}

extract_job_id() {
  sed -n 's/.*"jobId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$1"
}

echo "[1/4] Create job"
CREATE_BODY="{\"type\":\"${JOB_TYPE}\",\"url\":\"${JOB_URL}\"}"
CREATE_RESP="${TMP_DIR}/create.json"
CREATE_STATUS="$(request POST /api/jobs "$CREATE_BODY" "$CREATE_RESP")"
expect_status "$CREATE_STATUS" 201 "create"
JOB_ID="$(extract_job_id "$CREATE_RESP")"
if [[ -z "$JOB_ID" ]]; then
  echo "Failed to parse jobId from create response" >&2
  cat "$CREATE_RESP" >&2
  exit 1
fi
echo "Created job: ${JOB_ID}"


echo "[2/4] Get job"
GET_RESP="${TMP_DIR}/get.json"
GET_STATUS="$(request GET "/api/jobs/${JOB_ID}" "" "$GET_RESP")"
expect_status "$GET_STATUS" 200 "get"


echo "[3/4] List jobs (active=true)"
LIST_RESP="${TMP_DIR}/list.json"
LIST_STATUS="$(request GET "/api/jobs?active=true" "" "$LIST_RESP")"
expect_status "$LIST_STATUS" 200 "list"


echo "[4/4] Cancel job"
CANCEL_RESP="${TMP_DIR}/cancel.json"
CANCEL_STATUS="$(request POST "/api/jobs/${JOB_ID}/cancel" "" "$CANCEL_RESP")"
expect_status "$CANCEL_STATUS" 200 "cancel"

echo "Smoke test passed"
echo "Responses saved in: ${TMP_DIR}"
