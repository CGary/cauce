# Feature: Alarmas

| Campo        | Valor |
|--------------|-------|
| id           | `alarmas` |
| estado       | `activo` |
| versión spec | `11` |
| superficies  | `android`, `web` |

## 1. Problema

Las alarmas del reloj del teléfono no saben *para qué* suenan: no registran si di el remedio ni cuántas dosis van, y hay que recrearlas a mano cada tratamiento. Quiero alarmas que sepan qué son, registren qué pasó y se apaguen solas cuando termina el tratamiento.

## 2. Proceso de negocio

1. Una alarma tiene título, hora, fecha de inicio opcional (para un compromiso puntual, "el martes 16:00 revisar la facturación", o para una serie que empieza en una fecha concreta), tipo (`remedio` | `reunion` | `otro`) y repetición: `null` (una sola vez) o un subconjunto de RRULE de RFC 5545: `DAILY`, `WEEKLY` (con días), `MONTHLY` u `HOURLY`, con `interval`, y límite por `count` o por `until` (excluyentes entre sí). Uso típico: "cada 8 h por 5 días" se escribe `HOURLY` `interval 8` `count 15`.
   - `MONTHLY` admite exactamente una de dos formas: un día del mes (`bymonthday`: `1..31`, o `-1` = último día) o un día de la semana con ordinal (`byday` con un solo valor `1MO`..`5SU` o `-1MO`..`-1SU`, p. ej. "primer lunes" = `1MO`, "último viernes" = `-1FR`). Si el día no existe en un mes (`bymonthday 31` en abril, `5MO` en un mes con cuatro lunes), ese mes no dispara: se salta, como en RFC 5545 y Google Calendar.
2. Las alarmas de tipo `remedio` tienen un destinatario (texto libre, p. ej. el nombre de la persona a cargo) y el nombre del remedio.
   - Una alarma `remedio` no se modifica (decisión del dueño, 2026-11-19): solo cambia para corregir un error humano. Editarla corrige título, remedio y destinatario; tipo, fecha, hora y repetición (frecuencia, intervalo, días, límite) se muestran pero no se cambian. Si hace falta otro remedio u otro horario, se desactiva esta alarma y se crea otra. El `alarma_modificada` de esa corrección lleva el calendario tal como está en el estado vigente (misma `fecha` o ninguna, misma `hora`, misma `repeticion` con el `count` original), así que P1 (`proximo_disparo`, `restantes`) y P2 (`dosis_esperadas`) quedan iguales.
3. Al sonar, la alarma muestra pantalla completa aunque el teléfono esté bloqueado, con dos acciones: **Hecho** y **Posponer 10 min**. Suena y vibra hasta que se atiende o pasan 2 minutos (→ queda `ignorada`).
4. **Hecho** en una alarma `remedio` registra una dosis (→ P2). En los demás tipos solo cierra la alarma.
5. Se puede posponer hasta 3 veces; a la cuarta, la alarma se marca `ignorada`.
6. Una alarma cuyo calendario tiene fin (`count` o `until`) se desactiva sola después de su último disparo (→ `alarma_desactivada`, motivo `fin_tratamiento`). Una alarma sin repetición (`null`) se desactiva al atenderse (motivo `una_vez_atendida`).
7. Las alarmas sobreviven al reinicio del teléfono: al arrancar, la app reprograma todas las activas (→ P1). Además, tras un pull de sync que traiga `alarma_creada`, `alarma_modificada`, `alarma_desactivada`, `reunion_sincronizada`, `reunion_eliminada_en_origen` o `aviso_reunion_editado`, la app recomputa P1 y reprograma: si no, una alarma creada en la web (o un aviso editado allí) nunca sonaría.
8. Una alarma desactivada no se borra: su historial de dosis sigue visible.
9. La web es consola de administración: crea, edita, desactiva y carga en bloque alarmas. Sus eventos entran por el sync genérico (`POST /sync/push`, `device_id: "web"`); el teléfono los recibe en el próximo pull (cada 15 min, al abrir la app o con el botón Sincronizar).
10. Las reuniones de Google que trae `calendario_google` también suenan aquí, como alarmas de tipo `reunion` de un solo disparo (→ P1): el disparo es el `aviso_en` que define esa spec (recordatorio heredado de Google, editado en cauce o 10 min antes). Se atienden igual que cualquier alarma (reglas 3 y 5). No se crean, editan ni desactivan desde `alarmas`: su aviso se edita en `calendario_google` y desaparecen solas cuando pasa el aviso o cuando la reunión se elimina en Google.

## 3. Eventos

| Tipo | v | Productor | Campos (además de `id`, `user_id`, `ts`, `device_id`) |
|------|---|-----------|-------------------------------------------------------|
| `alarma_creada` | 3 | app, web, cli | como v2 más `fecha: "AAAA-MM-DD"?` (fecha local del primer disparo; §4). v3 es la versión que se produce desde la spec v5; v2 se sigue leyendo. |
| `alarma_creada` | 2 | app, web | `alarma_id: uuid`, `titulo: string`, `tipo: enum`, `hora: "HH:MM"`, `repeticion: null (una vez) o RRULE { freq: DAILY\|WEEKLY\|MONTHLY\|HOURLY, interval?, byday? (WEEKLY: MO..SU; MONTHLY: un solo valor con ordinal, 1MO..5SU o -1MO..-1SU), bymonthday? (solo MONTHLY: 1..31 o -1; excluyente con byday), count? o until? }`, `destinatario: string?`, `remedio: string?` |
| `alarma_modificada` | 3 | app, web, cli | como v2 más `fecha?` (estado completo) |
| `alarma_modificada` | 2 | app, web | `alarma_id`, mismos campos que `alarma_creada` (estado completo, no diff) |
| `alarma_desactivada` | 1 | app, web, cli | `alarma_id`, `motivo: enum(usuario, fin_tratamiento, una_vez_atendida)` |
| `alarma_disparada` | 1 | app | `alarma_id`, `programada_para: datetime` |
| `alarma_atendida` | 1 | app | `alarma_id`, `programada_para: datetime`, `accion: enum(hecho, pospuesta, ignorada)`, `pospuesta_n: int(1..3)?` |
| `dosis_dada` | 1 | app | `alarma_id`, `destinatario`, `remedio`, `programada_para: datetime` |

