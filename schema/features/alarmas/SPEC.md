# Feature: Alarmas

| Campo        | Valor |
|--------------|-------|
| id           | `alarmas` |
| estado       | `activo` |
| versión spec | `6` |
| superficies  | `android`, `web` |

## 1. Problema

Las alarmas del reloj del teléfono no saben *para qué* suenan: no registran si di el remedio ni cuántas dosis van, y hay que recrearlas a mano cada tratamiento. Quiero alarmas que sepan qué son, registren qué pasó y se apaguen solas cuando termina el tratamiento.

## 2. Proceso de negocio

1. Una alarma tiene título, hora, fecha de inicio opcional (para un compromiso puntual, "el martes 16:00 revisar la facturación", o para una serie que empieza en una fecha concreta), tipo (`remedio` | `reunion` | `otro`) y repetición: `null` (una sola vez) o un subconjunto de RRULE de RFC 5545: `DAILY`, `WEEKLY` (con días), `MONTHLY` u `HOURLY`, con `interval`, y límite por `count` o por `until` (excluyentes entre sí). Uso típico: "cada 8 h por 5 días" se escribe `HOURLY` `interval 8` `count 15`.
   - `MONTHLY` admite exactamente una de dos formas: un día del mes (`bymonthday`: `1..31`, o `-1` = último día) o un día de la semana con ordinal (`byday` con un solo valor `1MO`..`5SU` o `-1MO`..`-1SU`, p. ej. "primer lunes" = `1MO`, "último viernes" = `-1FR`). Si el día no existe en un mes (`bymonthday 31` en abril, `5MO` en un mes con cuatro lunes), ese mes no dispara: se salta, como en RFC 5545 y Google Calendar.
2. Las alarmas de tipo `remedio` tienen un destinatario (texto libre, p. ej. el nombre de la persona a cargo) y el nombre del remedio.
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
| `alarma_creada` | 3 | app, web | como v2 más `fecha: "AAAA-MM-DD"?` (fecha local del primer disparo; §4). v3 es la versión que se produce desde la spec v5; v2 se sigue leyendo. |
| `alarma_creada` | 2 | app, web | `alarma_id: uuid`, `titulo: string`, `tipo: enum`, `hora: "HH:MM"`, `repeticion: null (una vez) o RRULE { freq: DAILY\|WEEKLY\|MONTHLY\|HOURLY, interval?, byday? (WEEKLY: MO..SU; MONTHLY: un solo valor con ordinal, 1MO..5SU o -1MO..-1SU), bymonthday? (solo MONTHLY: 1..31 o -1; excluyente con byday), count? o until? }`, `destinatario: string?`, `remedio: string?` |
| `alarma_modificada` | 3 | app, web | como v2 más `fecha?` (estado completo) |
| `alarma_modificada` | 2 | app, web | `alarma_id`, mismos campos que `alarma_creada` (estado completo, no diff) |
| `alarma_desactivada` | 1 | app, web | `alarma_id`, `motivo: enum(usuario, fin_tratamiento, una_vez_atendida)` |
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
- **Salida:** `[{ alarma_id, titulo, tipo, hora, repeticion, proximo_disparo: datetime, restantes?: int }]` ordenado por `proximo_disparo` asc; empates por `alarma_id`.
- **Reglas:** 2.1, 2.6, 2.7, 2.10. Una alarma aparece si no tiene `alarma_desactivada` y su calendario tiene al menos un disparo estrictamente posterior a `now`; ese disparo es `proximo_disparo`.
- **Reuniones de Google (2.10):** por cada reunión vigente según la spec de `calendario_google` §4 (último evento por `ts` entre `reunion_sincronizada` y `reunion_eliminada_en_origen`; `estado: confirmada`; `respuesta` distinta de `rechazada`) cuyo `aviso_en` (misma regla, aplicada tal cual: editado > todo el día 08:00 > recordatorio de Google > 10 min) es estrictamente posterior a `now`, P1 agrega `{ alarma_id: "gcal:" + reunion_id, titulo, tipo: "reunion", hora: HH:MM local de aviso_en, repeticion: null, proximo_disparo: aviso_en }`, sin `restantes`. Un aviso `apagado` o ya pasado no aparece. Estas entradas no tienen `alarma_creada` ni `alarma_desactivada`; `alarma_disparada` y `alarma_atendida` las referencian por ese `alarma_id`.
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
- **Pantallas:** lista de alarmas activas (P1) con toggle; editor con fecha de inicio opcional (selector de fecha; vacío = "desde ahora") (las entradas `gcal:` se muestran con la cuenta/origen y sin toggle ni editor: su aviso se edita en la pantalla de reuniones de `calendario_google`, 2.10); editor de alarma (la repetición "Mensual" ofrece dos modos: "día N del mes" con opción "último día", y "N-ésimo día de la semana" con opción "último", 2.1); historial de dosis por destinatario (P3).
- **Al recibir eventos por sync:** tras un pull que traiga `alarma_creada`, `alarma_modificada`, `alarma_desactivada`, `reunion_sincronizada`, `reunion_eliminada_en_origen` o `aviso_reunion_editado` (incluidos los emitidos por la web con `device_id: "web"`), la app recomputa P1 y reprograma las alarmas del sistema (2.7, 2.10).
- **Notificaciones / servicios:** `AlarmManager.setAlarmClock` por disparo y por posposición (no se alinea, saca al teléfono de Doze y muestra el ícono de alarma); el timeout de 2 min queda además como respaldo con `setExactAndAllowWhileIdle`. `BroadcastReceiver` para el disparo, que arranca un servicio en primer plano (`systemExempted`) que reproduce el tono de alarma por defecto y la vibración en bucle por `USAGE_ALARM` (no respeta el modo silencio y No molestar lo deja pasar por defecto), con `WakeLock` parcial. El servicio corta a los 2 minutos (→ `ignorada`), al pulsar Hecho o Posponer y cuando aparece un `alarma_atendida` terminal; con varias alarmas a la vez suena mientras quede alguna sin atender. La notificación no tiene sonido propio (canal silencioso): es la del servicio, con `fullScreenIntent` y acciones Hecho/Posponer. La pantalla de alarma se muestra sobre el bloqueo con `showWhenLocked` y `turnScreenOn`, sin pedir desbloquear; Hecho y Posponer funcionan bloqueado. Receiver de `BOOT_COMPLETED` que reprograma desde P1.
- **Permisos:**
  - `SCHEDULE_EXACT_ALARM` (solo hasta API 32) — alarmas exactas; en Android 13+ lo reemplaza `USE_EXACT_ALARM`.
  - `USE_EXACT_ALARM` — Android 13+: las dosis no pueden correrse 15 min por ahorro de batería.
  - `POST_NOTIFICATIONS` — Android 13+.
  - `USE_FULL_SCREEN_INTENT` — mostrar sobre pantalla bloqueada (2.3).
  - `FOREGROUND_SERVICE` — el servicio que hace sonar la alarma.
  - `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` — tipo `systemExempted`, previsto para apps con alarmas exactas que siguen sonando en segundo plano.
  - `WAKE_LOCK` — la CPU no duerme mientras suena.
  - `RECEIVE_BOOT_COMPLETED` — 2.7.
