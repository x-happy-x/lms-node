# Release Checklist (MVP)

Use this checklist before deploying the node agent to VPS.

## 1. Local Verification

- [ ] Run full test suite:

```bash
./mvnw test
```

- [ ] Build artifact:

```bash
./mvnw package
```

- [ ] Validate application starts with expected profile:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=test
```

## 2. API Smoke Test (HMAC)

Run against a live node instance (local or VPS) with valid credentials.

```bash
BASE_URL="https://node.example.com" \
CLIENT_ID="router-main" \
CLIENT_SECRET="<hmac-secret>" \
JOB_TYPE="DIRECT" \
JOB_URL="https://example.com/file.bin" \
./scripts/smoke-api.sh
```

Expected result:

- Script prints `Smoke test passed`
- Create/get/list/cancel endpoints all return expected status codes

## 3. Runtime Preconditions

- [ ] `node.download-dir` exists and is writable by app user
- [ ] `yt-dlp` is installed and executable on target host
- [ ] `aria2c` is installed and executable on target host
- [ ] Nginx reverse proxy is routing `/api/**` to app
- [ ] fail2ban rule for Nginx 401/403 on `/api/**` is active

## 4. Security Validation

- [ ] Unknown `X-Client-Id` returns `403`
- [ ] Invalid signature/body hash/timestamp/nonce replay returns `401`
- [ ] Nginx access logs contain 401/403 entries for bad requests (fail2ban signal)

## 5. Post-Deploy Checks

- [ ] `GET /actuator/health` is `UP`
- [ ] Router can create job and read progress
- [ ] Cancel request terminates running process and sets status `CANCELED`
- [ ] Downloaded files appear in configured download directory