### Eventos consumidos de otros features

| Tipo | Feature dueño | Para qué |
|------|---------------|----------|
| `reunion_sincronizada` | `calendario_google` | cada reunión próxima con aviso futuro es una entrada de P1 (2.10) |
| `reunion_eliminada_en_origen` | `calendario_google` | la reunión deja de aparecer en P1 (2.10) |
| `aviso_reunion_editado` | `calendario_google` | el aviso editado define el disparo de la serie (2.10) |

## 4. Proyecciones

Convenciones comunes a las tres: todo `datetime` de entrada y salida es RFC 3339 en UTC (`...Z`). `hora` y `until` son locales; la zona entra como `params.tz` (IANA, p. ej. `America/La_Paz`). El estado vigente de una alarma es el último evento entre `alarma_creada` y `alarma_modificada` ordenado por `ts` (así el que venga de la web pisa al de la app y viceversa).

**Calendario de disparos** (usado por P1 y P2): el **ancla** es el `ts` del `alarma_creada` o, si el estado vigente trae `fecha`, el instante `fecha` a `hora` local. Sin `fecha`, el primer disparo es la primera ocurrencia de `hora` estrictamente posterior al ancla. Con `fecha`, el primer disparo es la primera ocurrencia de `hora` igual o posterior al ancla que cumpla la regla de la repetición (`fecha` misma si cae en un día válido): una alarma puntual (`repeticion: null`) con `fecha` dispara ese día aunque falten semanas, y una serie con `fecha` cuenta semanas, meses, `count` y `until` desde ahí. Las reglas de abajo dicen "posterior al `ts`"; léase "posterior al ancla". Luego:
- `repeticion: null`: solo ese disparo.
- `DAILY`: cada `interval` días (`1` si se omite) a `hora`.
- `WEEKLY` (alineado con RFC 5545, `WKST=MO`): el primer disparo es la primera ocurrencia de `hora` estrictamente posterior al `ts` del `alarma_creada` que caiga en un día listado en `byday`. Sin `byday`, el primer disparo es la primera ocurrencia de `hora` posterior al `ts`, y solo cuenta su día de la semana. Con `interval`, `interval` cuenta semanas del calendario ISO (las semanas empiezan el lunes): solo disparan las semanas que distan un múltiplo de `interval` de la semana que contiene el primer disparo. Ejemplo: alarma creada el viernes 2026-10-23 10:00 local con `hora 08:00`, `byday ["FR"]`, `interval 2` → primer disparo viernes 30-10 08:00, siguiente viernes 13-11 08:00 (caso_07).
- `MONTHLY`: el primer disparo es la primera ocurrencia de `hora` estrictamente posterior al `ts` del `alarma_creada` que cumpla la regla (`bymonthday` o `byday` con ordinal). Luego, cada `interval` meses (`1` si se omite) el día que cumple la regla en ese mes; `-1` cuenta desde el final del mes (`bymonthday -1` = último día; `byday -1FR` = último viernes). Un mes sin ese día se salta y no consume `count`. Ejemplo: creada el 2026-10-23 con `hora 09:00`, `bymonthday 31` → 31-10, 31-12, 31-01 (noviembre no tiene 31). Ejemplo: `byday ["1MO"]` creada el viernes 2026-10-23 → lunes 02-11, lunes 07-12.
- `HOURLY`: cada `interval` horas desde el primer disparo.
- `count`: exactamente esa cantidad de disparos, primero incluido. `until`: no hay disparo con fecha local posterior a `until` (es inclusive). `count` y `until` son excluyentes, como en RFC 5545. Sin `count` ni `until`, la repetición no termina.
- Ejemplo: "cada 8 h por 5 días" se escribe `{"freq": "HOURLY", "interval": 8, "count": 15}`. Con `hora 08:00` y creada el 20 a las 06:30: 15 disparos, del 20 08:00 al 25 00:00.

Todo resto de RRULE (`YEARLY`, `BYHOUR`, `BYSETPOS`, `EXDATE`, varios valores en `byday` de `MONTHLY`, `bymonthday` fuera de `MONTHLY`, …) queda fuera de alcance.

### P1. `alarmas_activas`
- **Entrada:** `alarma_creada`, `alarma_modificada`, `alarma_desactivada`, y los consumidos `reunion_sincronizada`, `reunion_eliminada_en_origen`, `aviso_reunion_editado`
- **Params:** `now`, `tz`
- **Salida:** `[{ alarma_id, titulo, tipo, hora, fecha?: "AAAA-MM-DD", repeticion, proximo_disparo: datetime, restantes?: int }]` ordenado por `proximo_disparo` asc; empates por `alarma_id`.
- **Reglas:** 2.1, 2.6, 2.7, 2.10. Una alarma aparece si no tiene `alarma_desactivada` y su calendario tiene al menos un disparo estrictamente posterior a `now`; ese disparo es `proximo_disparo`.
- **Reuniones de Google (2.10):** por cada reunión vigente según la spec de `calendario_google` §4 (último evento por `ts` entre `reunion_sincronizada` y `reunion_eliminada_en_origen`; `estado: confirmada`; `respuesta` distinta de `rechazada`) cuyo `aviso_en` (misma regla, aplicada tal cual: editado > todo el día 08:00 > recordatorio de Google > 10 min) es estrictamente posterior a `now`, P1 agrega `{ alarma_id: "gcal:" + reunion_id, titulo, tipo: "reunion", hora: HH:MM local de aviso_en, repeticion: null, proximo_disparo: aviso_en }`, sin `restantes`. Un aviso `apagado` o ya pasado no aparece. Estas entradas no tienen `alarma_creada` ni `alarma_desactivada`; `alarma_disparada` y `alarma_atendida` las referencian por ese `alarma_id`.
- `fecha`: la `fecha` del estado vigente, tal cual; la clave se omite si el estado no la trae (y en las entradas `gcal:`). Está para que un editor pueda reenviar el calendario sin cambios (2.2): sin ella, un `alarma_modificada` no puede conservar el ancla.
- `restantes`: número de disparos del calendario con instante `>= proximo_disparo`, contando `proximo_disparo` mismo. Se incluye solo cuando `repeticion.count` está definido; en los demás casos la clave se omite (no es `null`). Una alarma cuyo calendario terminó no aparece en P1.

