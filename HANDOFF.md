# QueueLive handoff

The project was reviewed locally before the scheduled September 24, 2026 GitHub commit. No deployment was performed.

## Completed

- Spring Boot Java 21-targeted backend with PostgreSQL/Flyway, JDBC sessions, staff auth, CSRF, BCrypt, owner checks, explicit ticket state machine, UTC timestamps, audit events and database constraints.
- Queue-row lock serialises concurrent mutations; transactional stored results make join and call-next retry-safe. After-commit SSE refresh hints plus ten-second reconciliation recover missed updates.
- Responsive React student, staff and public pages with QR code, connection states, loading/errors and accessible controls.
- Maven Wrapper, npm lockfile, Docker Compose, CI, real PostgreSQL tests, Playwright tests, load script, and requested documentation.

## Verified here

- `./mvnw verify` against disposable PostgreSQL 17.6: 19 tests passed. Host Java 25.0.3 compiled source targeting Java 21.
- Fresh database Flyway migration and live backend startup passed.
- Frontend format check, TypeScript/Vite production build and npm audit passed; npm audit reported zero known vulnerabilities.
- Playwright Chromium: 2 browser tests passed, including disconnect and missed-SSE recovery.
- Student phone/desktop, staff desktop and public board inspected in Chromium; no horizontal overflow. Student phone and staff desktop captures are included under `docs/screenshots/`.
- Small measured local load workload: 40 scenarios, 200 requests, 0 errors. Hardware and latency are recorded in `docs/testing.md` with the workload limits.
- September 24 recheck: all 19 backend tests passed with default Testcontainers PostgreSQL 17.11; `npm ci`, frontend formatting, production build and npm audit passed; the isolated Compose build/startup and both Playwright Chromium tests passed against nginx and Java 21 containers.

## Unverified / limitations

- GitHub Actions has not yet been observed. Check its results after the first push. Local container and Java 21 verification results are recorded in `docs/testing.md`.
- Sessions and ticket/audit/idempotency data need an explicit retention policy and cleanup job before real use.
- One process owns SSE subscribers and rate-limit counters; multi-instance fan-out is not implemented.

## Next exact steps

1. For a local demo, copy `.env.example` to `.env`, set unique local passwords, run `docker compose up --build -d`, and visit `http://localhost:8080`. Confirm `/api/public`, staff login and QR target.
2. Run `cd backend && ./mvnw verify`, then `cd frontend && npm ci && npm run build && npx playwright install chromium && DEMO_STAFF_PASSWORD='your-value' npm run test:e2e` against a disposable Compose database.
3. Review retention/staff-provisioning and safe deployment guidance in `docs/security.md` before any real-lab use. Do not deploy until the user requests it.

## Latest review follow-up

A fresh review reran all 19 backend tests, both browser tests and frontend format/build checks successfully. Targeted diagnostics nevertheless reproduced HTTP-origin UUID failure, pending-key loss after 429, persistent access by a disabled staff session, and a repeatable-read presence-update conflict. The proxy rate limit also aggregates users by construction. See `docs/review.md` for evidence and reproduction scripts and `docs/improvement-blueprint.md` for ordered fixes and acceptance gates. Application behavior was not changed during that review.
