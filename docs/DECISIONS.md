# Decisiones

Registro corto de decisiones con su porqué. Una entrada por decisión; si se revierte, se agrega una entrada nueva, no se borra la vieja. Los agentes pueden agregar **propuestas** marcadas `(propuesta)` pero no decidir.

## D1 — Android nativo (Kotlin + Compose), no cross-platform — 2026-10-22

La app es Android-only y casi todo lo difícil es trabajo de fondo del sistema (alarmas exactas, foreground service, WorkManager, Health Connect, widgets). Nativo lo tiene de primera mano; Expo/Flutter lo tienen por módulos de terceros con una capa más donde los agentes se pierden al depurar. El costo es no compartir código con la web; se resuelve con D3.

## D2 — Backend en Go, un binario, SQLite — 2026-10-22

Corre en un PC viejo con 20 MB de RAM, se cross-compila y se despliega con `scp`. SQLite + Litestream reemplaza Postgres + cron de backups para un usuario. Sin cgo (`modernc.org/sqlite`) para que el binario sea realmente estático.

## D3 — El contrato es el esquema y los fixtures, no código compartido — 2026-10-22

Kotlin y Go obedecen el mismo JSON Schema (forma) y pasan los mismos fixtures (comportamiento). Se descartó Kotlin Multiplatform para compartir con la web y un `core` en TypeScript: ambos agregan una capa de build para ahorrar unas pocas funciones puras que son fáciles de duplicar y verificar.

## D4 — Event log append-only, proyecciones puras — 2026-10-22

Cambiar el proceso de negocio es reinterpretar el log, no migrar datos. El sync se vuelve trivial (cursor + idempotencia por id). Las proyecciones puras son lo que hace posible D3.

## D5 — Local-first; el servidor es sync + blobs + workers — 2026-10-22

La app no puede depender de un PC que puede estar apagado. Además elimina latencia y la mayoría de los estados de carga.

## D6 — Un feature = un módulo Gradle / un paquete Go / un directorio en schema — 2026-10-22

El aislamiento lo impone el build, no una convención. Un agente que edita `feature/alarmas` no puede romper `feature/finanzas` porque no puede importarlo.

## D7 — Sin tablas de proyección materializadas — 2026-10-22

Un usuario, cientos de eventos por día: calcular en memoria es instantáneo. Se materializa cuando una medición lo pida, no antes.

## D8 — Idiomas: eventos y specs en español, código e instrucciones a agentes en inglés — 2026-10-22

Los eventos son vocabulario del negocio y los specs los escribe y lee el dueño. El código y las instrucciones a agentes siguen la convención del dueño para skills.

## D9 — Cloudflare Access en vez de auth propia — 2026-10-22

Un usuario. Toda la identidad pasa por una función (`auth.UserFromRequest`); si aparece un segundo usuario real, se reemplaza esa función y nada más.

## D10 — Blobs en R2 con presigned URLs, nunca a través del servidor — 2026-10-22

El PC viejo no debe ser cuello de botella de subidas ni guardar fotos en su disco. R2 no cobra egreso y es S3-compatible.

## D11 — La web es consola de administración (escribe), no un dashboard de solo lectura — 2026-10-22

Misma información en app y web; app para uso diario, web para administrar. La web deja de ser solo lectura y pasa a producir los mismos tipos de eventos que la app (crear, editar, desactivar, cargar en lote): los envía por `POST /sync/push` con `device_id: "web"`, sin outbox — si el push falla muestra el error y reintenta — y relee las proyecciones de `GET /api/projections/...`. Sigue sin lógica de proyecciones: el servidor calcula todas. El servidor valida cada evento empujado contra su schema y rechaza inválidos con 400, igual para app y web.

Trade-offs: la web ahora puede escribir datos del negocio, así que deja de ser inofensiva. Se protege con Cloudflare Access (login Google) y comparte identidad con la app (service token) vía `auth.UserFromRequest`; `/sync/push` acepta ambas. Un solo usuario, así que el conflicto se resuelve por `ts` (last write wins): las ediciones se modelan como eventos de estado completo, sin lógica de merge, y se acepta un pequeño skew de reloj. Las escrituras web dependen de sync (ROADMAP 3); hasta que no exista, las features de administración web se especifican pero no se implementan.
