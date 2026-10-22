---
name: new-feature
description: Scaffold a new feature from the SPEC template and interview the owner to fill it in. Use when the owner says /new-feature <id>, "quiero un feature para…", "agregá un módulo de…", or describes a new capability that has no directory under schema/features/. Produces the SPEC.md and empty fixtures/ only; it does not write code — that is /feature.
---

# /new-feature <id>

Argument: a snake_case id. If the owner gave a description instead, propose an id and confirm.

## 1. Check it is new

- `ls schema/features/` — if `<id>` exists, say so and offer `/feature <id>` instead.
- Read `docs/ROADMAP.md`; if the roadmap already describes this feature, use that paragraph as the starting point and say so.

## 2. Scaffold

```
cp schema/features/_TEMPLATE/SPEC.md schema/features/<id>/SPEC.md
mkdir -p schema/features/<id>/fixtures
```

Set `id`, `estado: activo`, `versión spec: 1`, and today's date in the changelog.

## 3. Interview, section by section

Ask only what you cannot infer; keep it to a few questions per round. Fill the spec in Spanish, plain language, as the template's comments describe.

1. **Problema** — what friction this removes. One to three lines. If the owner cannot state it, say the feature may not be needed yet.
2. **Proceso de negocio** — numbered, verifiable rules. Push for edge cases: what happens on cancel, on repeat, on a day with no data, after a reinstall.
3. **Eventos** — one per thing that *happens*, in Spanish snake_case, past tense (`gasto_registrado`). Fields with types. Mark any event this feature needs from another feature under "Eventos consumidos" — then check that feature's spec actually produces it.
4. **Proyecciones** — every screen or widget is a projection. Name inputs, `params` (always `now` if time matters, `tz` if local dates matter), output shape, and which rules apply.
5. **Superficies** — Android screens/permissions/background work, and which device effects to re-apply after a sync pull (scheduled alarms, notifications, widgets, foreground services); server workers; web views (from projections) plus the web's admin actions (the events it produces: create, edit, deactivate, bulk load — and the bulk-load file format). Each Android permission gets a one-line justification.
6. **Fixtures** — at least one case per projection plus one per edge-case rule. List them; do not write the JSON yet unless the owner asks.
7. **Fuera de alcance** — what it will not do, so `/feature` does not over-build.

## 4. Validate and hand off

- `python3 scripts/lint_contract.py` must pass (missing event schemas will fail — create minimal schemas for the events listed, or ask the owner whether to defer).
- Show the finished SPEC.md and end with: "Cuando lo apruebes, corré `/feature <id>`."
- Do not implement anything.
