# HTTP API

All paths begin `/api`. Responses are JSON except SSE and empty successful authentication/queue-setting responses. Use same-origin cookies. `GET /session` returns `csrfToken` and the signed-in staff username (empty for students). Send the token as `X-CSRF-TOKEN` on every POST, including login and logout. Fetch a new token after login/logout because Spring rotates the authentication session/token.

| Method / path | Access | Input / output |
|---|---|---|
| GET `/session` | Public | Establish session; CSRF token + own staff name |
| POST `/login` | Public + CSRF | URL-encoded `username`, `password`; 204 or 401 |
| POST `/logout` | Session + CSRF | Invalidates session; 204 |
| GET `/public` | Public | Queue open, aggregate counts, called numbers/states/desks, join URL |
| GET `/events` | Public | Generic SSE refresh hints, no ticket data |
| GET `/student` | Own session | Own latest ticket, people ahead, optional approximate minutes and note |
| POST `/student/join` | Own session + CSRF | `{ "displayName": "Ada" }`, required `Idempotency-Key: <UUID>` |
| POST `/student/leave` | Own session + CSRF | Cancels own waiting ticket; no ticket ID accepted |
| GET `/staff` | STAFF | Waiting and active tickets including optional names, own username, counts |
| POST `/staff/queue` | STAFF + CSRF | `{ "open": true }` |
| POST `/staff/call-next` | STAFF + CSRF | No body; required `Idempotency-Key: <UUID>` |
| POST `/staff/tickets/{uuid}/transition` | Assigned STAFF + CSRF | `{ "state": "IN_SERVICE" }`, `COMPLETED` or `NO_SHOW` |

Mutation response: `{ "ticket": {...} | null, "message": "..." }`. Ticket has `id`, human-readable `number`, `displayName`, `state`, `assignedTo`, and lifecycle timestamps. Only student-own and staff endpoints use this full projection. Public uses a separate DTO containing `number`, `state`, and `counter` only. Ticket numbers never authorise actions. Counts are all-time totals, not daily analytics.

Errors: 400 invalid input, 401 staff login required, 403 missing/invalid CSRF or forbidden route, 404 assignment absent/not owned, 409 incompatible transition/closed queue/idempotency payload conflict, 413 too large, 429 rate limit, 503 SSE capacity. Expected errors contain `message`; unexpected exceptions return generic server errors without internals.

## Retry protocol

Generate a new UUID for each intended join/call-next action. Keep it for every retry of that same action until a definitive response arrives. After a timeout, do not invent a new key: the original transaction may have committed. A successful retry returns the original result, potentially an older ticket state, so fetch a fresh snapshot. Another browser session is a different caller.

The frontend stores pending keys and join input in per-tab sessionStorage. Successful/definitive client-error responses clear keys; ambiguous network/server errors preserve them. Join/call-next are retry-safe; transition/toggle/leave endpoints validate the current state but do not implement the operation-result protocol. Refresh after an ambiguous transition response to review the actual state before acting again.

## SSE

Connect EventSource to `/api/events`. Treat `ready`/open and `changed` as invalidation hints, not ordered business events. `heartbeat` keeps the transport alive. Fetch the appropriate snapshot on connect and reconcile periodically. Streams expose no session identifiers or student names. Unauthenticated listeners may observe generic queue activity, which is public information in this MVP.
