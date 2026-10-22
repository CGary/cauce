# schema/ — el contrato

Este directorio no tiene código. Tiene tres cosas y las tres son obligatorias para Kotlin y Go:

1. **`envelope.v1.json`** — el sobre de todo evento.
2. **`events/<tipo>.v<N>.json`** — un JSON Schema (draft 2020-12) por tipo y versión, que describe **solo el `payload`**.
3. **`features/<id>/`** — el `SPEC.md` del feature y sus `fixtures/`.

`python3 scripts/lint_contract.py` verifica la coherencia entre los tres. Corre en CI y debe correr antes de tocar código.

## Sobre (envelope)

```json
{
  "id": "uuid v7",
  "user_id": "u1",
  "device_id": "pixel-1 | web | server | worker:<nombre> | test",
  "type": "nombre_en_snake_case",
  "v": 1,
  "ts": "2026-10-22T13:05:00Z",
  "payload": { }
}
```

`ts` es cuándo ocurrió según el dispositivo, en UTC. El servidor agrega `server_seq` al recibir; no es parte del sobre que emite el dispositivo.

## Schemas de eventos

- Nombre de archivo = `<type>.v<v>.json`. El linter exige que exista uno por cada evento listado en la sección 3 de cada SPEC.
- `additionalProperties` queda **sin especificar** (permitido): ambos lados ignoran campos desconocidos. Eso es lo que hace posible el versionado aditivo.
- **Nunca** se edita un schema publicado de forma incompatible. Agregar un campo opcional: misma versión. Campo obligatorio nuevo, cambio de tipo o de significado: `<type>.v<N+1>.json`, y el SPEC lista ambas versiones mientras existan eventos viejos.
- Cada schema es autocontenido (sin `$ref` entre archivos) para que cualquier validador en Kotlin o Go lo cargue sin configurar base URIs. Si dos eventos del mismo feature comparten una estructura, se duplica en `$defs` de cada uno.
- El dueño de un schema es el feature que lo produce. Otro feature que lo consume lo declara en su SPEC (sección 3, "Eventos consumidos") y **no** lo modifica.

## Fixtures (tests de conformidad)

Un archivo por caso en `features/<id>/fixtures/<proyección>/<caso>.json`:

```json
{
  "projection": "alarmas.alarmas_activas",
  "params": { "now": "2026-10-22T13:00:00Z" },
  "events": [
    { "id": "e1", "type": "alarma_creada", "v": 1, "ts": "2026-10-20T10:00:00Z", "payload": { } }
  ],
  "expected": [ ]
}
```

- `projection` es `<feature>.<nombre>` y debe existir en el registro de proyecciones de ambos lados. Proyección desconocida = test que falla, no que se salta.
- `params` son los parámetros de la proyección. `now` es obligatorio para cualquier proyección que dependa del tiempo: las proyecciones **nunca** leen el reloj.
- En `events`, `id`, `type`, `v`, `ts` y `payload` son obligatorios; `user_id` y `device_id` se omiten y los runners aplican los defaults `"u1"` y `"test"`. Los `id` pueden ser cortos (`"e1"`) en fixtures; en producción son UUID v7.
- `expected` se compara estructuralmente (orden de claves irrelevante; orden de arrays relevante). Para floats, los runners comparan con tolerancia 1e-9.
- La sección 7 del SPEC lista los casos; el linter exige que cada caso listado exista como archivo y que cada archivo esté listado.
- El agente escribe los fixtures **antes** del código. Un fixture es la regla de negocio en forma ejecutable.

## Cómo se agrega un feature

`/new-feature <id>` copia `features/_TEMPLATE/SPEC.md` a `features/<id>/SPEC.md`, crea `fixtures/` vacío y el linter empieza a exigir coherencia desde ese momento. Un SPEC con `estado: eliminado` mantiene sus schemas (hay eventos históricos) pero el linter no exige fixtures ni implementación.