### P2. `adherencia_tratamiento`
- **Entrada:** `alarma_creada`, `alarma_modificada`, `dosis_dada`, `alarma_atendida`
- **Params:** `now`, `tz`
- **Salida:** `[{ alarma_id, destinatario, remedio, dosis_esperadas: int, dosis_dadas: int, ignoradas: int, ultima_dosis: datetime? }]` una por alarma de tipo `remedio` (desactivadas incluidas, 2.8), ordenado por `alarma_id`.
- **Reglas:** 2.4, 2.5. `dosis_esperadas` = disparos del calendario con instante `≤ now`. `dosis_dadas` = cantidad de `programada_para` distintos con al menos un `dosis_dada` (idempotencia por reintentos de sync). `ignoradas` = cantidad de `programada_para` distintos con `alarma_atendida.accion = ignorada` y sin dosis. Las posposiciones no cuentan como nada. `ultima_dosis` = mayor `ts` de `dosis_dada`, o `null`.

### P3. `historial_dosis`
- **Entrada:** `dosis_dada`
- **Params:** `destinatario` (opcional)
- **Salida:** `[{ ts, destinatario, remedio }]` ordenado por `ts` desc; si viene `destinatario`, solo las de ese.
- **Reglas:** 2.8. Misma deduplicación que P2: una entrada por (`alarma_id`, `programada_para`), con el menor `ts`.

## 5. Superficies

### Android
- **Pantallas:** lista de alarmas activas (P1) con toggle; al editar un `remedio`, solo título, remedio y destinatario son editables y el calendario se guarda tal como está (2.2); editor con fecha de inicio opcional (selector de fecha; vacío = "desde ahora") (las entradas `gcal:` se muestran con la cuenta/origen y sin toggle ni editor: su aviso se edita en la pantalla de reuniones de `calendario_google`, 2.10); editor de alarma (la repetición "Mensual" ofrece dos modos: "día N del mes" con opción "último día", y "N-ésimo día de la semana" con opción "último", 2.1); historial de dosis por destinatario (P3).
- **Al recibir eventos por sync:** tras un pull que traiga `alarma_creada`, `alarma_modificada`, `alarma_desactivada`, `reunion_sincronizada`, `reunion_eliminada_en_origen` o `aviso_reunion_editado` (incluidos los emitidos por la web con `device_id: "web"`), la app recomputa P1 y reprograma las alarmas del sistema (2.7, 2.10).
- **Notificaciones / servicios:** `AlarmManager.setAlarmClock` por disparo y por posposición (no se alinea, saca al teléfono de Doze y muestra el ícono de alarma); el timeout de 2 min queda además como respaldo con `setExactAndAllowWhileIdle`. `BroadcastReceiver` para el disparo, que arranca un servicio en primer plano (`systemExempted`) que reproduce el tono de alarma por defecto y la vibración en bucle por `USAGE_ALARM` (no respeta el modo silencio y No molestar lo deja pasar por defecto), con `WakeLock` parcial. El servicio corta a los 2 minutos (→ `ignorada`), al pulsar Hecho o Posponer y cuando aparece un `alarma_atendida` terminal; con varias alarmas a la vez suena mientras quede alguna sin atender. La notificación no tiene sonido propio (canal silencioso): es la del servicio, con `fullScreenIntent` y acciones Hecho/Posponer. Si el servicio no puede pasar a primer plano o el reproductor falla, la misma notificación sale por un canal de respaldo audible («Alarmas (respaldo)»: tono de alarma por defecto, `USAGE_ALARM`, vibración, insistente hasta Hecho, Posponer o el timeout); en uso normal ese canal no suena, así que nunca hay doble sonido. Al disparar, el timeout de respaldo y el próximo disparo se programan antes de hacer sonar la alarma. La pantalla de alarma se muestra sobre el bloqueo con `showWhenLocked` y `turnScreenOn`, sin pedir desbloquear; Hecho y Posponer funcionan bloqueado. Receiver de `BOOT_COMPLETED` (y de `MY_PACKAGE_REPLACED`, tras actualizar el APK) que reprograma desde P1.
- **Permisos:**
  - `SCHEDULE_EXACT_ALARM` (solo hasta API 32) — alarmas exactas; en Android 13+ lo reemplaza `USE_EXACT_ALARM`.
  - `USE_EXACT_ALARM` — Android 13+: las dosis no pueden correrse 15 min por ahorro de batería.
  - `POST_NOTIFICATIONS` — Android 13+.
  - `USE_FULL_SCREEN_INTENT` — mostrar sobre pantalla bloqueada (2.3).
  - `SYSTEM_ALERT_WINDOW` («Mostrar sobre otras apps», se concede en Ajustes) — el servicio que hace sonar la alarma abre él mismo la pantalla de alarma, porque Android permite iniciar actividades desde segundo plano a las apps que lo tienen (§8). La lista de alarmas muestra una tarjeta mientras falte.
  - `FOREGROUND_SERVICE` — el servicio que hace sonar la alarma.
  - `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` — tipo `systemExempted`, previsto para apps con alarmas exactas que siguen sonando en segundo plano.
  - `WAKE_LOCK` — la CPU no duerme mientras suena.
  - `RECEIVE_BOOT_COMPLETED` — 2.7.
- **Requisitos del teléfono (ColorOS):** sin ellos el sistema puede cerrar la app a la fuerza y se pierden las alarmas programadas, o bloquear la pantalla completa. En Ajustes → Apps → Cauce: batería «Sin restricciones», inicio automático activado, «Aparecer sobre otras aplicaciones» permitido y, en Aplicaciones → Acceso de aplicación especial, «Mostrar ventanas emergentes en segundo plano» permitido (sin este último ColorOS bloquea que el servicio abra la pantalla de alarma: verificado 2026-11-24 en el teléfono del dueño), mostrar en pantalla de bloqueo permitido, y en Notificaciones → Alarmas la pantalla completa permitida. No tienen API pública: la lista de alarmas solo muestra un texto que lo recuerda.
- **Widget:** ninguno en v1.

