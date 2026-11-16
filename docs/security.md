# Security and trust boundary

ReviewPulse is a **single-machine, local** system. It has no user accounts.

## Trust boundary

* Every port binds to `127.0.0.1`: backend 8080, Vite 5173, PostgreSQL 15432, Kafka 9092, Redis 6379.
  Nothing is reachable from other machines unless you change `SERVER_ADDRESS` or the Compose port
  mappings. Do not do that without adding real authentication and TLS.
* Anyone who can reach the backend can create, edit and delete reviews. That is the intended demo
  behavior. Optimistic versions prevent lost updates, not unauthorized ones.
* `/api/admin/**` (reindex, snapshot, verify, replay) requires `X-Admin-Token`, compared in constant time.
  With no token configured the endpoints return 503 (fail closed), never "open".
* The Vite dev proxy attaches the admin token server-side from `../.env`. The built bundle does not contain
  it (checked with a grep of `dist/`).

## Controls in place

| Risk | Control |
|---|---|
| SQL injection | All SQL parameterized (`JdbcClient`/`PreparedStatement`); no user input concatenated |
| XSS | Highlights are returned as offsets; the UI renders text nodes and `<mark>` only, never HTML |
| Oversized input | `q` ≤ 256 chars and ≤ 10 terms; title ≤ 500, body ≤ 32,000 chars; request body ≤ 128 KB (413), enforced while streaming too |
| CPU exhaustion | Per-query time budget (2 s → 503), page size ≤ 50, keyset cursors (deep pages cost the same as page 1) |
| Abuse | Per-client token buckets (100 reads/s, 20 writes/s by default) → 429 + `Retry-After` |
| Path traversal | Replay files: strict name pattern, resolved inside the configured directory only; data-tools refuse paths outside `data/` |
| Bad events | Strict schema validation; poison events go to a dead-letter topic |
| Secrets | None in Git. `.env` is ignored; defaults are local-only placeholders |
| Error leakage | 5xx responses carry generic messages; details go to logs only |
| PII | Reviewer ids are never stored or logged |

## Not in scope (would be required before exposing it)

TLS everywhere, real authentication/authorization (OAuth2/OIDC) instead of a shared admin token,
per-user rate limits behind a trusted proxy, Kafka/PostgreSQL/Redis authentication, secret management,
audit logging, dependency CVE scanning in CI.
