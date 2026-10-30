# Feature: Calendario Google

| Campo        | Valor |
|--------------|-------|
| id           | `calendario_google` |
| estado       | `activo` |
| versión spec | `1` |
| superficies  | `android`, `web` |

## 1. Problema

Tengo reuniones repartidas en varias cuentas de Google, y cada una avisa por su lado (o no avisa). Quiero verlas en un solo lugar y que cauce me avise de todas con sus alarmas, sin cargarlas a mano.

## 2. Proceso de negocio

1. Las reuniones salen del calendario del teléfono: todas las cuentas de Google que ya están en Android aportan sus eventos de calendario, sin conectar nada a mano ni pedir contraseñas. Entran los calendarios que están visibles en el teléfono; un calendario oculto en Android no entra. Todas se ven y suenan en un solo lugar: cauce.
2. La alarma de una reunión hereda el aviso de Google: suena en el recordatorio más temprano que la reunión tenga configurado y, si no tiene ninguno, 10 minutos antes del inicio. Ese aviso se puede editar en cauce (cuántos minutos antes, o apagarlo para esa reunión); lo editado manda sobre lo heredado (→ P1).
3. La edición del aviso alcanza a toda la serie: a esa ocurrencia y a todas las siguientes de la misma reunión repetida. Si después cambia el recordatorio en Google, sigue mandando lo editado en cauce. Una reunión que no se repite es una serie de una sola ocurrencia.
4. Una reunión rechazada por mí o cancelada por el organizador no se ve ni suena. Una tentativa o sin responder sí (→ P1).
5. Una reunión de todo el día avisa una vez, a las 08:00 locales de ese día. La hora se puede editar como cualquier aviso (regla 2, otra hora o apagado).
6. El teléfono mira los próximos 14 días. Relee el calendario cuando Android avisa de un cambio y al menos cada 15 minutos, y registra solo lo que cambió: reunión nueva, modificada o eliminada. Nada se registra dos veces si nada cambió.
7. Si una reunión cambia en Google, manda la última versión: si cambia el inicio, hay un aviso nuevo; lo que ya sonó no se altera (→ P1).
8. Una reunión eliminada en Google, o cuya cuenta se quitó del teléfono, deja de verse y de sonar. Su registro no se borra (→ P1).
9. La web ve las mismas reuniones y puede editar el aviso. El teléfono recibe la edición en el próximo pull de sync y `alarmas` reprograma.
10. Sonar es trabajo de `alarmas`: pantalla completa, **Hecho** y **Posponer 10 min**, ignorada a los 2 minutos, igual que cualquier alarma de tipo `reunion`. Este feature decide *qué* y *cuándo*; `alarmas` decide *cómo*.

## 3. Eventos

| Tipo | v | Productor | Campos (además de `id`, `user_id`, `ts`, `device_id`) |
|------|---|-----------|-------------------------------------------------------|
| `reunion_sincronizada` | 1 | app | `reunion_id: string` (estable por ocurrencia), `serie_id: string` (igual para todas las ocurrencias de la misma reunión repetida; en una reunión única, igual a su propio id), `cuenta: string` (la cuenta de Google tal como la muestra el teléfono), `calendario: string?`, `titulo: string`, `inicio: datetime`, `fin: datetime`, `todo_el_dia: bool`, `recordatorio_min: int \| null` (el recordatorio más temprano de Google, en minutos antes del inicio; `null` si no tiene), `estado: enum(confirmada, cancelada)`, `respuesta: enum(aceptada, tentativa, rechazada, sin_respuesta)`. Estado completo: gana el último `ts`. |
| `reunion_eliminada_en_origen` | 1 | app | `reunion_id` |
| `aviso_reunion_editado` | 1 | app, web | `serie_id`, y exactamente uno de: `minutos_antes: int (≥ 0)` para reuniones con hora, `hora: "HH:MM"` para reuniones de todo el día, `apagado: true`. Estado completo del aviso: gana el último `ts`. |

### Eventos consumidos de otros features

| Tipo | Feature dueño | Para qué |
|------|---------------|----------|
| Ninguno | | |

## 4. Proyecciones

Convenciones: todo `datetime` es RFC 3339 en UTC (`...Z`). La zona local entra como `params.tz` (IANA). En una reunión de todo el día, `inicio` y `fin` son las medianoches UTC de su primer día y del día siguiente (así lo guarda Android); la fecha local de la reunión es la parte de fecha de `inicio`.

**Estado vigente de una reunión:** el último evento por `ts` entre `reunion_sincronizada` y `reunion_eliminada_en_origen` con ese `reunion_id`. Si es `reunion_eliminada_en_origen`, la reunión no existe (2.8). **Aviso editado de una serie:** el último `aviso_reunion_editado` por `ts` con ese `serie_id`, sin importar si un `reunion_sincronizada` posterior trajo otro recordatorio (2.3).