### Server
- **Endpoints:** ninguno fuera del sync genérico.
- **Workers:** ninguno.

### Web
- **Vistas:** las mismas que la app: alarmas activas (P1, con las entradas `gcal:` de solo lectura), adherencia (P2) e historial de dosis (P3).
- **Acciones de admin:** crear (`alarma_creada`), editar (`alarma_modificada`, con el estado completo; en un `remedio` solo se corrigen título, remedio y destinatario y el calendario se reenvía tal como viene en P1, 2.2) y **Eliminar** (`alarma_desactivada` con `motivo: usuario`; no hay borrado duro para que el historial de dosis sobreviva (2.8), y el texto de la UI lo dice). Además, carga y exportación en bloque (abajo). Los eventos salen por `POST /sync/push` con `device_id: "web"`; el teléfono los aplica en su próximo pull (2.9).

#### Formato de carga y exportación

- Formato propio (JSON), el primario. La web crea un `alarma_creada` por ítem, con `alarma_id` UUID v7 nuevo:

```json
{
  "formato": "cauce.alarmas",
  "version": 1,
  "alarmas": [
    {
      "titulo": "Amoxicilina Juan",
      "tipo": "remedio",
      "hora": "08:00",
      "repeticion": { "freq": "HOURLY", "interval": 8, "count": 15 },
      "destinatario": "Juan",
      "remedio": "Amoxicilina"
    },
    {
      "titulo": "Reunión de equipo",
      "tipo": "reunion",
      "hora": "10:00",
      "repeticion": { "freq": "WEEKLY", "byday": ["MO", "WE"] }
    }
  ]
}
```

- Importación `.ics`: subconjunto RFC 5545 pensado para que sirva una exportación de Google Calendar; un VEVENT por alarma:
  - `SUMMARY` → `titulo`.
  - hora local de `DTSTART` → `hora`, y su fecha local → `fecha` (v3), así una serie importada arranca el día que dice el calendario.
  - `RRULE` → `repeticion`; `UNTIL` en UTC se convierte a fecha local con la zona de la sesión web (la misma que viaja como `params.tz`). `FREQ=MONTHLY;BYMONTHDAY=n` → `bymonthday n`; `FREQ=MONTHLY;BYDAY=1MO` → `byday ["1MO"]`. La forma equivalente que escriben Apple Calendar y Outlook, `FREQ=MONTHLY;BYDAY=MO;BYSETPOS=1` (un solo día sin ordinal, un solo `BYSETPOS` en `1..5` o `-1`), se traduce a `byday ["1MO"]`. Una regla fuera del subconjunto (`YEARLY`, `BYHOUR`, `EXDATE`, varios días o varios `BYMONTHDAY` en `MONTHLY`, cualquier otro uso de `BYSETPOS`, `COUNT` junto con `UNTIL`, …) se rechaza con un error por evento, sin frenar el resto del archivo.
  - `CATEGORIES` → `tipo` (`remedio` | `reunion` | `otro`; si falta, `otro`).
  - `X-CAUCE-DESTINATARIO` y `X-CAUCE-REMEDIO` → `destinatario` y `remedio`.
- La exportación produce los dos formatos. En el `.ics` los campos de dominio (`destinatario`, `remedio`) se espejan además en `DESCRIPTION`, porque Google puede descartar las propiedades `X-`. No se usa `VTODO` (Google lo ignora). Cuando P1 informa `restantes`, el `COUNT` exportado usa ese valor y no el `count` original; en la traducción interoperable de `HOURLY`, ese valor se reparte entre los VEVENTs igual que un `COUNT` normal.
  - Interoperabilidad `HOURLY`: Google Calendar no soporta `FREQ=HOURLY`. Si `interval` divide a 24 (24 es divisible por `n`), se emiten `24/n` VEVENTs, cada uno con `RRULE:FREQ=DAILY` y el mismo `COUNT`/`UNTIL` traducido (`COUNT` repartido entre los VEVENTs), con `DTSTART` a `hora`, `hora+n`, … Ejemplo: cada 8 h desde 08:00 → 3 VEVENTs diarios a 08:00, 16:00 y 00:00. Si 24 no es divisible por `n`, se emite un VEVENT por disparo, sin `RRULE`. Si la alarma no tiene `count` ni `until`, se exportan solo los primeros 30 días.
  - Interoperabilidad `WEEKLY`: el `DTSTART` debe ser el primer disparo, que por la regla de §4 con `byday` cae en un día listado en `byday`.
  - Interoperabilidad `MONTHLY`: se exporta tal cual (`FREQ=MONTHLY;BYMONTHDAY=n` o `FREQ=MONTHLY;BYDAY=1MO`, siempre en la forma con ordinal, nunca con `BYSETPOS`), con `DTSTART` en el primer disparo. Google y Apple lo soportan nativamente; no hace falta desdoblar como con `HOURLY`. Un mes sin ese día lo saltan igual que cauce.
  - `VALARM` se incluye (`ACTION=DISPLAY`, `TRIGGER:PT0M`), pero la exportación es para personas y otros calendarios: Google y Apple pueden ignorar `VALARM` y las propiedades `X-`. La fuente de verdad del timbre es siempre la app, nunca la notificación del calendario.
  - En importación, un `RRULE` `HOURLY` se acepta (está en el subconjunto), aunque Google nunca lo produce.
- El parseo ocurre en la web, antes del push. No es una proyección y no tiene fixtures.

## 6. Dependencias y efectos sobre otros features

- `alarmas` consume los tres eventos de `calendario_google` (§3); un cambio en ellos lanza las dos specs juntas.
- `agenda` (futuro) probablemente consuma `alarma_creada` para mostrar reuniones en el día. Mientras no exista, nada.

## 7. Fixtures (tests de conformidad)