- **Requisitos del teléfono (ColorOS):** sin ellos el sistema puede cerrar la app a la fuerza y se pierden las alarmas programadas, o bloquear la pantalla completa. En Ajustes → Apps → Cauce: batería «Sin restricciones», inicio automático activado, ventanas emergentes / mostrar sobre otras apps permitido, mostrar en pantalla de bloqueo permitido, y en Notificaciones → Alarmas la pantalla completa permitida. No tienen API pública: la lista de alarmas solo muestra un texto que lo recuerda.
- **Widget:** ninguno en v1.

### Server
- **Endpoints:** ninguno fuera del sync genérico.
- **Workers:** ninguno.

### Web
- **Vistas:** las mismas que la app: alarmas activas (P1, con las entradas `gcal:` de solo lectura), adherencia (P2) e historial de dosis (P3).
- **Acciones de admin:** crear (`alarma_creada`), editar (`alarma_modificada`, con el estado completo) y **Eliminar** (`alarma_desactivada` con `motivo: usuario`; no hay borrado duro para que el historial de dosis sobreviva (2.8), y el texto de la UI lo dice). Además, carga y exportación en bloque (abajo). Los eventos salen por `POST /sync/push` con `device_id: "web"`; el teléfono los aplica en su próximo pull (2.9).

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
| P2 | `caso_01_tratamiento_completo` | `count 15` → 15 esperadas, 15 dadas |
| P2 | `caso_02_con_ignoradas` | 15 esperadas, 13 dadas, 2 ignoradas |
| P2 | `caso_03_dosis_duplicada_por_sync` | dos `dosis_dada` con mismo `programada_para` cuentan 1 |
| P2 | `caso_04_pospuesta_tres_veces` | tres `pospuesta` y luego `ignorada` sobre el mismo disparo → 1 esperada, 0 dadas, 1 ignorada; las posposiciones no cuentan |
| P2 | `caso_05_mensual_esperadas` | remedio `bymonthday 31`, `count 3`, `now` en febrero: 3 esperadas (oct, dic, ene; noviembre saltado no cuenta), 2 dadas, 1 ignorada |
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
- (agente) Una alarma pospuesta sigue sonando aunque ya se haya emitido su `alarma_desactivada(fin_tratamiento)`; solo una desactivación por usuario cancela la posposición.
- Las reuniones de Google no se convierten en `alarma_creada`: v2 no tiene fecha y ya hay eventos producidos, así que P1 las deriva directamente de los eventos de `calendario_google` (2.10). La regla de `aviso_en` se repite aquí tal cual está en esa spec, porque los features solo se hablan por eventos.
- Las entradas `gcal:` no emiten `alarma_desactivada` al atenderse (2.6 no aplica): son de un solo disparo y desaparecen solas al pasar su aviso. Posponer funciona igual que en cualquier alarma.
- (agente) v6, tres decisiones de dispositivo. `setAlarmClock` en vez de `setExactAndAllowWhileIdle` porque ColorOS asigna a este último una ventana de 1 h (la alarma llegaba hasta un minuto tarde), mientras que el reloj del sistema usa `setAlarmClock` con `window=0`. Servicio en primer plano en vez de sonido del canal de notificación porque ese sonido es de notificación y ColorOS lo calla en modo silencio; el servicio lo reproduce por `USAGE_ALARM`. Se quita `requestDismissKeyguard` porque con PIN o huella pedía desbloquear en vez de mostrar la alarma encima del bloqueo. La duración de 2 min no cambia (decisión pendiente del dueño). El `showIntent` de la alarma abre la app (cuya pantalla de inicio es la lista de alarmas).

