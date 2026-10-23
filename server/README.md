# server/

Single Go binary: HTTP API, SQLite event log, R2 presigning and background
workers. Docker is packaging only, not extra infrastructure. See `docs/ARCHITECTURE.md` §3–§5
and `docs/INFRA.md` for deployment.

## Layout

```
cmd/cauce/                 wiring only: config, store, mux, workers
internal/auth/             auth.UserFromRequest (CAUCE_USER_ID in phase 2)
internal/config/           env-only configuration
internal/core/event/       Envelope, JSON, embedded JSON Schema validation
internal/core/store/       SQLite (modernc.org/sqlite), sqlc-generated queries
internal/core/projection/  Projection interface + registry ("<feature>.<name>")
internal/sync/             POST /sync/push, GET /sync/pull
internal/blobs/            POST /blobs/presign (presigned PUT for R2)
internal/projections/      GET /api/projections/{feature}/{name}
internal/worker/           Worker interface + ticker loop
internal/conformance/      fixture runner against schema/features/*/fixtures
migrations/                0001_events.sql, embedded at startup
```

Feature packages (`internal/<id>`) are added by `/feature <id>`; phase 2 ships
no feature implementation.

## Configuration

All configuration is environment-only. Copy `.env.example` to `.env` (ignored by
git) and export it:

```bash
set -a; . ./.env; set +a
go run ./cmd/cauce
```

| Variable | Required | Default | Meaning |
|----------|----------|---------|---------|
| `CAUCE_ADDR` | no | `:8080` | listen address |
| `CAUCE_DB_PATH` | no | `cauce.db` | SQLite file; parent dir is created |
| `CAUCE_USER_ID` | yes | — | single user id returned by `auth.UserFromRequest` |
| `R2_ACCOUNT_ID` | for blobs | — | R2 account, endpoint is `https://<id>.r2.cloudflarestorage.com` |
| `R2_ACCESS_KEY_ID` | for blobs | — | R2 token |
| `R2_SECRET_ACCESS_KEY` | for blobs | — | R2 secret |
| `R2_BUCKET` | for blobs | — | bucket name |

Without the four `R2_*` variables `POST /blobs/presign` returns 503 and every
other endpoint keeps working.

## Build and run

```bash
cd server
go build -o cauce ./cmd/cauce
./cauce
```

Or `make server-run` from the repository root.

## Docker

From the repository root, `make up` creates `server/.env` when needed, builds
the pinned image and starts the server with its database in `deploy/data/`.
Use `make backup` and `make restore FILE=...` to move the state to another
machine. The optional Cloudflare Tunnel is started with the `tunnel` profile.

## Schema embedding

`go:embed` cannot read parent directories or follow symlinks, so
`internal/core/event/schema/` is a committed copy of the top-level contract
(`envelope.v1.json` + `events/*.json`). Regenerate it after any schema change:

```bash
cd server && go generate ./internal/core/event
```

`TestEmbeddedSchemaInSync` fails when the copy drifts from `schema/`, so a stale
embed cannot merge silently. Fixtures are read from the repository at test time,
never embedded in the binary.

## sqlc

Queries live in `internal/core/store/queries.sql`, generated code in
`internal/core/store/sqlcgen/`. sqlc is not installed system-wide; regenerate
with the pinned version and commit the output:

```bash
cd server && go run github.com/sqlc-dev/sqlc/cmd/sqlc@v1.27.0 generate
```

## Endpoints

| Method | Path | Notes |
|--------|------|-------|
| `POST` | `/sync/push` | `{"events":[Envelope,...]}` → `{"accepted":n,"duplicates":m}`; the whole batch returns 400 on the first invalid event/auth mismatch |
| `GET` | `/sync/pull?since=<server_seq>` | events with `server_seq > since` for the user, ordered, max 500, plus `next_since` |
| `POST` | `/blobs/presign` | `{"key","content_type"}` → `{"url","expires_in":900}`; `key` must be under `<user_id>/` |
| `GET` | `/api/projections/{feature}/{name}` | runs a pure projection over the user's events |
| `GET` | `/healthz` | `{"status":"ok"}` |

Identity always comes from `auth.UserFromRequest` (phase 2 reads
`CAUCE_USER_ID`); the body `user_id` must match it and is never trusted for
authorization.

### Projection params and `now`

Query values become projection params as JSON strings. Projections never read the
clock: the HTTP endpoint injects `params.now` from the server clock **only when
the client omits it** (the alarmas SPEC §8 requires this so the web cache key is
stable across renders). An explicit `now` must be RFC 3339. All other params are
passed through untouched.

### Push + pull round-trip

With the server running (`CAUCE_USER_ID=u1`):

```bash
curl -sS -X POST http://localhost:8080/sync/push \
  -H 'Content-Type: application/json' \
  -d '{"events":[{"id":"0192e4a1-7f3c-7a2b-9c1d-4e5f6a7b8c9d","user_id":"u1",
       "device_id":"curl","type":"alarma_creada","v":2,"ts":"2026-10-20T10:00:00Z",
       "payload":{"alarma_id":"a1","titulo":"Vitaminas","tipo":"otro","hora":"08:00",
       "repeticion":{"freq":"DAILY"}}}]}'
# {"accepted":1,"duplicates":0}

curl -sS 'http://localhost:8080/sync/pull?since=0'
# {"events":[{"id":"0192e4a1-...","user_id":"u1",...,"server_seq":1}],"next_since":1}

# pushing the same id again is idempotent
# {"accepted":0,"duplicates":1}
```

## Conformance

`go test ./...` walks `../schema/features/*/fixtures/*/*.json`. Fixtures of a
feature without registered projections are schema-validated only and reported as
pending `/feature <id>` (in phase 2 no feature is wired, so the alarmas fixtures
are schema-validated only). Once a feature registers its projections in
`cmd/cauce/main.go` and in the test registry, every fixture of that feature must
execute its projection and match `expected` structurally (key order ignored,
arrays ordered, numbers within 1e-9); an unknown projection fails with `unknown
projection ...`.

## Tests

```bash
cd server
go vet ./...
go test ./...
```

Table-driven tests cover envelope/schema validation, store append + cursor,
push/pull over `httptest`, projection `now` injection, registry rules, blob key
containment, worker looping and the conformance runner itself.

## Operational notes

- WAL, `busy_timeout=5000` and a single connection are set at startup.
- Migrations are applied in lexical order inside a transaction and recorded in
  `schema_migrations`.
- `received_at` is server UTC time; `ts` stays the device timestamp.
- Unknown envelope and payload fields are ignored by design (additive
  versioning).
