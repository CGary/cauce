---
name: feature
description: Make the code converge on a feature's SPEC.md. Use when the owner says /feature <id>, "implement <id>", "apply the spec for <id>", or edits schema/features/<id>/SPEC.md and asks to update the app, server or web. The spec is the desired state; this skill reads it, writes fixtures first, then implements on every surface and runs the conformance tests in Kotlin and Go.
---

# /feature <id>

Argument: the feature id (directory name under `schema/features/`). If missing, ask.

## 1. Read

- `schema/features/<id>/SPEC.md`, whole file.
- `git diff HEAD~1 -- schema/features/<id>/` — what the owner changed. If the spec is new or the diff is empty, the task is "implement the spec as written".
- `AGENTS.md` ownership table: you may write only to `schema/events/`, `schema/features/<id>/`, `android/feature/<id>/`, `server/internal/<id>/`, `web/src/features/<id>/`.

## 2. Cross-feature check (stop condition)

For every event in SPEC section 3 that you will add, change or remove:

```
grep -l "`<tipo>`" schema/features/*/SPEC.md
```

If another feature lists it under "Eventos consumidos", **stop**. Tell the owner which feature consumes it and ask for that SPEC to be updated too. Both are then launched together, one commit.

## 3. Contract first

1. Create or update `schema/events/<tipo>.v<N>.json` for new or changed events. Never edit a published version incompatibly; bump N.
2. Write every fixture listed in section 7 under `schema/features/<id>/fixtures/<projection>/<case>.json`. Use `params.now` for time; never the clock. Derive expected values by hand from the rules in section 2 and 4 — do not run code to produce them.
3. `python3 scripts/lint_contract.py` must pass.

## 4. Implement

Order: server projections → Android projections (JVM, no Android deps) → Android UI/system + re-apply-after-pull → web admin console (views + admin forms that produce events). Run tests after each:

The web admin console reads projections and writes: its admin forms push events via `POST /sync/push` with `device_id: "web"`, then re-read the projections (no projection logic in the browser). Android re-applies device effects (scheduled alarms, notifications, widgets, foreground services) after a pull that brings events of this feature's types.

```
cd server && go test ./...
cd android && ./gradlew --quiet :core:events:test :feature:<id>:test
```

Both conformance runners must be green on all fixtures. A fixture failing on one side only means the two implementations disagree; fix the implementation, never the fixture, unless the fixture contradicts the spec.

If the spec is ambiguous, choose the simplest reading, implement it, and add a line to SPEC section 8 prefixed `(agente)`.

## 5. Finish

- If you changed behavior the owner did not already record, bump `versión spec` and add a changelog line.
- Build the debug APK only if UI changed; report the path, do not install.
- Commit: `<id>: <what changed>`.
- Summary: what converged, fixtures added, ambiguities resolved, anything left undone and why.