**Aviso vigente y `aviso_en`** (regla que `alarmas` aplica tal cual para saber cuándo sonar):
- Si hay aviso editado: `apagado` → `aviso_en: null`; `minutos_antes: n` → `inicio - n min`; `hora: "HH:MM"` → esa hora local del día de la reunión. `aviso.origen = "cauce"`.
- Si no, y `todo_el_dia`: `hora "08:00"` local del día de la reunión, `aviso.origen = "defecto"` (2.5).
- Si no, y `recordatorio_min` no es `null`: `inicio - recordatorio_min min`, `aviso.origen = "google"` (2.2).
- Si no: `inicio - 10 min`, `aviso.origen = "defecto"` (2.2).

### P1. `reuniones_proximas`
- **Entrada:** `reunion_sincronizada`, `reunion_eliminada_en_origen`, `aviso_reunion_editado`
- **Params:** `now`, `tz`
- **Salida:** `[{ reunion_id, serie_id, cuenta, titulo, inicio, fin, todo_el_dia, aviso: { origen: enum(google, cauce, defecto), minutos_antes?: int, hora?: "HH:MM", apagado?: true }, aviso_en: datetime | null }]` ordenado por `inicio` asc y luego `reunion_id`.
- **Reglas:** 2.2–2.5, 2.7, 2.8. Aparece toda reunión vigente con `estado: confirmada`, `respuesta` distinta de `rechazada` e `inicio` estrictamente posterior a `now`. Una reunión cuyo `aviso_en` ya pasó pero aún no empezó sigue apareciendo, con ese `aviso_en` (ya sonó; 2.7). `aviso` lleva solo la clave que aplica: `minutos_antes` (con hora), `hora` (todo el día) o `apagado`.

## 5. Superficies

### Android
- **Pantallas:** lista de reuniones próximas (P1) agrupada por día, con la cuenta y el aviso vigente; al tocar una, edición del aviso de su serie (minutos antes, hora si es de todo el día, o apagado) → `aviso_reunion_editado`.
- **Lectura del calendario:** `ContentObserver` sobre el proveedor de calendario de Android más un trabajo periódico de WorkManager cada 15 minutos (2.6). Lee las ocurrencias de los próximos 14 días de los calendarios visibles de todas las cuentas, con sus recordatorios y mi respuesta, y las compara con el último `reunion_sincronizada` emitido por `reunion_id`: emite `reunion_sincronizada` solo si algo cambió, `reunion_eliminada_en_origen` si la ocurrencia ya no está. El `reunion_id` se deriva de forma determinista del evento y del inicio original de la ocurrencia; el `serie_id`, del evento de Android.
- **Al recibir eventos por sync:** este feature no tiene efectos de dispositivo propios. Los `aviso_reunion_editado` que lleguen de la web los aplica `alarmas` al reprogramar (ver su spec).
- **Permisos:**
  - `READ_CALENDAR` — leer las reuniones y recordatorios de todas las cuentas del teléfono (2.1). Solo lectura.
- **Widget:** ninguno en v1.

### Server
- **Endpoints:** ninguno fuera del sync genérico.
- **Workers:** ninguno.

### Web
- **Vistas:** reuniones próximas (P1), con cuenta, inicio y aviso vigente.
- **Acciones de admin:** editar el aviso de una serie (`aviso_reunion_editado`, por `POST /sync/push` con `device_id: "web"`). No hay alta, baja ni carga en bloque: las reuniones nacen en Google y las trae el teléfono (2.1, 2.8).

## 6. Dependencias y efectos sobre otros features

- `alarmas` consume `reunion_sincronizada`, `reunion_eliminada_en_origen` y `aviso_reunion_editado` para hacer sonar el teléfono: los lista en su §3 "Eventos consumidos", su P1 agrega una entrada por reunión con `aviso_en` futuro aplicando la regla de §4 tal cual, y reprograma tras un pull que traiga cualquiera de los tres. Las dos specs se lanzan juntas (`alarmas` v5).
- `agenda` (ROADMAP 8) probablemente los consuma también, más adelante; no entra en este lanzamiento.

## 7. Fixtures (tests de conformidad)

