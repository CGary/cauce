# Cauce — bitácora personal local-first para ordenar días, finanzas y rutinas

App Android a medida que registra todo lo que hacés como eventos y los convierte en orden: alarmas, bolsas de gasto, rutinas, notas y ubicación. Primero en tu teléfono, después en tu servidor. Kotlin + Go, spec-driven, construida por agentes.

## Principios

1. **Local-first.** Los datos viven en el teléfono (SQLite). El backend solo sincroniza, guarda blobs y corre jobs de IA. Si el servidor está apagado, la app funciona igual.
2. **Event log.** Nada se muta; todo es un evento inmutable (`gasto_registrado`, `dosis_dada`, `ubicacion_registrada`). Las vistas se derivan con proyecciones puras. Cambiar el proceso de negocio es reinterpretar el log, no migrar datos.
3. **Un feature = un directorio, un spec, un contrato.** Cada feature tiene su `schema/features/<id>/SPEC.md` como única fuente de verdad. Android (Kotlin) y server (Go) obedecen el mismo JSON Schema y pasan los mismos fixtures de conformidad.
4. **Infra barata y reemplazable.** PC viejo + Cloudflare Tunnel + R2. Cero servicios pagos de AWS. Cada pieza se puede cambiar sin tocar el contrato.

## Estructura del repo

```
schema/              ← EL CONTRATO. Sin código. Schemas de eventos, specs y fixtures.
  envelope.v1.json     sobre común de todo evento
  events/              un JSON Schema por tipo de evento y versión
  features/<id>/       SPEC.md + fixtures/<proyección>/<caso>.json
android/             ← Kotlin + Jetpack Compose. Módulos Gradle por feature.
server/              ← Go. Un binario: API de sync + workers.
web/                 ← Consola de administración (Cloudflare Pages). Lee y escribe.
docs/                ← Arquitectura, infra, decisiones, roadmap.
scripts/             ← Linter del contrato y utilidades.
.claude/skills/      ← Skills para Claude Code (/feature, /new-feature).
```

## Cómo se trabaja

El spec describe el **estado deseado**, no el cambio. Para modificar un feature:

1. Editás `schema/features/<id>/SPEC.md` (reglas de negocio, eventos, proyecciones, fixtures).
2. Le pedís al agente: `/feature <id>` ("hacé que el código cumpla el spec").
3. El agente escribe primero los fixtures, luego hace converger Android, server y web, y corre los tests de conformidad en Kotlin y Go.

Si un cambio toca un evento que otro feature consume, el agente se detiene y pide el segundo spec. Se lanzan juntos.

## Estado

Repositorio recién inicializado. El código de `android/`, `server/` y `web/` todavía no existe: lo genera el agente siguiendo `BOOTSTRAP.md`.

## Documentos

- [`BOOTSTRAP.md`](BOOTSTRAP.md) — instrucciones para que Claude Code inicialice el proyecto (en inglés, es para el agente).
- [`AGENTS.md`](AGENTS.md) — reglas para cualquier agente que toque el repo.
- [`schema/README.md`](schema/README.md) — reglas del contrato: schemas, fixtures, versionado.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — local-first, event log, sync, blobs, workers.
- [`docs/INFRA.md`](docs/INFRA.md) — PC viejo, Cloudflare Tunnel/Access/R2/Pages, Litestream, whisper.cpp.
- [`docs/DEPLOY-OPTIPLEX.md`](docs/DEPLOY-OPTIPLEX.md) — encargo: estado del servidor `optiplex` y pasos para desplegar ahí.
- [`docs/DECISIONS.md`](docs/DECISIONS.md) — decisiones de diseño y su porqué.
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — orden de construcción.

## Convención de idiomas

Documentos para humanos (README, docs/, SPEC.md): español. Instrucciones para agentes (AGENTS.md, BOOTSTRAP.md, SKILL.md): inglés. Código, identificadores y nombres de eventos: los eventos van en español (`gasto_registrado`) porque son vocabulario del negocio; el código en inglés.
