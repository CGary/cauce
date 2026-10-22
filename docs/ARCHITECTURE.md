# Arquitectura

## 1. Local-first

El teléfono es el sistema de registro. Todo lo que el usuario hace se guarda primero en SQLite local como un evento, y la app responde al instante. El servidor recibe copias, guarda blobs y corre jobs. Si el servidor está apagado una semana, la app no se entera; cuando vuelve, se sincroniza.

Consecuencias de diseño:

- No hay "cargando…" en la app salvo para blobs ajenos.
- El servidor nunca es fuente de verdad de algo que el usuario escribió; solo lo es de lo que generan los workers (transcripciones, insights), y eso también llega al teléfono como eventos.
- La web lee del servidor y le escribe; puede ir unos minutos atrás del teléfono y no importa.

## 2. Event log

Una sola tabla `events` en ambos lados. Un evento es inmutable y tiene este sobre (`schema/envelope.v1.json`):

```json
{
  "id": "0192e4a1-7f3c-7a2b-9c1d-4e5f6a7b8c9d",   // UUID v7: ordenable por tiempo
  "user_id": "u1",
  "device_id": "pixel-1",                   // o "web", "server", "worker:whisper"
  "type": "dosis_dada",
  "v": 1,
  "ts": "2026-10-22T13:05:00Z",                    // cuándo ocurrió, UTC
  "payload": { "...": "campos propios del tipo" }
}
```

Reglas:

- **No se mutan ni borran eventos.** Un error se corrige con otro evento (`gasto_anulado`, `alarma_modificada`).
- **Las vistas son proyecciones puras**: `(events, params) → resultado`. Sin reloj (la hora entra en `params.now`), sin I/O, sin aleatoriedad. Por eso son testeables con fixtures idénticos en Kotlin y Go.
- **Idempotencia por `id`.** El sync puede reenviar; el receptor ignora ids repetidos. Las proyecciones que agregan deben ser robustas a duplicados lógicos cuando el spec lo pida (p. ej. una dosis cuenta una vez por `programada_para`).
- **Versionado aditivo.** `tipo.v1` nunca se edita de forma incompatible. Campo opcional nuevo: misma versión. Cambio de significado o campo obligatorio nuevo: `tipo.v2`. Ambos lados ignoran campos desconocidos.

Con un usuario, el volumen es trivial (ubicación cada 10 min ≈ 150 eventos/día; todo lo demás, decenas). Las proyecciones se calculan en memoria desde los eventos del tipo que necesitan; no hay tablas de proyección materializadas hasta que una medición diga que hacen falta.

## 3. Sync

Protocolo mínimo, un usuario, varios productores (teléfono, web, server, workers):

```
POST /sync/push   { "events": [Envelope, ...] }
                  → { "accepted": n, "duplicates": m }        400 si alguno no valida contra schema

GET  /sync/pull?since=<server_seq>
                  → { "events": [Envelope + server_seq, ...], "next_since": k }   máx. 500 por página
```

- `server_seq` es un entero monótono asignado por el servidor al recibir. Es el cursor; el teléfono guarda el último que vio.
- El servidor valida cada evento empujado contra `schema/events/<tipo>.vN.json` y rechaza con 400 si alguno no valida. Misma regla para la app y para la web.
- El teléfono mantiene un **outbox**: eventos locales con `synced = 0`. `PushPullWorker` (WorkManager, con red, cada 15 min y al abrir la app) hace push del outbox, luego pull desde el cursor, y deduplica por `id` al insertar. Hay además un botón manual "Sincronizar".
- La web no tiene outbox: envía a `POST /sync/push` con `device_id: "web"`, genera el `id` (UUID v7) y los ids de entidad, y el `ts` sale del reloj del navegador. Si el push falla, muestra el error y el usuario reintenta. Tras un push, relee las proyecciones de `GET /api/projections/...`.
- Cómo llegan al teléfono los eventos escritos por la web: en el próximo pull (cada 15 min o al abrir la app). No hace falta que lleguen al instante. Todo feature con efectos de dispositivo (alarmas programadas, notificaciones, widgets, foreground services) debe re-aplicar esos efectos tras un pull que traiga eventos de sus tipos; es una regla que cada SPEC declara.
- Conflictos: un solo usuario, así que **last write wins por `ts`**. Los features modelan las ediciones como eventos de estado completo (como `alarma_modificada`: el estado entero, no un diff), de modo que no hay lógica de merge. Un pequeño skew de reloj entre dispositivos se acepta.

## 4. Blobs (audio, fotos)

