# LMS Node Agent

Remote worker service for home download automation.

- Router calls node over HTTPS (`PUSH` model)
- Node executes heavy downloads (`DIRECT`, `YTDLP`, `ARIA2C`)
- All `/api/**` endpoints are protected by HMAC + nonce authentication
- Node is stateless about router and keeps job state in memory (MVP)

## How it works

1. Router sends signed request to create a job (`POST /api/jobs`).
2. Node validates HMAC headers, timestamp, body hash, and nonce replay.
3. Job is stored in memory and executed by a worker (`maxParallel` default is `1`).
4. Router polls job state via `GET /api/jobs` and `GET /api/jobs/{jobId}`.
5. Router can stop job via `POST /api/jobs/{jobId}/cancel`.

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
- `ARIA2C_BIN` (default `aria2c`)
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
  "url": "https://example.com/video"
}
```

Response `201`:

```json
{ "jobId": "uuid" }
```

### List jobs

`GET /api/jobs?active=true`

- `active=true` returns only `QUEUED` and `RUNNING`

### Get job

`GET /api/jobs/{jobId}`

Returns:

- `jobId`, `type`, `url`, `status`
- `percent`, `speedBytes`, `etaSeconds`
- `message`
- `createdAt`, `startedAt`, `finishedAt`
- `outputPath`

### Cancel job

`POST /api/jobs/{jobId}/cancel`

Best effort cancel. Running process is terminated and job becomes `CANCELED`.

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

## Operational notes

- Job and nonce stores are in memory (restart resets state).
- Service logs lifecycle events (created/started/completed/canceled/failed).
- Runtime startup checks warn if download directory or downloader binaries are not ready.
- Deploy with Nginx in front and fail2ban on 401/403 responses.

## Release

See `RELEASE_CHECKLIST.md` for pre-release and post-deploy checks.
