---
name: new-feature
description: Scaffold a new feature from the SPEC template and interview the owner to fill it in, ONE question per round, each with full context. Use when the owner says /new-feature <id>, "quiero un feature para…", "agregá un módulo de…", or describes a new capability that has no directory under schema/features/. If the feature already exists, it first explains what it already does. Produces the SPEC.md and empty fixtures/ only; it does not write code — that is /feature.
---

# /new-feature <id>

Argument: a snake_case id. If the owner gave a description instead, propose an id and confirm. That confirmation is the first question, and it follows the format in "How to ask".

Talk to the owner in Spanish. Write the SPEC in Spanish, in plain language, as the template's comments describe.

## How to ask (applies to every question in this skill)

- **One question per round.** Ask it, then stop and wait for the answer. Never bundle two questions. Never move on without an answer. If an answer opens a new doubt, that doubt is the next round's question.
- **Every question carries its context.** The owner should be able to answer without opening any file. Use this shape:

  ```
  **Casillero N de 7 — <nombre>**   (o "Antes de empezar" en el paso 1)

  **Contexto**
  - Qué ya está escrito en la spec hasta ahora, en una línea por casillero.
  - Lo que el repo ya dice sobre este tema: el párrafo del ROADMAP, otros features que producen
    o consumen eventos relacionados (con sus nombres), reglas de AGENTS.md o ARCHITECTURE.md que
    aplican (p. ej. "la web también escribe", "eliminar = desactivar", "reaplicar tras el sync").
  - Un ejemplo corto tomado de alarmas, si ayuda a entender qué se espera.

  **Pregunta:** <una sola, concreta>

  **Mi propuesta:** <la respuesta que inferís del contexto, para que el dueño pueda decir "sí">
  Alternativas: <2 o 3, cuando las haya>
  ```

- **Inferred is not asked.** When the context already answers something, write it into the SPEC and list it under "Contexto" as `Asumí: …`, so the owner can correct it in any round without a separate question.
- **Write as you go.** After each answer, update `SPEC.md` right away. The next round's "Contexto" shows what was written.
- If the harness offers a structured question tool, use it: the proposal is the first option and the alternatives are the rest. The context still goes in the message text.

## 1. Check whether it exists

- Read `docs/ROADMAP.md` and `ls schema/features/`.
- **If `<id>` already exists**, do not scaffold anything. First tell the owner what the feature already does, read from its `SPEC.md` and its fixtures:
  - estado and versión spec, and the last changelog line;
  - the problem, in one line;
  - its rules, summarized in at most five bullets;
  - the events it produces, with their producers (app, web), and the events it consumes;
  - its projections, as name and what they show;
  - its surfaces: Android screens, device effects re-applied after sync, web views and admin actions;
  - how many fixtures it has, and what it explicitly does not do.

  Then ask ONE question, in the format above: "¿Qué querés hacer?" Proposal: run `/feature <id>` if the spec is ready. Alternatives: change the spec, which means interviewing only the casilleros that change, then bumping `versión spec` and adding a changelog line; or choose another id, if this is really a different feature.
- **If it is new but the ROADMAP describes it**, use that paragraph as the starting point and quote it in the first round's context.
- Also grep `schema/features/*/SPEC.md` for events whose names relate to this feature. Mention them in the context of casillero 3.

## 2. Scaffold

```
cp schema/features/_TEMPLATE/SPEC.md schema/features/<id>/SPEC.md
mkdir -p schema/features/<id>/fixtures
```

Set `id`, `estado: activo`, `versión spec: 1`, and today's date in the changelog. Do not ask about these; mention them as `Asumí:` in the first round.

## 3. Interview, casillero by casillero

Seven casilleros, in this order. Each one takes as many single-question rounds as it needs; a simple casillero may take one round.

1. **Problema** — what friction this removes, in one to three lines. If the owner cannot state it, say the feature may not be needed yet.
2. **Proceso de negocio** — numbered, verifiable rules. Push for edge cases one at a time: cancel, repeat, a day with no data, after a reinstall, an edit made from the web while the phone is offline.
3. **Eventos** — one per thing that *happens*, in Spanish snake_case and past tense (`gasto_registrado`), with typed fields and a producer (`app`, `web`, `server`, `worker:<n>`). Mark any event this feature needs from another feature under "Eventos consumidos", and check that the other feature's spec actually produces it.
4. **Proyecciones** — every screen or widget is a projection. Name its inputs, its `params` (always `now` if time matters, `tz` if local dates matter), its output shape, and which rules apply.
5. **Superficies** — Android screens, permissions (each with a one-line justification) and background work. Which device effects to re-apply after a sync pull (scheduled alarms, notifications, widgets, foreground services). Server workers. Web views (from projections) plus the web's admin actions: the events it produces (create, edit, deactivate, bulk load) and the bulk-load file format.
6. **Fixtures** — at least one case per projection, plus one per edge-case rule. List them; do not write the JSON unless the owner asks.
7. **Fuera de alcance** — what it will not do, so `/feature` does not over-build. Remind the owner that "eliminar" means deactivation unless this SPEC justifies a real delete.

## 4. Validate and hand off

- `python3 scripts/lint_contract.py` must pass. Missing event schemas make it fail: create minimal schemas for the events listed, or ask the owner (one question, with context) whether to defer.
- Show the finished SPEC.md and end with: "Cuando lo apruebes, corré `/feature <id>`."
- Do not implement anything.
