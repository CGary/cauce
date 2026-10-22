# BOOTSTRAP.md — initializing the project with Claude Code

Audience: the Claude Code agent, on a fresh clone where `android/`, `server/` and `web/` contain only a README. Read `AGENTS.md`, `docs/ARCHITECTURE.md` and `schema/README.md` first. Then execute the phases below **in order**. Each phase ends with a gate; stop there, report, and wait for the owner before starting the next phase. Do not skip ahead "to save time": the owner wants to review each layer.

Throughout: no DI frameworks, no extra infrastructure, smallest thing that passes the gate. Commit at the end of each phase with scope `bootstrap`.

---

## Phase 0 — Toolchain check

Verify and print versions. Do not install anything system-wide without asking.

| Tool | Minimum | Why |
|------|---------|-----|
| JDK | 17 | Android Gradle Plugin |
| Android SDK + cmdline-tools | API 34 platform, build-tools | compile the app |
| Go | 1.22 | stdlib router |
| Node | 20 | web dashboard |
| Python 3 + `jsonschema` | 3.10 | `scripts/lint_contract.py` |
| `sqlc` | latest | Go query codegen (optional in phase 2; can be deferred) |

Run `python3 scripts/lint_contract.py`. It must pass on the untouched repo.

**Gate 0:** versions table printed, lint green. If anything is missing, list exactly what the owner must install and stop.

---

## Phase 1 — Build glue

`Makefile` and `.github/workflows/ci.yml` already exist with targets `lint`, `test-server`, `test-android`, `test`, `apk`, `server-run`. The server and android CI jobs carry `continue-on-error: true` until phases 2 and 3 land; remove the flag at the end of each phase. Adjust paths in both files only if your module layout differs from this document.

**Gate 1:** `make lint` passes. Commit.

---

## Phase 2 — Server (Go)

Module path: `cauce/server` (private module, no domain needed). Layout:

```
server/
  cmd/cauce/main.go              wiring only: config, store, http mux, workers
  internal/core/event/           Envelope struct, JSON (de)serialization, schema validation
  internal/core/store/           SQLite via modernc.org/sqlite; migrations in migrations/*.sql
  internal/core/projection/      Projection interface + registry (key "<feature>.<name>")
  internal/sync/                 POST /sync/push, GET /sync/pull
  internal/blobs/                POST /blobs/presign (S3-compatible presigned PUT for R2)
  internal/projections/          GET /api/projections/{feature}/{name}?param=... (for web)
  internal/worker/               ticker loop; Worker interface { Name(); Run(ctx) }
  internal/alarmas/              projections for the alarmas feature (pure functions)
  internal/conformance/          test runner that walks ../../schema/features/*/fixtures
  .env.example
```

Requirements:

- **Events table** (`migrations/0001_events.sql`): `server_seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE, user_id TEXT, device_id TEXT, type TEXT, v INTEGER, ts TEXT, payload TEXT (JSON), received_at TEXT`. Index on `(user_id, server_seq)` and `(user_id, type)`.
- **Push** is idempotent: duplicate `id` → ignored, counted in the response. Validate every event against `schema/envelope.v1.json` and `schema/events/<type>.v<v>.json`; reject the whole batch with the first error (400) so the device can fix and retry.
- **Pull** returns events with `server_seq > since` for the request's `user_id`, ordered, max 500, plus `next_since`. The device's own events are included (simplest; dedupe by `id` on the device).
- **user_id** comes from one function `auth.UserFromRequest(r)`. Phase 2 implementation: read `CAUCE_USER_ID` from env and trust the network (the server is only reachable through Cloudflare Tunnel + Access). Keep the function; it is the only place that changes if a second user ever exists.
- **Schema loading**: the server embeds `schema/` at build time (`//go:embed` via a symlink or a `go generate` copy step; choose the one that works with `go build` from `server/` and document it in `server/README.md`).
- **Conformance test**: for every `schema/features/*/fixtures/*/*.json`, parse `projection`, `params`, `events`, `expected`; look up the projection in the registry; run; compare JSON structurally (ignore key order). Unknown projection in a fixture = test failure with a clear message.
- **alarmas projections**: implement `alarmas.alarmas_activas`, `alarmas.adherencia_tratamiento`, `alarmas.historial_dosis` per `schema/features/alarmas/SPEC.md` section 4, until all fixtures in section 7 pass. Write any missing fixtures first.
- **Blobs**: `POST /blobs/presign {key, content_type}` → presigned PUT URL for R2 using `aws-sdk-go-v2` with a custom endpoint. Keys are forced under `<user_id>/`. No download endpoint in phase 2.
- Config from env only: `CAUCE_ADDR`, `CAUCE_DB_PATH`, `CAUCE_USER_ID`, `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET`. Put them in `.env.example` with comments.
- Dockerfile is **not** needed. Document `go build -o cauce ./cmd/cauce` and the systemd unit in `docs/INFRA.md` (already drafted; adjust if your binary name/paths differ).