## 9. Changelog

- `v1` — 2026-10-22 — creación. Fixtures iniciales escritos a mano junto con los schemas.
- `v2` — 2026-10-22 — la web administra alarmas: alta, edición, baja y carga/exportación en bloque, con eventos por `POST /sync/push` (`device_id: "web"`) que el teléfono aplica al recibir el pull (2.7, 2.9). `repeticion` pasa a subconjunto de RRULE RFC 5545 (`null` = una vez; `count`/`until` excluyentes; `until` inclusive) y se elimina el campo `fin`; schemas nuevos `alarma_creada.v2` y `alarma_modificada.v2`. Fixtures reescritos a v2 y tres casos nuevos: modificación desde web, `until` inclusive y semanal `byday`. Aclaraciones (sin cambios de versión, v2 aún sin implementar): regla `WEEKLY` precisa y alineada con RFC 5545 (interval cuenta semanas ISO, anclada en la semana del primer disparo); reglas de interoperabilidad de exportación `.ics` (HOURLY desdoblado en VEVENTs DAILY, DTSTART de WEEKLY en el primer disparo, VALARM/X- opcionales en el receptor, import acepta HOURLY).
- `v3` — 2026-10-23 — repetición mensual: `freq: MONTHLY` con `bymonthday` (`1..31` o `-1` = último día) o `byday` con un ordinal (`1MO`..`5SU`, `-1MO`..`-1SU`), excluyentes; un mes sin ese día se salta y no consume `count`. Editor con modo "Mensual" en app y web. Importación `.ics` acepta `MONTHLY` y traduce la forma Apple/Outlook `BYDAY=MO;BYSETPOS=1`; exportación emite `MONTHLY` tal cual. Schemas `alarma_creada.v2` y `alarma_modificada.v2` editados en el lugar (sin eventos producidos, decisión del dueño, §8). Tres fixtures nuevos (P1 ×2, P2 ×1). `YEARLY` fuera de alcance.
- `v4` — 2026-10-24 — P1 agrega el campo opcional `restantes`: cantidad de disparos del calendario desde `proximo_disparo`, inclusive. Se incluye solo si la repetición tiene `count` y se omite en los demás casos. La exportación `.ics` usa `COUNT=restantes` para no repetir disparos ya consumidos. Fixture P1 nuevo: mensual con `count 5` y dos disparos pasados.
- `v5` — 2026-10-28 — las reuniones de Google (`calendario_google`) suenan como alarmas de tipo `reunion` de un solo disparo: P1 consume `reunion_sincronizada`, `reunion_eliminada_en_origen` y `aviso_reunion_editado`, agrega entradas `gcal:<reunion_id>` con `proximo_disparo = aviso_en` y reprograma tras un pull que traiga cualquiera de los tres (2.7, 2.10). Entradas de solo lectura en app y web. Fixture P1 nuevo `caso_11_reunion_google`. Además, `fecha` opcional en `alarma_creada.v3` / `alarma_modificada.v3` para compromisos puntuales y series con fecha de inicio; la importación `.ics` conserva la fecha de `DTSTART`; fixture `caso_12_fecha_puntual_y_serie`. Lanzamiento conjunto con `calendario_google` v1.
- `v6` — 2026-10-29 — la alarma suena como la del reloj del sistema: `setAlarmClock` (hora exacta), servicio en primer plano que reproduce el tono y la vibración por `USAGE_ALARM` (suena en silencio), pantalla de alarma sobre el bloqueo sin pedir desbloquear; permisos `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` y `WAKE_LOCK`, y requisitos de ColorOS. Sin cambios de eventos, proyecciones ni fixtures.
