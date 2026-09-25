# LMS Node Agent

Remote worker service for home download automation.

- Router calls node over HTTPS (`PUSH` model)
- Node executes heavy downloads (`DIRECT`, `YTDLP`, `ARIA2C`, `TORRENT`)
- All `/api/**` endpoints are protected by HMAC + nonce authentication
- Node is stateless about router; job state is kept in memory and mirrored to a JSON state file

## How it works

1. Router sends signed request to create a job (`POST /api/jobs`).
2. Node validates HMAC headers, timestamp, body hash, and nonce replay.
3. Job is stored in memory and executed by a worker (`maxParallel` default is `1`).
4. Router polls job state via `GET /api/jobs` and `GET /api/jobs/{jobId}`.
5. Router can stop or pause/resume jobs via `POST /api/jobs/{jobId}/cancel|pause|resume`.

## Tech stack

- Java 21
- Spring Boot 4 (Web + Validation + Actuator)
- External tools on host:
  - `yt-dlp`
  - `aria2c`

## Configuration

Main config: `src/main/resources/application.yaml`

Important env vars:

- `SPRING_PROFILES_ACTIVE` (`local` by default)
- `SERVER_PORT` (default `8080`)
- `NODE_DOWNLOAD_DIR` (default `./downloads` for local, `/downloads` for prod)
- `NODE_MAX_PARALLEL` (default `1`)
- `NODE_HMAC_SECRET` (required for real usage)
- `YTDLP_BIN` (default `yt-dlp`)
- `ARIA2C_BIN` (default `aria2c`, also used for `TORRENT`)
- `NODE_STATE_FILE` (default `<download-dir>/.lms-node/jobs.json`; empty = memory only)
- `NODE_RESUME_ON_STARTUP` (default `true`: continue interrupted jobs after restart; `false` = leave them `PAUSED`)
- `TORRENT_LISTEN_PORT` (default `6881-6999`; in Docker `TORRENT_PORT`, default `6881`, is published tcp+udp)
- `TORRENT_SEED_TIME_MINUTES` (default `0`: stop right after download)
- `NODE_AUTH_ALLOWED_SKEW_SECONDS` (default `120`)
- `NODE_AUTH_NONCE_TTL_SECONDS` (default `600`)

Default client id in profiles: `router-main`.

## Run locally

Prerequisites:

- JDK 21
- `yt-dlp` and `aria2c` available in `PATH` (or override with env vars)

Run tests:

```bash
./mvnw test
```

Run app:

```bash
NODE_HMAC_SECRET='change-me' ./mvnw spring-boot:run
```

Health check:

```bash
curl http://127.0.0.1:8080/actuator/health
```

## API

Base path: `/api/jobs`

### Create job

`POST /api/jobs`

```json
{
  "type": "YTDLP",
  "url": "https://example.com/video",
  "storagePath": "movies/yt"
}
```

- `type`: `DIRECT` | `YTDLP` | `ARIA2C` | `TORRENT`.
- `url`: `http(s)://...`; `TORRENT` also accepts `magnet:?...` (magnets are rejected for other types).
- `storagePath` is optional.
- If provided, it is resolved under configured `node.download-dir`.
- Paths outside `node.download-dir` are rejected with `400`.

Response `201`:

```json
{ "jobId": "uuid" }
```

### List jobs

`GET /api/jobs?active=true`

- `active=true` returns `QUEUED`, `PAUSED`, and `RUNNING`

### Get job

`GET /api/jobs/{jobId}`

Returns:

- `jobId`, `type`, `url`, `status`
- `storagePath`
- `percent`, `speedBytes`, `etaSeconds`
- `message`
- `createdAt`, `startedAt`, `finishedAt`
- `outputPath`

### Cancel job

`POST /api/jobs/{jobId}/cancel`

Best effort cancel. Running process is terminated and job becomes `CANCELED`.

### Pause job

`POST /api/jobs/{jobId}/pause`

Pauses queued/running job. Running process is interrupted and job becomes `PAUSED`.

### Resume job

`POST /api/jobs/{jobId}/resume`

Resumes paused job by putting it back into queue (`QUEUED`) for execution.
Resume can be called right after pause: if the previous run is still stopping, the job
stays `QUEUED` ("Waiting for previous run to stop") and starts as soon as it exits.