| Proyección | Caso | Qué verifica |
|------------|------|--------------|
| P1 | `caso_01_diaria` | `proximo_disparo` es hoy si la hora no pasó, mañana si ya pasó |
| P1 | `caso_02_cada_8h_count` | "cada 8 h por 5 días" = `count 15`; dentro del tratamiento muestra el próximo disparo; una alarma cuyo `count` se agotó no aparece |
| P1 | `caso_03_modificada_pisa_creada` | `alarma_modificada` reemplaza completo, no mezcla campos |
| P1 | `caso_04_desactivada_no_aparece` | 2.6; además `WEEKLY byday` salta al próximo día habilitado |
| P1 | `caso_05_modificada_desde_web` | la `alarma_modificada` de la web (`device_id: "web"`, `ts` posterior) gana: gana el último `ts`, no el dispositivo (2.7, 2.9) |
| P1 | `caso_06_until_inclusivo` | `until` es inclusive: hay disparo en la fecha `until` y ninguno después |
| P1 | `caso_07_semanal_byday` | `WEEKLY byday` respeta los días y `interval` (2 semanas = 14 días, no 7) |
| P1 | `caso_08_mensual_dia_del_mes` | con `now` en noviembre: `bymonthday 15` da 15-11; `bymonthday 31` salta a 31-12 (noviembre no tiene 31); `bymonthday -1` da el último día del mes (30-11); orden por `proximo_disparo` |
| P1 | `caso_09_mensual_dia_ordinal` | `byday ["1MO"]` da el primer lunes del mes siguiente; `byday ["-1FR"]` con `interval 2` salta un mes entero; una con `count 3` agotada no aparece |
| P1 | `caso_10_mensual_count_restantes` | mensual con `count 5`: dos disparos ya pasaron, `proximo_disparo` es el tercero y `restantes = 3` |
| P1 | `caso_12_fecha_puntual_y_serie` | `alarma_creada.v3` con `fecha`: una puntual dentro de 12 días aparece con `proximo_disparo` ese día; una puntual con `fecha` ya pasada no aparece; una `WEEKLY byday` con `fecha` futura ancla ahí (no en la creación); un v2 sin `fecha` sigue igual |
| P1 | `caso_11_reunion_google` | una reunión de Google con recordatorio aparece como `gcal:<reunion_id>`, tipo `reunion`, `repeticion: null`, `proximo_disparo = aviso_en`, intercalada por `proximo_disparo` con una alarma propia; una rechazada, una con aviso `apagado` y una cuyo aviso ya pasó no aparecen (2.10) |
| P1 | `caso_13_remedio_correccion_de_textos` | dos remedios `HOURLY count 15` a mitad de tratamiento, uno con `fecha` y otro sin ella, reciben un `alarma_modificada` que solo corrige textos: `proximo_disparo` y `restantes` son los del calendario original; la fila con `fecha` la trae y la otra no (2.2) |
| P2 | `caso_01_tratamiento_completo` | `count 15` → 15 esperadas, 15 dadas |
| P2 | `caso_02_con_ignoradas` | 15 esperadas, 13 dadas, 2 ignoradas |
| P2 | `caso_03_dosis_duplicada_por_sync` | dos `dosis_dada` con mismo `programada_para` cuentan 1 |
| P2 | `caso_04_pospuesta_tres_veces` | tres `pospuesta` y luego `ignorada` sobre el mismo disparo → 1 esperada, 0 dadas, 1 ignorada; las posposiciones no cuentan |
| P2 | `caso_05_mensual_esperadas` | remedio `bymonthday 31`, `count 3`, `now` en febrero: 3 esperadas (oct, dic, ene; noviembre saltado no cuenta), 2 dadas, 1 ignorada |
| P2 | `caso_06_remedio_correccion_de_textos` | los mismos dos remedios con dosis antes y después de la corrección: `dosis_esperadas` no cambia (6 y 7), las dosis dadas con el texto viejo siguen contando, y destinatario y remedio salen del estado corregido (2.2) |
| P3 | `caso_01_filtro_destinatario` | solo dosis del destinatario pedido, orden desc |

## 8. Fuera de alcance y decisiones