Los archivos **no** van en la base. Van a **Cloudflare R2** (S3-compatible, 10 GB gratis, sin costo de egreso).

1. La app guarda el archivo local y emite el evento (p. ej. `nota_audio_creada { key: "u1/2026/10/22/<uuid>.m4a", duracion_s }`). La nota ya existe y se puede ver.
2. `BlobUploadWorker` pide `POST /blobs/presign { key, content_type }`, recibe una URL PUT firmada (válida 15 min) y sube con WorkManager (solo wifi por defecto, con reintentos).
3. El servidor fuerza que `key` empiece con `<user_id>/`.

Fotos se reducen en el teléfono antes de subir (lado largo 1600 px, JPEG 85). Audio en AAC/m4a mono 32 kbps: una nota de un minuto pesa ~250 KB.

## 5. Workers (IA y jobs)

Un worker es una goroutine con `time.Ticker` que implementa:

```go
type Worker interface {
    Name() string
    Run(ctx context.Context) error   // una pasada; el loop está afuera
}
```

Patrón: **leer eventos pendientes → hacer algo → escribir eventos nuevos**. El worker nunca toca la app ni la web; sus resultados llegan como eventos por el sync normal.

Ejemplos previstos:

| Worker | Lee | Escribe | Cómo |
|--------|-----|---------|------|
| `whisper` | `nota_audio_creada` sin `transcripcion_agregada` | `transcripcion_agregada { nota_id, texto, modelo }` | descarga de R2, `exec` whisper.cpp |
| `resumen_diario` | eventos del día anterior, agregados | `insight_generado { fecha, texto }` | llamada a API de LLM con resumen compacto |

Idempotencia: cada worker busca "evento A sin evento B correspondiente", así una corrida interrumpida se retoma sola.

## 6. Consola de administración web

La app y la web contienen la misma información; la app es para uso diario y la web para administrar. La web es una SPA estática en Cloudflare Pages, protegida por Cloudflare Access (login Google), que además de leer **escribe**: produce los mismos tipos de eventos que la app — crear, editar, desactivar y cargar en lote las entidades de cada feature.

- Envía eventos por `POST /sync/push` con `device_id: "web"` (ver §3). No tiene lógica de proyecciones: todas las proyecciones las calcula el servidor, y la web las lee con `GET /api/projections/{feature}/{name}?param=...`, que ejecuta la misma función que pasó los fixtures. Así la web nunca puede divergir de la app.
- Eliminar es, por defecto, baja lógica (evento de desactivación), porque la historia nunca se borra. Un feature puede definir un evento de borrado real solo si su SPEC lo justifica.
- Cargar en lote: cada feature que soporta "cargar" define su formato de archivo en su SPEC; la web lo convierte en N eventos de creación. Se prefiere un formato estándar existente cuando lo hay.
- Las escrituras de la web dependen de sync (ROADMAP 3). Hasta que sync exista, las features de administración web se especifican pero no se implementan.

## 7. Módulos y aislamiento

```
android/
  core/events   core/ui   core/sync        ← compartido, sin lógica de negocio
  feature/alarmas  feature/finanzas  …    ← Gradle prohíbe que un feature importe otro
server/
  internal/core/*  internal/sync  …        ← compartido
  internal/alarmas  internal/finanzas  …   ← sin imports entre features
```

Un feature se comunica con otro **solo** produciendo o consumiendo eventos, y esa dependencia está declarada en su SPEC (sección 3, "Eventos consumidos"). Es lo que permite lanzar dos specs juntos cuando hace falta y nunca tres.

## 8. Autenticación

Un usuario, dos credenciales, una identidad. La web entra con Cloudflare Access (login con Google) y la app con un service token. Ambas llegan al servidor como el mismo usuario: `auth.UserFromRequest` las mapea y `/sync/push` acepta las dos. Internamente devuelve `CAUCE_USER_ID` de la configuración. Es la única función que cambiaría si hubiera auth real; nada más del código sabe cómo se identifica un usuario.

## 9. Lo que deliberadamente no hay

- Sin Postgres (SQLite + Litestream alcanza; cambiar el driver es un cambio local a `core/store`).
- Sin colas, sin Lambda, sin contenedores en producción.
- Sin server-driven UI ni sistema de temas: la flexibilidad visual viene de un kit de UI chico que el agente recombina.
- Sin CRDTs ni librerías de sync: el log append-only con cursor es suficiente para un usuario.
- Sin tablas de proyección materializadas hasta que una medición lo justifique.
