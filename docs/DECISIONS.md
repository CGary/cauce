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

Misma información en app y web; app para uso diario, web para administrar. La web deja de ser solo lectura y pasa a producir los mismos tipos de eventos que la app (crear, editar, desactivar, cargar en lote): los envía por `POST /sync/push` con `device_id: "web"`, sin outbox — si el push falla muestra el error y reintenta — y relee las proyecciones de `GET /api/projections/...`. Sigue sin lógica de proyecciones: el servidor calcula todas. El servidor valida cada evento empujado contra su schema y rechaza inválidos con 400, igual para app y web. Reemplaza la descripción anterior del dashboard como de solo lectura (ARCHITECTURE §6, web/README).

Trade-offs: la web ahora puede escribir datos del negocio, así que deja de ser inofensiva. Se protege con Cloudflare Access (login Google) y comparte identidad con la app (service token) vía `auth.UserFromRequest`; `/sync/push` acepta ambas. Un solo usuario, así que el conflicto se resuelve por `ts` (last write wins): las ediciones se modelan como eventos de estado completo, sin lógica de merge, y se acepta un pequeño skew de reloj. Las escrituras web dependen de sync (ROADMAP 3); hasta que no exista, las features de administración web se especifican pero no se implementan.

## D12 — Las claves de blobs deben venir bajo `<user_id>/`; no se prefijan — 2026-10-22

(agente) El servidor rechaza con 400 cualquier clave de `POST /blobs/presign` que no empiece con `<user_id>/`, en vez de anteponerle el prefijo. Prefijar en silencio hace que el cliente crea que subió a una ruta distinta de la real y esconde un error de programación; rechazar mantiene una sola forma de construir la clave y deja el aislamiento por usuario en una validación explícita.

## D13 — Docker Compose como empaquetado portable — 2026-10-23

El servidor corre en Docker Compose para poder moverlo entre máquinas Linux con un `git clone` y un comando de arranque. Docker es empaquetado, no infraestructura nueva: el estado sigue estando únicamente en SQLite y `server/.env`. Esto reemplaza el enfoque anterior de binario + systemd en el PC viejo y supersede el deploy por `scp` del binario descrito en D2: ahora se mueve un tarball con la base y el entorno, no el ejecutable. El túnel ya corre en Compose (perfil `tunnel`); Litestream se moverá más adelante.

Trade-offs: Docker agrega una dependencia de empaquetado (imagen, Compose y permisos de UID/GID) a cambio de un arranque reproducible en cualquier Linux con `git clone` y `just up`. La imagen no guarda estado: la base vive en `deploy/data/` y la configuración en `server/.env`, así que el backup sigue siendo un tarball de esos dos. Compose fuerza `CAUCE_ADDR` y `CAUCE_DB_PATH` para que un `.env` de desarrollo no rompa el contenedor. El túnel ya corre en Compose; Litestream todavía no.

## D14 — La web llama a la API por mismo origen, con una Pages Function — 2026-10-30

Contexto: los navegadores de quien tiene activo el cliente VPN del dueño resuelven el host de la API a una dirección privada y bloquean la llamada directa como acceso a red local; además, la llamada entre orígenes dependía de CORS.

Decisión: la web pide `/api/*` y `/sync/*` a su propio origen. Una Cloudflare Pages Function (`web/functions/api/[[path]].ts`, reutilizada por `web/functions/sync/[[path]].ts`) reenvía la petición al origen que indica la variable de entorno `API_ORIGIN` del proyecto de Pages. El build de producción usa `VITE_API_BASE` vacío. El CORS del servidor Go (`CAUCE_CORS_ORIGIN`) queda solo como respaldo para acceso directo.

Consecuencias: el navegador nunca llama al host de la API, así que desaparecen el bloqueo de red local y la dependencia de CORS. Paso operativo: `API_ORIGIN` se define en el proyecto de Pages (`wrangler pages secret put API_ORIGIN --project-name <proyecto>` o el panel), nunca en el repo; cambiarlo exige un nuevo deploy, no un rebuild. Detalle en `web/README.md`.

## D15 — El APK debug exige `cauceSyncBaseUrl`; `just apk` falla si falta — 2026-10-30