- No hay alarmas por ubicación ("al llegar a casa"). Es otro feature si algún día hace falta.
- No se sincroniza con Google Calendar: solo importación/exportación de archivos `.ics` (§5).
- No hay `YEARLY`: "una vez al año" se escribe `MONTHLY` `interval 12` (RFC 5545 lo permite y Google lo entiende). `YEARLY` real (con `bymonth`) entra solo si algún día hace falta.
- `MONTHLY` no admite listas: un solo `bymonthday` o un solo `byday` con ordinal. Dos días al mes son dos alarmas.
- El destinatario es texto libre y no un catálogo de personas: evita una dependencia con un feature `familia` que hoy no existe.
- `alarma_modificada` lleva el estado completo (no un diff) para que P1 no tenga que reconstruir campo por campo; cuesta bytes, ahorra bugs.
- No hay evento `alarma_borrada`: desactivar es suficiente (2.8).
- (agente) v1 de `alarma_creada`/`alarma_modificada` nunca se produjo (no hubo implementación); las proyecciones leen solo v2.
- `MONTHLY` (spec v3) se agregó editando `alarma_creada.v2` y `alarma_modificada.v2` en el lugar, no como v3: decisión del dueño, porque v2 tampoco llegó a implementarse y no existe ningún evento producido que pueda romperse. Es la única excepción a "nunca editar un schema publicado"; a partir de la primera implementación la regla vuelve a aplicar.
- (agente) `WEEKLY` sin `byday` repite el mismo día de la semana del primer disparo (el comportamiento por defecto de RFC 5545 cuando no hay `BYDAY`); con `interval` el ciclo ancla en la semana — del calendario ISO, que empieza el lunes (`WKST=MO`) — que contiene ese primer disparo (§4).
- `fecha` llegó como `alarma_creada.v3` / `alarma_modificada.v3`, no como campo aditivo de v2: cambia el significado del primer disparo, y un lector que lo ignorara sonaría en el día equivocado. Las proyecciones leen v2 y v3; la app y la web producen v3. Antes de v3, la fecha de `DTSTART` se ignoraba al importar `.ics`.
- (agente) No hay borrado duro en la web: **Eliminar** emite `alarma_desactivada` con `motivo: usuario` para que el historial de dosis sobreviva (2.8); la UI lo explica.
- (agente) El parseo de carga/exportación vive en la web, antes del push; no es una proyección ni tiene fixtures.
- (agente) La web pide P2 con `tz` = zona IANA del navegador y **sin** `now`: el endpoint `GET /api/projections/...` usa el reloj del servidor cuando falta `now`. Así la clave de caché no cambia en cada render y la proyección sigue siendo pura (el `now` lo inyecta el servidor, no la función).
- (agente) El filtro por destinatario de la tabla de adherencia (P2) es de presentación, en la web: P2 no recibe `destinatario` como param. En el historial (P3) el destinatario sí viaja como param de la proyección.
- (agente) Hora inexistente por cambio de horario (DST): se interpreta con el offset previo al salto (RFC 5545), p. ej. 02:30 → 03:30; hora ambigua: la primera ocurrencia.
- (agente) El ancla del calendario (P1/P2) es el `ts` del último `alarma_creada`; si no hay `alarma_creada` en el log, es el `ts` del último evento de estado (`alarma_creada` o `alarma_modificada`).
- (agente) P3 filtra por `destinatario` antes de deduplicar.
- (agente) En P3, los empates de `ts` se ordenan por `alarma_id` y luego por `programada_para`, ambos ascendentes.
- (agente) Consola web de P1: pide solo `tz` (el endpoint inyecta `now`, igual que P2). El editor rellena destinatario y remedio desde P2, porque P1 no los trae. El `.ics` usa la fecha local de `proximo_disparo` como DTSTART (ocurrencia válida; P1 no trae el ts de creación). HOURLY cuyo COUNT no es divisible entre los VEVENTs reparte el resto en los primeros; sin count ni until, y si 24 no es divisible por interval, exporta 30 días desde ese ancla. `WKST` en un RRULE importado se ignora (Google lo emite; cauce usa WKST=MO).
- (agente) Formulario web de alarma: al elegir tipo `remedio` sin repetición se propone `HOURLY` cada 8 h (editable). `HOURLY` ofrece cada 4/6/8/12 h u otro valor, y la duración «por días» es solo de UI: se guarda como `count = ⌈días × 24 / interval⌉` (cada 8 h por 5 días = 15). Al editar, un `HOURLY` cuyo `count × interval` cubre días enteros se muestra en días.
- (agente) Editar desde la web una alarma que no es `remedio` (un `remedio` se corrige sin tocar el calendario, 2.2) rellena «Fecha de inicio» con la fecha local de `proximo_disparo` (escrito cuando P1 no traía `fecha`): una puntual recupera su fecha y una serie se reancla en el próximo disparo; si la serie tiene `count`, el `count` nuevo es `restantes`, para no repetir disparos. Un `HOURLY` toma además la `hora` local de `proximo_disparo`, para que fecha + hora sea ese mismo instante y no se pierda ni se salte una dosis. La exportación JSON lleva la misma fecha local, la misma hora de `HOURLY` y `restantes` como `count`.
- (agente) Una alarma pospuesta sigue sonando aunque ya se haya emitido su `alarma_desactivada(fin_tratamiento)`; solo una desactivación por usuario cancela la posposición.
- Las reuniones de Google no se convierten en `alarma_creada`: v2 no tiene fecha y ya hay eventos producidos, así que P1 las deriva directamente de los eventos de `calendario_google` (2.10). La regla de `aviso_en` se repite aquí tal cual está en esa spec, porque los features solo se hablan por eventos.
- Las entradas `gcal:` no emiten `alarma_desactivada` al atenderse (2.6 no aplica): son de un solo disparo y desaparecen solas al pasar su aviso. Posponer funciona igual que en cualquier alarma.
- (agente) Android edita igual que la web (también el `remedio`, que se corrige sin tocar el calendario, 2.2): «Fecha de inicio» se rellena con la fecha local de `proximo_disparo`, un `HOURLY` toma también su `hora` y una serie con `count` usa `restantes` como `count` nuevo. Vacía = «desde ahora» (sin clave `fecha`).
- (agente) P1 no trae la cuenta de Google: en Android las entradas `gcal:` se rotulan «Google Calendar», sin leer datos de `calendario_google`. Al sonar, título y tipo salen de la fila de P1 calculada un milisegundo antes del instante programado (P1 solo lista avisos futuros); si la reunión ya no está, una posposición sigue sonando con el título «Reunión de Google Calendar».
- (agente) v6, tres decisiones de dispositivo. `setAlarmClock` en vez de `setExactAndAllowWhileIdle` porque ColorOS asigna a este último una ventana de 1 h (la alarma llegaba hasta un minuto tarde), mientras que el reloj del sistema usa `setAlarmClock` con `window=0`. Servicio en primer plano en vez de sonido del canal de notificación porque ese sonido es de notificación y ColorOS lo calla en modo silencio; el servicio lo reproduce por `USAGE_ALARM`. Se quita `requestDismissKeyguard` porque con PIN o huella pedía desbloquear en vez de mostrar la alarma encima del bloqueo. La duración de 2 min no cambia (decisión pendiente del dueño). El `showIntent` de la alarma abre la app (cuya pantalla de inicio es la lista de alarmas).
- (agente) v7, UX de la lista y el inicio en Android. La lista pone las alarmas propias primero y agrupa las `gcal:` en una sección plegable «Reuniones de Google (N)», cerrada por defecto; «Nueva alarma» es una acción de la barra superior. Cada fila `gcal:` rotula la cuenta y el encabezado «Reunión HH:mm · aviso HH:mm» leyendo `reunion_sincronizada` (ya consumido por este feature) para recuperar cuenta, inicio y `serie_id`, que P1 no trae; no se fusionan filas con igual título e inicio porque P1 emite una por `reunion_id` (cuenta distinta). Tocar una fila `gcal:` abre el editor de aviso de su serie: la ruta se inyecta desde `:app` (lambda del módulo) para no crear dependencia entre features. El recordatorio de ColorOS es una tarjeta descartable (flag en SharedPreferences `alarmas_ui`) que reaparece si falta el permiso de pantalla completa. Se agregan las pantallas Adherencia (P2) e Historial agrupado por día (P3, estado siempre «Dada»); P2 no define etiqueta ni umbrales, así que no se inventan: se muestra `dadas / esperadas` en % y una leyenda de las definiciones. Inicio sigue siendo el hub (tarjetas con icono y subtítulo). `core` no expone fecha de última sincronización (solo el cursor `server_seq`): se muestra «Último evento: hace X» con el `ts` más reciente del almacén local.
- (agente) Editor Android: la repetición `HOURLY` ya existía en el editor y ahora se rotula «Horaria» (campo «Cada N horas», mismos campos `freq`/`interval`/`count` del schema). Hora y fecha de inicio se eligen con TimePicker y DatePicker de Material3; la fecha se muestra localizada («dom 1 nov 2026») y los valores guardados (`HH:MM`, `YYYY-MM-DD`) no cambian. Los chips de tipo y repetición van en un `FlowRow` para que ninguna etiqueta se parta. El editor tiene flecha de volver y barra inferior fija con Cancelar y Guardar, y al editar una alarma activa ofrece «Desactivar» con confirmación, que emite el mismo `alarma_desactivada` (`motivo: usuario`) que el interruptor de la lista.
- (agente) Consola web, presentación: la leyenda de adherencia usa umbrales propios de la web (Al día ≥ 90 %, Revisar 70–89 %, Baja < 70 %) porque el servidor entrega solo conteos y no expone umbrales; el historial se agrupa por día local con «Cargar más» cada 50 filas y filtra por destinatario con un select cuyas opciones salen de P2; los días de `byday` se muestran en español («Lun a vie»); tras cada push la web avisa que el teléfono lo aplica en su próximo pull (2.9). Con P2 caída, la exportación JSON sigue habilitada (los remedios salen sin destinatario ni remedio) y el `.ics` no cambia: `restantes` viene en P1, así que `COUNT` se exporta igual.
- (agente) Consola web: la vista Activas pliega las reuniones de Google (`gcal:`) en una sección «Reuniones de Google (N)» cerrada por defecto, como Android; las alarmas propias van primero y el filtro de origen «Google» abre la sección.
- (agente) v8, robustez del disparo en Android. Desde v6, 0 de 18 disparos registrados se atendieron y los `ignorada` llegaban a ~+177 s (el timeout de respaldo, no el del servicio): el servicio se caía en cada disparo con `SecurityException` al vibrar, porque el manifiesto no declaraba `android.permission.VIBRATE` (confirmado en el teléfono el 2026-11-19; ColorOS no intervenía); se agregó el permiso. El disparo programa el timeout de respaldo y el próximo disparo antes de sonar (esperando como máximo 2 s la reprogramación, para no atrasar el sonido; si no alcanza, reprograma después). Ningún fallo del arranque del servicio mata el proceso: cada fallo se registra en logcat con la etiqueta `CauceAlarmas`, y si falta el primer plano o el reproductor suena la notificación por el canal de respaldo audible (§5) con `FLAG_INSISTENT`, mismo id que la normal, así que Hecho, Posponer y el timeout la cancelan igual. Las reprogramaciones se serializan (lock de proceso sobre lectura, cálculo y re-armado) porque pueden correr a la vez (inicio de la app, pull, editores, receivers). Un proceso matado por el sistema sin excepción sigue sin sonar: eso solo lo resuelven los requisitos de ColorOS.
- (agente) Formulario web y carga en bloque, validaciones antes del push: con el reloj del navegador no se guarda una puntual con fecha y hora pasadas ni un «Hasta» anterior a la fecha de inicio o a hoy; `count` ≤ 1000 e `interval` ≤ 365 (también al importar JSON o `.ics`, como error por ítem), porque un entero enorme rompe las proyecciones del servidor; «por días» no puede dar más de 1000 tomas; «cada N horas» sin opción elegida es un error (no cada 1 h) y cambiar la frecuencia vuelve el intervalo a su valor visible. La tabla resume `HOURLY` como «Cada N horas» y, si P1 trae `restantes`, muestra «quedan N» en vez del `count` original.
- Decisión del dueño (2026-11-19): «Una alarma de remedio no está para modificarse. Solo puede cambiar para corregir un error humano. Si quiero otro remedio, creo otra alarma.» Por eso editar un `remedio` en la web o en la app corrige solo título, remedio y destinatario y reenvía el calendario sin cambios (2.2). Antes, la edición reanclaba la serie en el próximo disparo con `restantes` como `count`, y P2 pasaba a contar las dosis esperadas desde ahí (p. ej. «5 dadas de 0 esperadas»). P1 trae `fecha` (aditivo, sin cambio de evento) porque sin ella el editor de la web no puede reenviar el ancla.
- (agente) Las alarmas que no son `remedio` siguen editándose como antes (se reanclan en el próximo disparo, reglas de arriba): su historial no alimenta P2.
- (agente) Límite conocido: un `remedio` editado antes de v9 ya tiene el ancla movida en su último `alarma_modificada`; no se repara. Su adherencia sigue contando desde ese ancla.
- (agente) Las validaciones de fecha pasada y de «Hasta» del formulario web no aplican a la corrección de un `remedio`: su calendario no se edita, y una alarma con `fecha` de inicio ya pasada tiene que poder corregirse.
- (agente) v10, pantalla de alarma en ColorOS/Android 16 (Realme RMX5070, verificado en el teléfono el 2026-11-24): el servicio suena y vibra y la notificación lleva `fullScreenIntent` con el permiso concedido, pero el sistema nunca abre la pantalla de alarma (la pantalla sigue apagada y solo aparece la notificación chica del bloqueo). Por eso, cuando «Mostrar sobre otras apps» (`SYSTEM_ALERT_WINDOW`) está concedido, el servicio también abre la pantalla de alarma él mismo al empezar a sonar cada disparo; un fallo al abrirla se registra y no corta el sonido. El `fullScreenIntent` se mantiene para otros teléfonos. Si suena un segundo disparo con la pantalla abierta, la pantalla pasa a mostrar el más nuevo (y se cierra cuando ese disparo se atiende). El permiso se pide solo con una tarjeta en la lista de alarmas, sin diálogos.

