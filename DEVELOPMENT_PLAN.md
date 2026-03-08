# Development Plan

This document defines the implementation roadmap for the Node Agent MVP described in `AGENTS.md`.

## 1. Scope and Delivery Strategy

- Delivery model: incremental, MVP-first.
- Execution model for MVP: single active job (`maxParallel=1`).
- Persistence for MVP: in-memory only.
- Security model: custom HMAC auth in app; progressive bans handled by Nginx + fail2ban outside app.

Success condition:

- Router can create/list/get/cancel jobs over authenticated `/api/**` endpoints.
- Node executes `DIRECT`, `YTDLP`, and `ARIA2C` jobs into configured downloads directory.

---

## 2. Milestones

1. Foundation and project structure
2. Domain model and in-memory job store
3. Downloader abstraction + concrete downloaders
4. Job orchestration and lifecycle management
5. REST API endpoints
6. HMAC authentication filter with nonce replay protection
7. Observability and operational hardening
8. Test pass and MVP release checklist

---

## 3. Detailed Implementation Phases

## Phase 1: Foundation

Goals:

- Ensure baseline Spring Boot setup is aligned with MVP requirements.
- Create package/module structure for clean separation.

Tasks:

- Confirm dependencies: web, validation, (optional) actuator.
- Add configuration class/properties for:
  - downloads directory
  - max parallel jobs (default 1)
  - auth timestamp skew and nonce TTL
  - downloader executable paths (`yt-dlp`, `aria2c`) if needed
- Define package layout:
  - `api` (controllers, DTOs)
  - `domain` (job model, enums)
  - `service` (job orchestration)
  - `downloader` (interface + implementations)
  - `security` (HMAC filter and helpers)
  - `infra` (stores, process management utilities)

Exit criteria:

- Application starts cleanly with configuration binding validated.

---

## Phase 2: Domain and In-Memory Store

Goals:

- Define stable job model and status transitions.
- Implement thread-safe in-memory storage.

Tasks:

- Create enums:
  - `JobType`: `DIRECT`, `YTDLP`, `ARIA2C`
  - `JobStatus`: `QUEUED`, `RUNNING`, `DONE`, `ERROR`, `CANCELED`
- Create entities/records:
  - `Job`
  - `JobProgressSnapshot`
- Define transition rules and guard conditions:
  - `QUEUED -> RUNNING -> DONE|ERROR|CANCELED`
  - `QUEUED -> CANCELED` allowed
  - terminal states immutable
- Implement `JobRepository` interface + in-memory implementation with concurrent collections.

Exit criteria:

- Jobs can be created, queried, updated, and canceled in memory with valid transitions.

---

## Phase 3: Downloader Layer

Goals:

- Implement extensible downloader contract.
- Provide three concrete downloader types.

Tasks:

- Define `Downloader` interface:
  - `id()`
  - `supports(request)` or direct type mapping
  - `download(request, progressListener)`
  - optional cancellation handle
- Implement `DirectHttpDownloader`:
  - stream URL to file under downloads directory
  - report percent when content-length is available
- Implement `YtDlpDownloader`:
  - start process via `ProcessBuilder`
  - parse stdout/stderr lines for progress/speed/ETA/message
- Implement `Aria2cDownloader`:
  - start process via `ProcessBuilder`
  - baseline status reporting; optional progress parsing in MVP
- Add process wrapper utility for:
  - process startup
  - line streaming
  - cancellation/termination
  - exit code handling

Exit criteria:

- Each downloader can run independently and produce a terminal result.

---

## Phase 4: Job Orchestration

Goals:

- Wire queue + execution with maxParallel=1.
- Keep lifecycle consistent and cancellation reliable.

Tasks:

- Implement `JobService` with methods:
  - `createJob(type, url)`
  - `listJobs(activeOnly)`
  - `getJob(jobId)`
  - `cancelJob(jobId)`
- Add executor/worker loop:
  - picks queued jobs
  - marks running/terminal states
  - stores progress updates (throttled to 1-3 seconds)
- Track active process handle for cancellation.
- Ensure idempotent cancel behavior.

Exit criteria:

- End-to-end job flow works from queue to terminal state with consistent timestamps.

---

## Phase 5: REST API

Goals:

- Expose API contract in `AGENTS.md`.

Tasks:

- Implement controller endpoints:
  - `POST /api/jobs`
  - `GET /api/jobs`
  - `GET /api/jobs/{jobId}`
  - `POST /api/jobs/{jobId}/cancel`
- Add request validation:
  - valid URL format
  - required `type`
- Implement global exception mapping:
  - 400 for validation errors
  - 404 for unknown job
  - 409 where conflict semantics make sense
- Keep response DTOs stable and router-friendly.

Exit criteria:

- API behaves per contract for happy and error paths.

---

## Phase 6: HMAC Authentication

Goals:

- Protect all `/api/**` endpoints with Variant A auth.

Tasks:

- Implement request wrapper to safely read body multiple times.
- Implement cryptographic helpers:
  - SHA-256 body hash
  - HMAC-SHA256 signature generation/verification
- Implement nonce store with TTL eviction.
- Implement `OncePerRequestFilter`:
  - read required headers
  - validate timestamp window
  - validate nonce uniqueness
  - validate body hash
  - validate signature
  - return 401/403 exactly as specified
- Add auth config model:
  - map `clientId -> secret`
  - skew/ttl settings

Exit criteria:

- Unauthorized and malformed requests are rejected with correct status codes.

---

## Phase 7: Observability and Hardening

Goals:

- Ensure operability on VPS behind reverse proxy.

Tasks:

- Add structured logs for job lifecycle events.
- Keep only concise progress message in memory.
- Add health endpoint via actuator if enabled.
- Add startup checks/log warnings for:
  - missing downloader binaries
  - unwritable downloads directory
- Add graceful shutdown handling:
  - stop worker loop
  - terminate active child process cleanly

Exit criteria:

- Service is diagnosable and handles restart/shutdown safely.

---

## Phase 8: Testing and Release Readiness

Goals:

- Validate MVP behavior and reduce regression risk.

Tasks:

- Unit tests:
  - job state transitions
  - HMAC payload/signature verification
  - nonce replay rules
- Integration tests:
  - authenticated API calls
  - unauthorized cases (bad signature/hash/timestamp/nonce)
  - create/list/get/cancel flow
- Manual smoke tests on target-like host:
  - direct HTTP download
  - yt-dlp download
  - aria2c download
  - cancellation while running
- Verify 401/403 responses are visible in reverse proxy logs (for fail2ban compatibility).

Exit criteria:

- All critical tests green, smoke tests successful, and MVP checklist complete.

---

## 4. Suggested Implementation Order (Task Queue)

1. Phase 1 + Phase 2 skeleton
2. Phase 4 orchestrator skeleton (without auth)
3. Phase 5 API endpoints (temporary open access for local testing)
4. Phase 3 downloaders integration
5. Phase 6 HMAC filter integration
6. Phase 7 hardening
7. Phase 8 test completion and release prep

Reasoning:

- This order enables rapid end-to-end validation early, then adds auth and hardening once core behavior is stable.

---

## 5. Risks and Mitigations

- External tools (`yt-dlp`, `aria2c`) output format changes.
  - Mitigation: keep parsers tolerant; do not fail job solely on unparsed progress lines.
- Long-running processes may orphan on crash/shutdown.
  - Mitigation: process handle tracking + shutdown hooks.
- In-memory nonce/job stores are not persistent.
  - Mitigation: acceptable for MVP; document restart semantics.
- Path safety and filename handling for downloaded content.
  - Mitigation: sanitize generated paths and constrain writes under configured root.

---

## 6. MVP Checklist

- [ ] Authenticated `POST /api/jobs` creates job
- [ ] Authenticated `GET /api/jobs` lists jobs, `active=true` filter works
- [ ] Authenticated `GET /api/jobs/{jobId}` returns snapshot
- [ ] Authenticated `POST /api/jobs/{jobId}/cancel` cancels running/queued job
- [ ] `DIRECT` downloader works for basic HTTP file
- [ ] `YTDLP` downloader works for supported media URL
- [ ] `ARIA2C` downloader works for direct link
- [ ] Correct 401/403 behavior for auth failures
- [ ] Downloads are written to configured directory
- [ ] Graceful shutdown does not leave unmanaged worker processes