Contexto: `android/app/build.gradle.kts` lee la propiedad de Gradle `cauceSyncBaseUrl`; sin ella el build cae al host del emulador y el teléfono nunca sincroniza. Pasó el 2026-10-29: 63 eventos quedaron sin sincronizar hasta recompilar.

Decisión: la propiedad vive en `~/.gradle/gradle.properties` del desarrollador, fuera del repo, porque su valor es el hostname privado del servidor. La receta `apk` del `justfile` falla de inmediato si no encuentra la propiedad (ni en ese archivo ni en la variable `CAUCE_SYNC_BASE_URL`, que se pasa como `-PcauceSyncBaseUrl`). Se documenta en `android/README.md`.

Consecuencias: un APK sin destino de sync ya no se puede producir por `just apk` por descuido. Compilar con `./gradlew` directo no tiene la guarda.

## D16 — Los umbrales de adherencia son presentación web, no contrato — 2026-10-30

Contexto: la proyección de adherencia entrega solo conteos (`dadas` / `esperadas`); el SPEC de `alarmas` no define etiquetas ni umbrales.

Decisión: los umbrales (Al día ≥ 90 %, Revisar 70–89 %, Baja < 70 %) son una decisión de presentación de la web. El servidor no expone ninguno y Android muestra solo los números crudos con una leyenda de las definiciones.

Consecuencias: cambiarlos es un cambio solo de web. Volverlos parte del contrato exigiría un cambio de SPEC (P2) más fixtures en ambos lados.

## D17 — Navegación Android: Home es el hub; Nueva alarma en la barra superior — 2026-10-30

Contexto: `core` define `Screen` sin ranura para FAB y los features no tocan `core`; los features no pueden depender entre sí en Gradle.

Decisión: Home sigue siendo el hub (tarjetas con icono y subtítulo), sin barra de navegación inferior. «Nueva alarma» es una acción de la barra superior. Las filas de reuniones de Google son de solo lectura en la lista de alarmas, se muestran plegadas bajo «Reuniones de Google», y tocarlas abre el editor de aviso mediante una lambda inyectada desde `:app`.

Consecuencias: ningún cambio en `core` ni dependencia entre módulos de feature. Si se quiere un FAB o navegación inferior habrá que cambiar `core` en una tarea propia.

## D18 — Retroalimentación de sync: «Último evento» en Android, «Actualizado» en la web — 2026-10-30

Contexto: `core` no expone la fecha de la última sincronización, solo el cursor `server_seq`.

Decisión: Android muestra «Último evento: hace X» con el `ts` del evento local más reciente. La web muestra «Actualizado hace X» a partir de `dataUpdatedAt` de TanStack Query, y tras un push avisa que el teléfono lo aplica en su próximo pull.

Consecuencias: limitación conocida: ninguno de los dos es la hora real de la última sincronización. Agregarla es un cambio de `core` para una tarea futura. (Resuelta por D22.)

## D19 — Política de reintentos de consultas en la web — 2026-10-30

Contexto: reintentar sin criterio oculta errores reales (4xx) o retrasa el aviso de una caída.

Decisión: las consultas reintentan como máximo 2 veces con backoff exponencial; no reintentan ante 4xx; ante errores de red hacen un solo reintento. El panel de error muestra una causa breve y deja el detalle técnico plegado.

Consecuencias: un 4xx se muestra de inmediato; una caída del servidor aparece en pocos segundos con su causa.

## D20 — Dos cerrojos: token compartido en el servidor, login humano solo en el borde — 2026-11-19

Contexto: la API respondía sin autenticación. `GET /sync/pull?since=0` por la URL pública de Pages devolvía el log completo, y el origen público del servidor (el que usa el teléfono) estaba igual de abierto. `auth.UserFromRequest` ignoraba la petición y la Pages Function reenviaba sin verificar nada.

Decisión: dos cerrojos independientes.