- (agente) v11, `cauce-cli` (D21) es un productor más, con `device_id: "cli"` y por el mismo `POST /sync/push` que la web: crea (`alarma_creada` v3), edita (`alarma_modificada` v3, estado completo) y desactiva (`alarma_desactivada`, `motivo: usuario`), y carga en bloque el formato `cauce.alarmas` v1 de §5 en un solo push. Aplica las mismas reglas que el formulario web: un `remedio` solo se corrige (2.2), una edición de otro tipo se reancla en el próximo disparo con `restantes` como `count`, y valida topes, fecha pasada y «Hasta» antes de enviar. Las entradas `gcal:` no se editan ni se desactivan desde la CLI (2.10). No produce `alarma_disparada`, `alarma_atendida` ni `dosis_dada`: son del teléfono.

## 9. Changelog

- `v1` — 2026-10-22 — creación. Fixtures iniciales escritos a mano junto con los schemas.
- `v2` — 2026-10-22 — la web administra alarmas: alta, edición, baja y carga/exportación en bloque, con eventos por `POST /sync/push` (`device_id: "web"`) que el teléfono aplica al recibir el pull (2.7, 2.9). `repeticion` pasa a subconjunto de RRULE RFC 5545 (`null` = una vez; `count`/`until` excluyentes; `until` inclusive) y se elimina el campo `fin`; schemas nuevos `alarma_creada.v2` y `alarma_modificada.v2`. Fixtures reescritos a v2 y tres casos nuevos: modificación desde web, `until` inclusive y semanal `byday`. Aclaraciones (sin cambios de versión, v2 aún sin implementar): regla `WEEKLY` precisa y alineada con RFC 5545 (interval cuenta semanas ISO, anclada en la semana del primer disparo); reglas de interoperabilidad de exportación `.ics` (HOURLY desdoblado en VEVENTs DAILY, DTSTART de WEEKLY en el primer disparo, VALARM/X- opcionales en el receptor, import acepta HOURLY).
- `v3` — 2026-10-23 — repetición mensual: `freq: MONTHLY` con `bymonthday` (`1..31` o `-1` = último día) o `byday` con un ordinal (`1MO`..`5SU`, `-1MO`..`-1SU`), excluyentes; un mes sin ese día se salta y no consume `count`. Editor con modo "Mensual" en app y web. Importación `.ics` acepta `MONTHLY` y traduce la forma Apple/Outlook `BYDAY=MO;BYSETPOS=1`; exportación emite `MONTHLY` tal cual. Schemas `alarma_creada.v2` y `alarma_modificada.v2` editados en el lugar (sin eventos producidos, decisión del dueño, §8). Tres fixtures nuevos (P1 ×2, P2 ×1). `YEARLY` fuera de alcance.
- `v4` — 2026-10-24 — P1 agrega el campo opcional `restantes`: cantidad de disparos del calendario desde `proximo_disparo`, inclusive. Se incluye solo si la repetición tiene `count` y se omite en los demás casos. La exportación `.ics` usa `COUNT=restantes` para no repetir disparos ya consumidos. Fixture P1 nuevo: mensual con `count 5` y dos disparos pasados.
- `v5` — 2026-10-28 — las reuniones de Google (`calendario_google`) suenan como alarmas de tipo `reunion` de un solo disparo: P1 consume `reunion_sincronizada`, `reunion_eliminada_en_origen` y `aviso_reunion_editado`, agrega entradas `gcal:<reunion_id>` con `proximo_disparo = aviso_en` y reprograma tras un pull que traiga cualquiera de los tres (2.7, 2.10). Entradas de solo lectura en app y web. Fixture P1 nuevo `caso_11_reunion_google`. Además, `fecha` opcional en `alarma_creada.v3` / `alarma_modificada.v3` para compromisos puntuales y series con fecha de inicio; la importación `.ics` conserva la fecha de `DTSTART`; fixture `caso_12_fecha_puntual_y_serie`. Lanzamiento conjunto con `calendario_google` v1.
- `v6` — 2026-10-29 — la alarma suena como la del reloj del sistema: `setAlarmClock` (hora exacta), servicio en primer plano que reproduce el tono y la vibración por `USAGE_ALARM` (suena en silencio), pantalla de alarma sobre el bloqueo sin pedir desbloquear; permisos `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` y `WAKE_LOCK`, y requisitos de ColorOS. Sin cambios de eventos, proyecciones ni fixtures.
- `v7` — 2026-10-30 — UX Android sin cambios de eventos, proyecciones ni fixtures: lista con alarmas propias primero y reuniones de Google plegables con cuenta, inicio y aviso, «Nueva alarma» en la barra, recordatorio de ColorOS descartable, pantallas Adherencia e Historial por día, e inicio con tarjetas y «Último evento».
- `v8` — 2026-11-19 — Android sin cambios de eventos, proyecciones ni fixtures: el disparo arma timeout y próximo disparo antes de sonar, fallos del servicio de alarma registrados y con respaldo audible (canal «Alarmas (respaldo)», insistente), reprogramaciones serializadas y reprogramación tras actualizar el APK (§5, §8); web: validaciones del formulario y la carga en bloque (fechas pasadas, «Hasta» antes del inicio, topes de `count`/`interval`), intervalo que se envía igual al visible y resumen con «quedan N» (§8).
- `v9` — 2026-11-19 — decisión del dueño: una alarma `remedio` solo se corrige (título, remedio, destinatario); su calendario no se edita y el `alarma_modificada` lo reenvía tal cual, así que la adherencia (P2) y `restantes` (P1) no cambian (2.2). P1 agrega `fecha?` a cada fila (aditivo). Fixtures nuevos: P1 `caso_13`, P2 `caso_06`; `caso_12` trae `fecha` en sus filas.
- `v10` — 2026-11-24 — Android sin cambios de eventos, proyecciones ni fixtures: con «Mostrar sobre otras apps» (`SYSTEM_ALERT_WINDOW`) concedido, el servicio de alarma abre la pantalla de alarma él mismo, porque ColorOS/Android 16 no lanza el `fullScreenIntent`; la pantalla muestra el disparo más nuevo y la lista pide el permiso con una tarjeta (§5, §8).
- `v11` — 2026-11-24 — `cauce-cli` (D21) se suma como productor de `alarma_creada` v3, `alarma_modificada` v3 y `alarma_desactivada` v1, con `device_id: "cli"` y las mismas reglas que la web (§8). Sin cambios de eventos, proyecciones ni fixtures.
