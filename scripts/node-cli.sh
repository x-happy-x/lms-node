#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
CLIENT_ID="${CLIENT_ID:-router-main}"
CLIENT_SECRET="${CLIENT_SECRET:-change-me}"

usage() {
  cat <<USAGE
Usage:
  BASE_URL=... CLIENT_ID=... CLIENT_SECRET=... ./scripts/node-cli.sh <command> [args]

Commands:
  create <TYPE> <URL>      Create a job (TYPE: DIRECT|YTDLP|ARIA2C)
  list [active]            List jobs; optional active=true|false
  get <JOB_ID>             Get one job
  cancel <JOB_ID>          Cancel job

Examples:
  ./scripts/node-cli.sh create DIRECT https://example.com/file.bin
  ./scripts/node-cli.sh list true
  ./scripts/node-cli.sh get 11111111-1111-1111-1111-111111111111
  ./scripts/node-cli.sh cancel 11111111-1111-1111-1111-111111111111
USAGE
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

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

signed_request() {
  local method="$1"
  local path_with_query="$2"
  local body="$3"

  local canonical_path="${path_with_query%%\?*}"
  mapfile -t headers < <(build_headers "$method" "$canonical_path" "$body")

  local tmp_body
  tmp_body="$(mktemp)"

  local args=(
    -sS
    -o "$tmp_body"
    -w "%{http_code}"
    -X "$method"
    "${BASE_URL}${path_with_query}"
    "${headers[@]}"
  )

  if [[ -n "$body" ]]; then
    args+=( -H "Content-Type: application/json" --data "$body" )
  fi

  local status
  status="$(curl "${args[@]}")"

  if [[ "$status" =~ ^2 ]]; then
    cat "$tmp_body"
    echo
    rm -f "$tmp_body"
    return 0
  fi

  echo "Request failed: ${method} ${path_with_query} -> HTTP ${status}" >&2
  cat "$tmp_body" >&2
  echo >&2
  rm -f "$tmp_body"
  return 1
}

cmd_create() {
  local type="$1"
  local url="$2"
  local body
  body="{\"type\":\"${type}\",\"url\":\"${url}\"}"
  signed_request POST "/api/jobs" "$body"
}

cmd_list() {
  local active="${1:-}"
  if [[ -n "$active" ]]; then
    signed_request GET "/api/jobs?active=${active}" ""
  else
    signed_request GET "/api/jobs" ""
  fi
}

cmd_get() {
  local job_id="$1"
  signed_request GET "/api/jobs/${job_id}" ""
}

cmd_cancel() {
  local job_id="$1"
  signed_request POST "/api/jobs/${job_id}/cancel" ""
}

main() {
  require_cmd curl
  require_cmd openssl
  require_cmd sha256sum

  local cmd="${1:-}"
  case "$cmd" in
    create)
      [[ $# -eq 3 ]] || { usage; exit 1; }
      cmd_create "$2" "$3"
      ;;
    list)
      [[ $# -le 2 ]] || { usage; exit 1; }
      cmd_list "${2:-}"
      ;;
    get)
      [[ $# -eq 2 ]] || { usage; exit 1; }
      cmd_get "$2"
      ;;
    cancel)
      [[ $# -eq 2 ]] || { usage; exit 1; }
      cmd_cancel "$2"
      ;;
    -h|--help|help|"")
      usage
      ;;
    *)
      echo "Unknown command: $cmd" >&2
      usage
      exit 1
      ;;
  esac
}

main "$@"