1. **Servidor: un secreto compartido.** `CAUCE_API_TOKEN` (mínimo 32 caracteres). Toda ruta salvo `GET /healthz` exige `Authorization: Bearer <token>`, comparado en tiempo constante; si no, `401` JSON con `WWW-Authenticate: Bearer`. Es un único middleware en `server/internal/auth`, montado una vez en `cmd/cauce`. Falla cerrado: sin token el servidor no arranca, salvo `CAUCE_AUTH=off` explícito (desarrollo local y tests aislados); al arrancar registra en una línea qué modo está activo, nunca el token.
2. **Teléfono:** la propiedad de Gradle `cauceApiToken` (como `cauceSyncBaseUrl`, D15: en `~/.gradle/gradle.properties`, nunca en el repo; el build del APK falla sin ella). `core/sync` la manda en cada llamada; un 401 se muestra en Home bajo «Último evento» (D18).
3. **Web:** Cloudflare Access (login Google) delante de Pages, verificado en la Function: cada petición a `/api/*` y `/sync/*` trae un JWT de Access válido (RS256 con las claves del equipo, `aud`, `iss`, `exp`, `nbf`, email opcional) o recibe `403` sin tocar el origen. Si es válido, la Function agrega `Authorization: Bearer <API_TOKEN>` (secreto de Pages) y no reenvía la `Authorization`, las cookies ni las cabeceras `Cf-Access-*` del navegador. No basta con que Access esté delante: cada deploy de Pages tiene también su hostname `<hash>.<proyecto>.pages.dev`. El token nunca entra al bundle; en desarrollo lo agrega el proxy de Vite y el build falla si aparece en `dist/`.

Cómo refina D9: el token compartido es el «service token» de D9 y D11, pero lo valida el propio servidor, no Access, así que el servidor deja de confiar en la red. `auth.UserFromRequest` sigue siendo la única costura de identidad: pasar el token prueba que quien llama es un cliente del dueño, y la identidad sigue siendo `CAUCE_USER_ID`. El login humano vive solo en el borde (la Function); cambiar Google/Access por otro proveedor es un cambio de `web/functions/` y no toca servidor ni teléfono.

Consecuencias: un token para todos los clientes, así que rotarlo exige actualizar a la vez `server/.env`, el APK y el secreto de Pages (procedimiento en `docs/DEPLOY-OPTIPLEX.md`, «Autenticación»). El token queda dentro del APK (como la URL de D15): quien tenga el APK lo tiene. Con un solo usuario es aceptable; si no, la salida es un token por cliente en el mismo middleware.

## D21 — `cauce-cli`: un cliente de terminal para agentes, en Go, que escribe con `device_id: "cli"` — 2026-11-24

Contexto: los agentes (Claude u otros) solo podían tocar alarmas abriendo la web o leyendo la base a mano. Hace falta una interfaz estable para que un agente vea, cree, edite y desactive alarmas, avisos de reuniones y adherencia sin saltarse las reglas del contrato. El diseño está en `docs/PLAN-CLI.md` y sigue `docs/GUIA-CLI-AGENTES.md`.

Decisión (las seis del plan, aprobadas por el dueño):

1. **Nombre:** el binario se llama `cauce-cli`; `cauce` ya es el servidor.
2. **Lenguaje y lugar:** Go, en `server/cmd/cauce-cli/` y `server/internal/cli/`, dentro del mismo módulo del servidor. Reusa el validador de schemas (`internal/core/event`) y valida cada evento antes de enviarlo. Es código de la CLI, no de ningún feature (tabla de propiedad de `AGENTS.md`); importa los paquetes compartidos y no los modifica.
3. **`device_id`:** `"cli"`. El servidor no restringe el valor y el historial distingue qué vino de un agente.
4. **Regla del remedio:** la aplica la CLI, igual que el formulario web (`isScheduleLocked`, `remedioCorrectionPayload` en `web/src/features/alarmas/model.ts`): editar un `remedio` solo corrige título, remedio y destinatario; cualquier otro cambio falla con `REMEDIO_SCHEDULE_LOCKED`. El servidor no cambia: el push valida cada evento por separado, sin historial, y rechazar ahí exigiría leer estado dentro de `sync` (código compartido) y podría atascar la cola del teléfono, que sube todo en un solo lote.
5. **Token:** el mismo token de D20, leído solo de `~/.config/cauce-cli/token` (o `--token-file`), nunca de un flag ni de una variable de entorno. La CLI rechaza un archivo legible por otros usuarios y nunca imprime el token, ni con `--debug`. El modo 600 protege de otros usuarios del sistema, no del agente que corre con el mismo usuario: frente al agente, la protección es que la CLI no lo muestra y la skill prohíbe leer el archivo.
6. **Desactivar:** exige `--apply --yes`, porque no hay «reactivar»: para volver atrás hay que crear otra alarma.