### Retry job

`POST /api/jobs/{jobId}/retry`

Re-queues an `ERROR` or `CANCELED` job. It continues from the partial data too.

## Download continuation

Pause, retry and node restarts continue from the data already on disk:

- `DIRECT`: data goes to `<jobId>.part` + `<jobId>.part.meta`; the next run sends
  `Range` (with `If-Range` when the server gave `ETag`/`Last-Modified`, otherwise the
  total size is checked). Dropped or stalled (60s without data) connections are retried
  inside the same run with backoff, up to 8 attempts, each continuing from the partial file.
  If the server ignores ranges or the file changed, the download starts over.
- `ARIA2C` / `TORRENT`: aria2c runs with `--continue` and keeps its `.aria2` control file;
  the process is stopped with SIGTERM so the state is saved (forced kill after 15s).
  Servers without range support restart the file instead of failing.
- `YTDLP`: yt-dlp keeps `.part` files and continues them; the process tree gets SIGTERM.
  The final file path is reported as `outputPath`.
- Job state is written to `NODE_STATE_FILE`. Jobs that were `QUEUED`/`RUNNING` when the
  node stopped are queued again on startup (`PAUSED` stay paused).

## Torrents

`TORRENT` jobs download magnet links or http(s) links to `.torrent` files with aria2c
(DHT, PEX and local peer discovery on). `outputPath` is the torrent's top-level file or
directory; `move` and `file/delete` handle directories, `GET /file` works only for a single file.
Magnet metadata is saved as `<infohash>.torrent` in the target dir while the job runs, so
resume does not refetch it. Seeding stops right after completion unless
`TORRENT_SEED_TIME_MINUTES` is set. `preflight` recommends `TORRENT` for magnets and
`.torrent` links.

## Authentication (HMAC + nonce)

Required headers for every `/api/**` request:

- `X-Client-Id`
- `X-Timestamp` (Unix seconds)
- `X-Nonce` (unique value)
- `X-Body-Sha256` (SHA-256 hex of raw body bytes)
- `X-Signature` (HMAC-SHA256 hex)

Signature payload:

```text
METHOD + "\n" +
PATH + "\n" +
X-Timestamp + "\n" +
X-Nonce + "\n" +
X-Body-Sha256
```

Validation behavior:

- unknown client -> `403`
- bad timestamp/hash/signature/replay nonce -> `401`

## Quick usage

Use the built-in smoke script (creates signed requests automatically):

```bash
BASE_URL="http://127.0.0.1:8080" \
CLIENT_ID="router-main" \
CLIENT_SECRET="change-me" \
JOB_TYPE="DIRECT" \
JOB_URL="https://example.com/file.bin" \
./scripts/smoke-api.sh
```

Or use interactive CLI helper for individual operations:

```bash
BASE_URL="http://127.0.0.1:8080" \
CLIENT_ID="router-main" \
CLIENT_SECRET="change-me" \
./scripts/node-cli.sh create DIRECT https://example.com/file.bin

BASE_URL="http://127.0.0.1:8080" \
CLIENT_ID="router-main" \
CLIENT_SECRET="change-me" \
./scripts/node-cli.sh list true
```

## Deploy

Repository includes a deploy script for the home server:

- `scripts/deploy-node-s1.env` - target `s1` (`192.168.99.13`).
- `scripts/deploy-node-s2.env` - target `s2` (`192.168.99.21`).
- `scripts/deploy-node.sh` - builds image locally, uploads it, writes `.env`, then restarts via `docker compose`.

Targets:

- `s1`: `amagomedsharipov@192.168.99.13`
- `s2`: `amagomedsharipov@192.168.99.21`
- key: `~/.ssh/homeserver` for both

Quick start:

1. Edit `scripts/deploy-node-s1.env` / `scripts/deploy-node-s2.env` if needed.
2. Run `make deploy-s1` or `make deploy-s2`.

## Operational notes

- Nonce store is in memory; job state survives restarts via `NODE_STATE_FILE`.
- Service logs lifecycle events (created/started/completed/canceled/failed).
- Runtime startup checks warn if download directory or downloader binaries are not ready.
- Deploy with Nginx in front and fail2ban on 401/403 responses.

## Release

See `RELEASE_CHECKLIST.md` for pre-release and post-deploy checks.