| Proyección | Caso | Qué verifica |
|------------|------|--------------|
| P1 | `caso_01_hereda_recordatorio` | el aviso es el recordatorio de Google (`origen: google`); con `recordatorio_min 0` suena al inicio |
| P1 | `caso_02_sin_recordatorio_10_min` | sin recordatorio en Google, 10 minutos antes (`origen: defecto`) |
| P1 | `caso_03_editado_manda_en_la_serie` | un `aviso_reunion_editado` alcanza a las dos ocurrencias de la serie y gana aunque un `reunion_sincronizada` posterior traiga otro recordatorio (2.3) |
| P1 | `caso_04_rechazada_cancelada_no_aparecen` | rechazada y cancelada no aparecen; tentativa y sin respuesta sí (2.4) |
| P1 | `caso_05_todo_el_dia_08` | todo el día avisa a las 08:00 locales; editada con `hora` usa esa hora (2.5) |
| P1 | `caso_06_eliminada_en_origen` | eliminada desaparece; un `reunion_sincronizada` posterior la revive (2.8) |
| P1 | `caso_07_aviso_apagado` | `apagado` deja la reunión visible con `aviso_en: null` |
| P1 | `caso_08_modificada_y_pasadas` | gana el último `ts` por `reunion_id`; una reunión ya iniciada no aparece; una cuyo aviso pasó pero no empezó sí; orden por `inicio` (2.7) |

## 8. Fuera de alcance y decisiones

- Este feature **no** emite `alarma_creada`: `alarma_creada.v2` no tiene fecha (solo `hora` + repetición anclada en el momento de creación), así que no puede representar una reunión en una fecha concreta, y como ya hay eventos reales producidos no se edita. En vez de eso, `alarmas` consume los eventos de este feature (§6). El dueño de un schema es quien lo produce.
- cauce no escribe en Google (no responde invitaciones ni edita eventos): solo lectura.
- Sin Google Tasks en v1: el calendario de Android no las expone y traerlas exige OAuth con secretos y un worker en el servidor. Entran en una versión siguiente si hacen falta.
- Sin OAuth ni cuentas conectadas desde cauce: se usan las cuentas que Android ya sincroniza. Una cuenta que no está en el teléfono no entra.
- Una ocurrencia por evento: el teléfono emite cada ocurrencia de una reunión repetida (dentro de los 14 días) como un `reunion_sincronizada` propio. Nadie fuera del teléfono interpreta RRULE ni excepciones de Google.
- Los recordatorios de Google de una reunión de todo el día se ignoran: el aviso es a las 08:00 locales (2.5). Los recordatorios de Google para esos eventos están pensados para otro día y otra hora y no se traducen bien a una alarma.
- La derivación de `aviso_en` (§4) se repite en `alarmas`, porque los features solo se hablan por eventos. Es una regla corta; los fixtures de ambos lados la cubren.
- Una cuenta quitada del teléfono no tiene evento propio: sus reuniones se registran como `reunion_eliminada_en_origen` una por una (2.8).
- "Eliminar" aquí es dejar de ver: no hay borrado. Para dejar de ver una reunión concreta se apaga su aviso; para dejar de ver un calendario entero se oculta en Android.
- (agente) En el servidor, un aviso editado se aplica tal como está escrito aunque no case con el tipo de reunión: `hora` en una reunión con hora usa esa hora local del día local de `inicio`, y `minutos_antes` en una de todo el día resta esos minutos a la medianoche UTC de `inicio`; igual que en `alarmas`.
- (agente) `reunion_id` = `<id del evento raíz>:<inicio original en ms>`; `serie_id` = id del evento raíz de Android. Para una excepción de una serie repetida el evento raíz es `ORIGINAL_ID` y el inicio original es `ORIGINAL_INSTANCE_TIME`, así la excepción conserva su id aunque cambie de hora.
- (agente) Una lectura vacía o fallida (sin permiso, proveedor no disponible) no emite nada, ni siquiera eliminaciones; por eso si se borran todas las reuniones de la ventana a la vez, las eliminaciones salen cuando vuelva a haber al menos una ocurrencia leída.
- (agente) `reunion_eliminada_en_origen` solo para ocurrencias previas con `inicio` futuro dentro de los 14 días que ya no están; las que simplemente pasaron al pasado no se eliminan.
- (agente) La edición del aviso en Android ofrece "minutos antes" para reuniones con hora y "a una hora" para las de todo el día, más "apagado" en ambas.
- (agente) Mi respuesta se lee de `SELF_ATTENDEE_STATUS` del proveedor (derivada de mi asistente en `Attendees`); `ATTENDEE_RELATIONSHIP` no tiene un valor "yo" en Android.
- (agente) Consola web: el estado vacío de «Reuniones próximas» muestra la hora de la última lectura de la proyección, para distinguir «sin reuniones» de «datos viejos»; tras guardar un aviso avisa que el teléfono lo aplica en su próximo pull.

## 9. Changelog

- `v1` — 2026-10-28 — creación. Reuniones de todas las cuentas de Google del teléfono, vía calendario de Android; aviso heredado de Google y editable por serie; `alarmas` consume los eventos para sonar. Fixtures iniciales escritos junto con los schemas.