La CLI es un cliente más, como la web: lee `GET /api/projections/<feature>/<name>` y escribe con `POST /sync/push`. Las escrituras exigen `--dry-run` (guarda un plan en `~/.local/state/cauce-cli/plans/`, vence a las 24 h) o `--apply`. El `ts` se pone al aplicar, no al planificar, y el plan guarda el `id` del evento: aplicarlo dos veces manda el mismo `id` y el servidor lo deduplica. Un plan de edición vuelve a leer la alarma al aplicarse y falla con `CONFLICT` si cambió desde el teléfono o la web.

Consecuencias: `alarmas` y `calendario_google` listan `cli` como productor de los eventos que escribe (`alarma_creada`, `alarma_modificada`, `alarma_desactivada`, `aviso_reunion_editado`). Rotar el token de D20 ahora incluye también el archivo de la CLI (`docs/DEPLOY-OPTIPLEX.md`, «Rotar el token»). Las reglas de validación que el schema no expresa (topes de `count` e `interval`, fecha pasada, `until` antes del inicio, reanclaje de una edición en el próximo disparo) quedan duplicadas en la web, la app y la CLI; si cambian en el SPEC, cambian en las tres.

## D22 — Sync visible: el servidor anota cuándo sincronizó cada dispositivo — 2026-11-26

Contexto: D18 dejó como limitación conocida que ni la app ni la web muestran la hora real de la última sincronización, y nadie (web, CLI) puede saber si un cambio ya llegó al teléfono. Hace falta para confiar en una alarma o un remedio creado desde la web o la CLI.

Decisión (cambio de `core` aprobado por el dueño: `server/internal/sync/**`, `server/internal/core/store`, migraciones y el cableado de `server/cmd/cauce`):

1. **`GET /sync/pull` acepta `device_id` opcional** (1..64 caracteres, la misma regla del sobre; inválido = 400). Si viene y el pull sale bien, el servidor anota `last_pull_at` = ahora (UTC) y `pulled_through_seq` = el `next_since` que devuelve. Sin `device_id`, el comportamiento es el de antes y no se anota nada: un teléfono con una versión vieja sigue funcionando.
2. **`POST /sync/push` anota `last_push_at`** para cada `device_id` distinto del lote, en la misma transacción que el insert, y suma el campo aditivo `server_seq`: el mayor `server_seq` del log del usuario tras insertar. Todo evento empujado, nuevo o duplicado, queda ≤ a él. `accepted` y `duplicates` no cambian. Un lote rechazado no anota nada.
3. **`GET /sync/devices`** (con token, como toda ruta salvo `/healthz`) devuelve `{now, server_seq, devices: [{device_id, last_push_at, last_pull_at, pulled_through_seq}]}`, ordenado por `device_id`, solo del usuario de la petición, horas RFC 3339 UTC con `Z` y `null` = nunca.
4. **Almacenamiento:** migración aditiva `0002_device_sync.sql`, tabla `device_sync(user_id, device_id, last_push_at, last_pull_at, pulled_through_seq)` con clave `(user_id, device_id)`. Es contabilidad operativa: no es un evento ni una proyección (D7 intacto) y el log no cambia. Consultas con sqlc, como el resto del store.
5. **«Recibido»:** un cambio llegó al dispositivo D cuando `D.pulled_through_seq >= server_seq` devuelto por el push que lo mandó. «El teléfono» es todo dispositivo cuyo id no es `web` ni `cli`.

Uso: el teléfono manda su `device_id` en el pull y la app muestra el estado de sus intentos de sync; la web muestra una tarjeta «Teléfono» y, tras un push, «el teléfono aún no lo recibe» hasta que lo tenga; la CLI agrega `server devices` y cada escritura con `--apply` devuelve `data.server_seq` y `data.phone_received` (una lectura de `/sync/devices` justo después del push).

Consecuencias: reemplaza la limitación de D18. `pulled_through_seq` es el `next_since` de la última página pedida, así que un teléfono a mitad de una paginación o que reinicia su cursor en 0 aparece atrasado hasta terminar de bajar; es lo correcto. Un teléfono que todavía no manda `device_id` no aparece en `/sync/devices` (solo con `last_push_at` si empujó algo), y la CLI y la web lo muestran como «no recibido». El reloj es el del servidor, no el del dispositivo.
