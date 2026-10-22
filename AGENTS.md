# AGENTS.md

Rules for any AI agent working in this repository. Human-facing docs are in Spanish; this file, `BOOTSTRAP.md` and every `SKILL.md` are in English. Event type names and spec prose are in Spanish because they are business vocabulary; code identifiers are in English.

## What this repo is

A single-user Android app (Kotlin/Compose) plus a Go server, built local-first on an append-only event log. `schema/` is the contract; `android/`, `server/` and `web/` are three implementations of it. Read `README.md` and `docs/ARCHITECTURE.md` once; read `schema/README.md` before touching any event or fixture.

## The one workflow: spec → fixtures → code

The spec describes the **desired state**, never a change request. Your job is to make the code converge on the spec.

1. Read `schema/features/<id>/SPEC.md` in full.
2. Run `git diff HEAD~1 -- schema/features/<id>/` to see what the owner changed. If nothing changed, the task is "make code match spec as written".
3. Write or update the fixtures listed in SPEC section 7 **before** writing any Kotlin or Go. Fixtures are the acceptance criteria.
4. Run `python3 scripts/lint_contract.py`. It must pass before you touch code.
5. Implement. Touch only the paths owned by this feature (see Ownership).
6. Run the conformance tests on both sides (`make test`). Both must be green.
7. Bump `versión spec` and add a changelog line in SPEC.md if you changed behavior that the owner did not already record.
8. Summarize what converged, what you could not do, and any spec ambiguity you resolved (and how).

## Ownership — where a feature may write

| Surface | Path |
|---------|------|
| Contract | `schema/events/<tipo>.vN.json`, `schema/features/<id>/**` |
| Android | `android/feature/<id>/**` |
| Server | `server/internal/<id>/**` |
| Web | `web/src/features/<id>/**` |

Shared code (`android/core/**`, `server/internal/core/**`, `server/internal/sync/**`, `web/src/core/**`) is **not** owned by any feature. Change it only when the task is explicitly about the core, and say so in your summary. If a feature seems to need a core change, stop and ask; the usual answer is that the feature should do it locally.

The web push client (UUID v7 ids, `ts` from the browser clock, error handling) is shared and lives in `web/src/core/`. A feature writes its admin forms and its own event builders under `web/src/features/<id>/`; if it needs a capability the push client does not have, stop and ask.

## Hard rules

- **Never edit a published event schema.** A breaking change is a new file `<tipo>.v(N+1).json`. Additive optional fields are allowed on the same version. Both app and server must ignore unknown fields.
- **Cross-feature events.** Before changing any event in SPEC section 3, grep `schema/features/*/SPEC.md` for consumers. If another feature lists it under "Eventos consumidos", STOP and ask for that feature's spec. The two are launched together.
- **The web is a producer, not a mirror.** Admin actions in the web are events, not local UI state: build the envelope, push it with `POST /sync/push` (`device_id: "web"`), then re-read the projections. The server validates every pushed event against its schema and rejects invalid ones with 400. Never re-implement projection logic in the browser to make a form look right.
- **Edits are full-state events.** App and web both write, so conflicts resolve by newest `ts`. Model an edit as the complete state (like `alarma_modificada`), not a diff, so last-`ts`-wins needs no merge logic. Small clock skew between devices is accepted; do not add CRDTs.
- **"Delete" means deactivate.** The default is a deactivation event, because history is never erased. A real delete event only exists if the feature's SPEC section 8 justifies it.
- **Re-apply device effects after a pull.** Events written in the web reach the phone on the next pull (`PushPullWorker`, or the "Sincronizar" button). A feature with device-side effects (scheduled alarms, notifications, widgets, foreground services) re-applies them after a pull that brings events of its types. SPEC section 5 states which ones; it is not optional.
- **Web writes depend on sync** (ROADMAP item 3). Until `/sync/push` exists in the server, specify the web admin actions in SPEC section 5 and do not implement them.
- **Projections are pure.** `(events, params) -> result`. No clock, no random, no I/O. Time comes in as `params.now`. This is what makes fixtures deterministic.
- **Do not add dependencies between feature modules.** Gradle enforces it on Android; keep Go packages flat under `internal/<id>` with no imports between features. Features talk only through events.
- **No new infrastructure.** No Lambda, no managed queues, no extra databases. The server is one Go binary with SQLite; workers are goroutines. If you believe infra is required, write the reason in `docs/DECISIONS.md` as a proposal and stop.
- **Spec ambiguity.** Pick the simplest reading, implement it, and record the reading you took in SPEC section 8 ("Fuera de alcance y decisiones") with the prefix `(agente)`. Do not silently invent behavior.
- **Secrets** never go in the repo. Use `.env` (gitignored) and document variables in `server/.env.example`.

## Verification before you say "done"

- `python3 scripts/lint_contract.py` passes.
- `cd server && go test ./...` passes.
- `cd android && ./gradlew :core:events:test :feature:<id>:test` passes (JVM only; no emulator required).
- If the feature has web admin actions and `web/` is bootstrapped, `npm run build` in `web/` passes.
- If you changed UI, build the debug APK once (`./gradlew :app:assembleDebug`) and report the path. Do not install anything.

## Style

- Kotlin: official style, Compose, no DI framework (manual wiring in `:app`), coroutines + Flow, Room for the event store, kotlinx.serialization for JSON.
- Go: standard library `net/http` (Go ≥ 1.22 routing), `modernc.org/sqlite` (no cgo), `sqlc` for queries, one package per feature, table-driven tests.
- Web: Vite + React 19 + TypeScript (`strict`, `noUncheckedIndexedAccess`), TanStack Query for all data access, React Router, Tailwind v4. No component library, no state manager, no form library, no SSR. Chart.js/MapLibre only via per-route `React.lazy`. Tests: Vitest + Testing Library + MSW (fixtures as data), one Playwright smoke test. ESLint (typescript-eslint, react-hooks) + Prettier. Exact version pins. It is an admin console over the same data as the app: it reads projections and writes events (create, edit, deactivate, bulk load) via `POST /sync/push`. All projection logic lives server-side. See BOOTSTRAP Phase 4.
- Keep everything smaller than you think it should be. One user. Defer complexity until a real need shows up.

## Commit messages

`<scope>: <what changed>` where scope is the feature id or `core`, `schema`, `server`, `android`, `web`, `docs`. One feature per commit. Fixtures and the code that satisfies them go in the same commit.
