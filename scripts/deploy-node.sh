#!/usr/bin/env bash
set -euo pipefail

# Build lms-node image locally, upload it to the target host, and restart with docker compose.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEPLOY_ENV_FILE="${DEPLOY_ENV_FILE:-${SCRIPT_DIR}/deploy-node-s2.env}"

if [[ -f "${DEPLOY_ENV_FILE}" ]]; then
  # shellcheck disable=SC1090
  source "${DEPLOY_ENV_FILE}"
fi

NODE_HOST="${NODE_HOST:-}"
NODE_USER="${NODE_USER:-}"
NODE_PORT="${NODE_PORT:-22}"
SSH_KEY="${SSH_KEY:-}"
SSH_OPTS="${SSH_OPTS:--o StrictHostKeyChecking=accept-new}"

REMOTE_DIR="${REMOTE_DIR:-/opt/lms-node}"
REMOTE_DOWNLOADS_DIR="${REMOTE_DOWNLOADS_DIR:-${REMOTE_DIR}/downloads}"
INSTANCE_NAME="${INSTANCE_NAME:-node}"
CONTAINER_NAME="${CONTAINER_NAME:-lms-node}"

NODE_HMAC_SECRET="${NODE_HMAC_SECRET:-}"
NODE_MAX_PARALLEL="${NODE_MAX_PARALLEL:-1}"
SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-prod}"
HOST_PORT="${HOST_PORT:-8080}"
TORRENT_PORT="${TORRENT_PORT:-6881}"
TORRENT_SEED_TIME_MINUTES="${TORRENT_SEED_TIME_MINUTES:-0}"
REMOTE_DOCKER_CMD="${REMOTE_DOCKER_CMD:-docker}"
REMOTE_DOCKER_COMPOSE_CMD="${REMOTE_DOCKER_COMPOSE_CMD:-docker compose}"

if [[ -z "${NODE_HOST}" ]]; then
  echo "ERROR: NODE_HOST is required"
  exit 1
fi

if [[ -z "${NODE_USER}" ]]; then
  echo "ERROR: NODE_USER is required"
  exit 1
fi

if [[ -z "${NODE_HMAC_SECRET}" || "${NODE_HMAC_SECRET}" == "change-me" ]]; then
  echo "ERROR: set a real NODE_HMAC_SECRET in ${DEPLOY_ENV_FILE}"
  exit 1
fi

for cmd in ssh scp docker gzip; do
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    echo "ERROR: ${cmd} command not found"
    exit 1
  fi
done

SSH_BASE=(ssh -p "${NODE_PORT}")
SCP_BASE=(scp -P "${NODE_PORT}")

if [[ -n "${SSH_KEY}" ]]; then
  SSH_BASE+=(-i "${SSH_KEY}")
  SCP_BASE+=(-i "${SSH_KEY}")
fi

if [[ -n "${SSH_OPTS}" ]]; then
  # shellcheck disable=SC2206
  EXTRA_SSH_OPTS=( ${SSH_OPTS} )
  SSH_BASE+=("${EXTRA_SSH_OPTS[@]}")
  SCP_BASE+=("${EXTRA_SSH_OPTS[@]}")
fi

SSH_TARGET="${NODE_USER}@${NODE_HOST}"
TMP_ENV="$(mktemp)"
TMP_IMAGE="$(mktemp --suffix=.tar.gz)"
trap 'rm -f "${TMP_ENV}" "${TMP_IMAGE}"' EXIT

cat > "${TMP_ENV}" <<EOF
INSTANCE_NAME=${INSTANCE_NAME}
CONTAINER_NAME=${CONTAINER_NAME}
SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE}
NODE_HMAC_SECRET=${NODE_HMAC_SECRET}
NODE_MAX_PARALLEL=${NODE_MAX_PARALLEL}
NODE_DOWNLOAD_DIR=/downloads
HOST_PORT=${HOST_PORT}
TORRENT_PORT=${TORRENT_PORT}
TORRENT_SEED_TIME_MINUTES=${TORRENT_SEED_TIME_MINUTES}
REMOTE_DOWNLOADS_DIR=${REMOTE_DOWNLOADS_DIR}
EOF

echo "Deploy instance: ${INSTANCE_NAME} -> ${SSH_TARGET}:${REMOTE_DIR}"

echo "[1/5] Ensure remote directories"
"${SSH_BASE[@]}" "${SSH_TARGET}" "mkdir -p '${REMOTE_DIR}' '${REMOTE_DOWNLOADS_DIR}'"

echo "[2/5] Build local docker image"
docker build -t lms-node:latest "${REPO_ROOT}"
docker save lms-node:latest | gzip > "${TMP_IMAGE}"

echo "[3/5] Upload image and runtime files"
"${SCP_BASE[@]}" "${TMP_IMAGE}" "${SSH_TARGET}:${REMOTE_DIR}/lms-node-image.tar.gz"
"${SCP_BASE[@]}" "${TMP_ENV}" "${SSH_TARGET}:${REMOTE_DIR}/.env"
"${SCP_BASE[@]}" "${REPO_ROOT}/docker-compose.deploy.yml" "${SSH_TARGET}:${REMOTE_DIR}/docker-compose.yml"

echo "[4/5] Load image and restart container"
"${SSH_BASE[@]}" "${SSH_TARGET}" "cd '${REMOTE_DIR}' && gzip -dc lms-node-image.tar.gz | ${REMOTE_DOCKER_CMD} load && rm -f lms-node-image.tar.gz && ${REMOTE_DOCKER_COMPOSE_CMD} down && ${REMOTE_DOCKER_COMPOSE_CMD} up -d"

echo "[5/5] Show service status"
"${SSH_BASE[@]}" "${SSH_TARGET}" "cd '${REMOTE_DIR}' && ${REMOTE_DOCKER_COMPOSE_CMD} ps && ${REMOTE_DOCKER_COMPOSE_CMD} logs --tail=30 lms-node"
