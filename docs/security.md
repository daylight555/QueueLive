# Security and operational boundaries

## Authentication and ownership

Spring Security handles form login, session fixation protection, logout, CSRF, staff route checks and BCrypt (cost 12). Passwords are never returned. There is no JWT or secret in frontend code. Browser session cookies are HTTP-only, SameSite=Lax, and Secure by default; local HTTP explicitly opts out through `COOKIE_SECURE=false`. TLS is required for real use. CSRF tokens are fetched through a same-origin endpoint and submitted in a header. Do not enable wildcard credentialed CORS.

Students have server-issued sessions, not accounts. A stable server-only owner UUID is stored in that session, derived deterministically from its initial server session ID so concurrent initialisation cannot create multiple owners. Student request bodies cannot specify an owner. Possessing a public number, or even guessing a ticket UUID, does not grant private actions. The leave endpoint operates on the caller's own active ticket only. Staff transitions require both the STAFF role and the current assignee.

The public board intentionally projects ticket numbers, desk labels and statuses; it excludes names, UUIDs, session data and timestamps. SSE is public because it contains only generic refresh hints. Staff need names to locate students and therefore can view the shared staff queue.

## Inputs and abuse limits

Names allow at most 40 Java UTF-16 units, trim surrounding whitespace and reject control characters. React renders text with escaping; no HTML is accepted. Idempotency keys must parse as UUIDs. SQL uses bound parameters; the only dynamic SQL column is selected from a fixed state-transition branch.

The backend has fixed-window limits: 600 requests/minute per direct source IP and 20 login attempts/minute per source IP, with a bounded 10,000-key map. nginx limits body size to 4 KB and the backend checks declared Content-Length. The backend's direct-body check is not a streaming size limit; keep it private behind the proxy. SSE has a process-wide 300-connection cap and 120-second lifetime. These are modest safeguards, not a comprehensive denial-of-service solution.

The Compose reverse proxy shares one source IP from the backend's perspective, so these limits effectively aggregate local users. We do not trust arbitrary forwarded IP headers. Production needs a trusted edge proxy with per-client throttling, connection caps, timeouts, and a deliberate forwarded-header policy. In-memory limits reset on restart and are not distributed.

## Credentials and provisioning

`.env` is ignored; `.env.example` contains placeholders. Demo provisioning is disabled by default and requires a 12–72-character environment password when enabled. Use ASCII demo passwords within BCrypt's 72-byte limit. `alex` and `sam` are example usernames; their public desk labels match these names. Existing account hashes are never replaced on restart. Demo accounts share the explicitly supplied password only for local demonstration.

Before production, disable demo provisioning, remove demo staff through an audited database administration process after handling any assigned tickets, and provision individual accounts with BCrypt hashes using Spring's PasswordEncoder or a compatible trusted offline tool. There is no password reset/admin UI. Never paste passwords into SQL or frontend code. Store application/database secrets through the deployment platform and restrict who can read process environments.

## Retention and logging

Ticket names, owner UUIDs, timestamps, audit actors and retry-response JSON currently have no automatic expiry. Sessions expire after seven days idle and Spring Session cleans expired records. Clearing session cookies can leave a waiting ticket that the user can no longer cancel; staff can call and mark it no-show. Operational policy should decide when to close stale waiting entries.

For real use, choose a documented retention window (for example 30 days for closed tickets) based on actual institution needs. Implement a transactionally safe cleanup that includes terminal tickets, corresponding audit rows, idempotency response JSON and obsolete session data, with a published retry horizon. Keep active tickets and in-horizon retry records. Include backups in the retention policy. This MVP does **not** claim that such cleanup already exists or that it meets any legal compliance standard.

Application code does not log passwords, cookies, request bodies or student names. Avoid debug security/HTTP logging in real operation. Test failure reports and Playwright traces can contain disposable test credentials and names; treat CI artifacts as private and retain briefly. GitHub CI uses clearly disposable test-only credentials.

## Deployment checklist

Use HTTPS, Secure cookies, a correct PUBLIC_BASE_URL, a private database, restricted staff access, encrypted backups and a restore drill. Serve frontend and API through one origin. Keep nginx buffering off for SSE. Configure readiness checks/monitoring and review pinned dependencies/images before deployment. No public deployment was performed by this task.