**Gate 2:** `make test-server` green including conformance; `make server-run` starts; a `curl` push + pull round-trip documented in `server/README.md` works. Remove `continue-on-error` from the server CI job. Commit.

---

## Phase 3 — Android (Kotlin + Compose)

Gradle, Kotlin DSL, version catalog (`gradle/libs.versions.toml`). minSdk 26, targetSdk 34. Modules:

```
android/
  settings.gradle.kts            include(":app", ":core:events", ":core:ui", ":core:sync", ":feature:alarmas")
  app/                           Application, FeatureRegistry (explicit list), NavHost, manual wiring
  core/events/                   Envelope (kotlinx.serialization), Room `events` table + outbox flag,
                                 EventStore (append, flow by types, pending for sync),
                                 Projection interface + registry, conformance test (JVM)
  core/ui/                       theme tokens + ~10 components: Screen, BigButton, NumberInput,
                                 ListRow, Card, Timer, EmptyState, ConfirmSheet, SectionHeader, Chip
  core/sync/                     WorkManager: PushPullWorker (outbox → POST push; GET pull → append
                                 with dedupe by id), cursor in DataStore, BlobUploadWorker (presign → PUT)
  feature/alarmas/               FeatureModule impl; domain/ (pure projections, JVM-only source);
                                 ui/ (list, editor, history); system/ (AlarmScheduler over AlarmManager,
                                 AlarmReceiver, BootReceiver, full-screen notification with actions)
```

Rules enforced by the build:

- `:feature:*` depends on `:core:*` only. Never on another `:feature:*`. Never on `:app`.
- `:core:*` never depends on `:feature:*` or `:app`.
- Projections live in a plain Kotlin source set with **no Android dependencies** so `./gradlew :feature:alarmas:test` runs on the JVM in seconds.

`FeatureModule` interface (in `:core:events`):

```kotlin
interface FeatureModule {
    val id: String
    val projections: List<Projection<*>>
    fun NavGraphBuilder.navGraph(nav: NavController)
    fun onAppStart(ctx: Context) {}   // e.g. alarmas reschedules from P1
}
```

Conformance test in `:core:events`: Gradle passes `-Dcauce.schemaDir=<rootDir>/../schema` to the test task; the test walks the fixtures exactly like the Go runner and dispatches through the registry assembled from all feature modules' `projections` (test-only aggregation in `:core:events` test source set, listing feature modules as `testImplementation`).

Event append validates the payload against the embedded JSON Schema in debug builds only (`networknt/json-schema-validator`), to catch agent mistakes early without paying at runtime in release.

**alarmas**: implement everything in `schema/features/alarmas/SPEC.md` section 5 (Android). Exact alarms via `setExactAndAllowWhileIdle`; request `SCHEDULE_EXACT_ALARM` with the settings-deeplink fallback on Android 12+; `fullScreenIntent` notification with "Hecho" and "Posponer 10 min" actions; `BOOT_COMPLETED` receiver reschedules from `alarmas_activas`.

**Gate 3:** `make test-android` green (fixtures pass in Kotlin too), `make apk` produces an APK, and the owner can install it and create/fire an alarm. Remove `continue-on-error` from the android CI job. Commit.

---

## Phase 4 — Web dashboard

Vite + TypeScript + Preact (small, no state library). Read-only. Structure:

```
web/src/core/        api client (fetch `/api/projections/...`), layout, auth-less (Cloudflare Access in front)
web/src/features/alarmas/   adherence table (P2), dose history (P3)
```

No projection logic in the browser: it calls the server endpoints from phase 2. `VITE_API_BASE` from env.

**Gate 4:** `npm run build` succeeds; dev server shows alarmas adherence against the local Go server. Commit.

---

## Phase 5 — Hand-off

- Write `docs/STATUS.md`: what exists, how to run each part, known gaps.
- Verify `make test` is green end to end.
- List, for the owner, the next three specs to write (see `docs/ROADMAP.md`) and any spec ambiguities you resolved during bootstrap (they must also be in each SPEC's section 8 with the `(agente)` prefix).

**Gate 5:** final commit, summary to the owner. Bootstrap is complete; all further work goes through `/feature` and `/new-feature`.
