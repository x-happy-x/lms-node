# AGENTS.md

This repository contains the **Node Agent** (remote worker) for a home download system.

## System Architecture

- **Router (control plane)** runs at home (Entware). It provides UI + lightweight backend and schedules jobs.
- **Node (this repository)** runs on a **remote VPS** (public IP) and performs heavy work: `yt-dlp`, `aria2c`, direct HTTP downloads, and future post-processing.
- Interaction model is **PUSH**: the **router calls the node** over HTTPS.
- The node is **stateless about the router**: it does not call back and does not store router endpoint information.
- The node accepts requests only when authentication and integrity checks pass.

---

## MVP Goals

1. Expose a compact HTTPS REST API to:
   - create a download job
   - inspect job status/progress
   - cancel a job
   - list jobs
2. Execute downloads via one of:
   - **Direct HTTP** (`java.net.http.HttpClient`) for simple links
   - **yt-dlp** for supported video/media platforms
   - **aria2c** for resilient direct downloads (resume/multi-connection tuning later)
3. Implement machine-to-machine authentication using **HMAC signature + nonce** (Variant A).
4. Keep progressive blocking and IP bans **outside** the application:
   - Nginx reverse proxy in front of the node
   - fail2ban consuming Nginx logs for repeated 401/403 responses

---

## MVP Non-Goals

- No UI on the node.
- No node-to-router callbacks, polling channels, or websockets.
- No heavy persistence requirement for MVP (in-memory storage is acceptable).
  - Persistence (SQLite/PostgreSQL) can be added later without changing API shape.
- No end-user file streaming through the node API.

---

## Runtime Requirements

### Platform

- Linux VPS with public inbound connectivity (Debian/Ubuntu preferred).
- Java 21 runtime.

### Required Tools on Node Host

- `yt-dlp`
- `aria2c`
- Optional later: `ffmpeg`

### Storage

- Default download directory: `/downloads` (must be configurable).
- Node writes resulting files to this directory (subfolders/templates allowed).
- Ensure sufficient disk space and write permissions before starting jobs.

---

## API Contract (MVP)

- Prefix: `/api`
- Auth: all `/api/**` endpoints require HMAC authentication.
- Content type for request/response payloads: `application/json` unless noted otherwise.

### Create Job

`POST /api/jobs`

Request body example:

```json
{
  "type": "YTDLP", // DIRECT | YTDLP | ARIA2C
  "url": "https://..."
}
```

Response example:

```json
{ "jobId": "uuid" }
```

### List Jobs

`GET /api/jobs?active=true`

- `active=true` returns only `QUEUED` and `RUNNING` jobs.

### Get Job

`GET /api/jobs/{jobId}`

- Returns status and latest progress snapshot.

### Cancel Job

`POST /api/jobs/{jobId}/cancel`

Best-effort behavior:

- if process is running, destroy process
- job transitions to `CANCELED`

---

## Job Model (Minimum)

- `jobId: UUID`
- `type: DIRECT | YTDLP | ARIA2C`
- `url: string`
- `status: QUEUED | RUNNING | DONE | ERROR | CANCELED`
- progress (optional but recommended):
  - `percent: number?`
  - `speedBytes: number?`
  - `etaSeconds: number?`
- `message: string?` (last meaningful line)
- timestamps:
  - `createdAt`
  - `startedAt`
  - `finishedAt`
- `outputPath: string?` (final path hint when available)

MVP execution model can be **single active job** (`maxParallel=1`). Parallelism is a later enhancement.

---

## Download Execution Design

Use an extensible downloader abstraction:

- `Downloader` interface:
  - `id()`
  - `supports(DownloadRequest)`
  - `download(DownloadRequest, ProgressListener)`
  - optional `cancel(handle)`

Required implementations:

1. `DirectHttpDownloader` (Java HTTP stream download)
2. `YtDlpDownloader` (process execution + progress parsing)
3. `Aria2cDownloader` (process execution; progress parsing can be added incrementally)

Selection:

- service/controller maps requested job type to concrete downloader.

Progress strategy:

- keep latest progress snapshot in memory for reads.
- throttle progress writes (for example, every 1-3 seconds) to reduce overhead.

---

## Authentication (Variant A: HMAC + Nonce)

### Required headers for every `/api/**` request

- `X-Client-Id`: router client identifier (pre-shared)
- `X-Timestamp`: Unix seconds (integer)
- `X-Nonce`: unique random token (UUID recommended)
- `X-Body-Sha256`: lowercase hex SHA-256 of raw request body bytes
  - for empty body: SHA-256 of empty string
- `X-Signature`: lowercase hex HMAC-SHA256 signature

### Signature payload

```text
payload =
  METHOD + "\n" +
  PATH + "\n" +
  X-Timestamp + "\n" +
  X-Nonce + "\n" +
  X-Body-Sha256
```

```text
X-Signature = HEX(HMAC_SHA256(secret, payload))
```

### Verification rules

- Unknown `X-Client-Id` -> `403 Forbidden`
- Timestamp skew outside configured window (default +/- 120s) -> `401 Unauthorized`
- Reused nonce within nonce TTL (default 10 minutes) -> `401 Unauthorized`
- Body hash mismatch -> `401 Unauthorized`
- Signature mismatch -> `401 Unauthorized`

### Implementation notes

- Implement as servlet filter (for example, `OncePerRequestFilter`) for `/api/**`.
- Because body hashing requires reading request bytes, request body must be re-readable:
  - use `ContentCachingRequestWrapper` or equivalent custom wrapper.
- Nonce store:
  - MVP: in-memory map `nonce -> expiresAt`
  - Later: persistent/shared storage if needed

---

## Security Hardening (Outside Application)

Progressive bans are managed by infrastructure, not node code:

1. Nginx reverse proxy in front of node app
2. fail2ban parsing Nginx access logs for `/api/**` 401/403 patterns
3. Optional recidive jail for escalating repeat offenders

Application responsibility:

- return correct 401/403 codes consistently so infra protections can react.

---

## Spring Stack (MVP)

Required:

- `spring-boot-starter-web`
- `spring-boot-starter-validation`

Optional (recommended):

- `spring-boot-starter-actuator`

Not required for MVP:

- Spring Security (custom HMAC filter is used)
- WebFlux/WebSocket
- JPA/relational DB

Target runtime:

- Java 21
- Maven or Gradle (project preference)

---

## Operational Notes

- Node app should listen on a local port (for example, `8080`); TLS termination is handled by Nginx (`443`).
- Keep runtime memory bounded for in-memory job/nonce stores.
- Store only concise progress text (for example, last meaningful line) for MVP.
- Large downloads (0-100+ GB) are expected; external tools should handle robustness.
- Enforce process execution timeouts/cancellation paths to avoid orphaned workers.

---

## MVP Definition of Done

- Node application runs on VPS behind Nginx.
- Router can successfully:
  - create job
  - read status/progress
  - cancel job
- Node executes `yt-dlp`, `aria2c`, and direct HTTP downloads into `/downloads`.
- Unauthorized/malformed requests return proper 401/403 responses enabling fail2ban action.

---

## Optional Next Section

If needed, add deploy appendix sections with sample:

- Nginx reverse proxy configuration
- fail2ban jail/filter configuration for `/api/**` authentication failures
