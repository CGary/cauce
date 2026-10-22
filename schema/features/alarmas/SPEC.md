# Feature: Alarmas

| Campo        | Valor |
|--------------|-------|
| id           | `alarmas` |
| estado       | `activo` |
| versión spec | `2` |
| superficies  | `android`, `web` |

## 1. Problema

Las alarmas del reloj del teléfono no saben *para qué* suenan: no registran si di el remedio ni cuántas dosis van, y hay que recrearlas a mano cada tratamiento. Quiero alarmas que sepan qué son, registren qué pasó y se apaguen solas cuando termina el tratamiento.

## 2. Proceso de negocio

1. Una alarma tiene título, hora, tipo (`remedio` | `reunion` | `otro`) y repetición: `null` (una sola vez) o un subconjunto de RRULE de RFC 5545: `DAILY`, `WEEKLY` (con días) u `HOURLY`, con `interval`, y límite por `count` o por `until` (excluyentes entre sí). Uso típico: "cada 8 h por 5 días" se escribe `HOURLY` `interval 8` `count 15`.
2. Las alarmas de tipo `remedio` tienen un destinatario (texto libre, p. ej. el nombre de la persona a cargo) y el nombre del remedio.
3. Al sonar, la alarma muestra pantalla completa aunque el teléfono esté bloqueado, con dos acciones: **Hecho** y **Posponer 10 min**. Suena y vibra hasta que se atiende o pasan 2 minutos (→ queda `ignorada`).
4. **Hecho** en una alarma `remedio` registra una dosis (→ P2). En los demás tipos solo cierra la alarma.
5. Se puede posponer hasta 3 veces; a la cuarta, la alarma se marca `ignorada`.
6. Una alarma cuyo calendario tiene fin (`count` o `until`) se desactiva sola después de su último disparo (→ `alarma_desactivada`, motivo `fin_tratamiento`). Una alarma sin repetición (`null`) se desactiva al atenderse (motivo `una_vez_atendida`).
7. Las alarmas sobreviven al reinicio del teléfono: al arrancar, la app reprograma todas las activas (→ P1). Además, tras un pull de sync que traiga `alarma_creada`, `alarma_modificada` o `alarma_desactivada`, la app recomputa P1 y reprograma: si no, una alarma creada en la web nunca sonaría.
8. Una alarma desactivada no se borra: su historial de dosis sigue visible.
9. La web es consola de administración: crea, edita, desactiva y carga en bloque alarmas. Sus eventos entran por el sync genérico (`POST /sync/push`, `device_id: "web"`); el teléfono los recibe en el próximo pull (cada 15 min, al abrir la app o con el botón Sincronizar).

## 3. Eventos

| Tipo | v | Productor | Campos (además de `id`, `user_id`, `ts`, `device_id`) |
|------|---|-----------|-------------------------------------------------------|
| `alarma_creada` | 2 | app, web | `alarma_id: uuid`, `titulo: string`, `tipo: enum`, `hora: "HH:MM"`, `repeticion: null (una vez) o RRULE { freq: DAILY\|WEEKLY\|HOURLY, interval?, byday?, count? o until? }`, `destinatario: string?`, `remedio: string?` |
| `alarma_modificada` | 2 | app, web | `alarma_id`, mismos campos que `alarma_creada` (estado completo, no diff) |
| `alarma_desactivada` | 1 | app, web | `alarma_id`, `motivo: enum(usuario, fin_tratamiento, una_vez_atendida)` |
| `alarma_disparada` | 1 | app | `alarma_id`, `programada_para: datetime` |
| `alarma_atendida` | 1 | app | `alarma_id`, `programada_para: datetime`, `accion: enum(hecho, pospuesta, ignorada)`, `pospuesta_n: int(1..3)?` |
| `dosis_dada` | 1 | app | `alarma_id`, `destinatario`, `remedio`, `programada_para: datetime` |

### Eventos consumidos de otros features

| Tipo | Feature dueño | Para qué |
|------|---------------|----------|
| Ninguno | | |

## 4. Proyecciones

Convenciones comunes a las tres: todo `datetime` de entrada y salida es RFC 3339 en UTC (`...Z`). `hora` y `until` son locales; la zona entra como `params.tz` (IANA, p. ej. `America/La_Paz`). El estado vigente de una alarma es el último evento entre `alarma_creada` y `alarma_modificada` ordenado por `ts` (así el que venga de la web pisa al de la app y viceversa).

**Calendario de disparos** (usado por P1 y P2): el primer disparo es la primera ocurrencia de `hora` estrictamente posterior al `ts` del `alarma_creada`. Luego:
- `repeticion: null`: solo ese disparo.
- `DAILY`: cada `interval` días (`1` si se omite) a `hora`.
- `WEEKLY` (alineado con RFC 5545, `WKST=MO`): el primer disparo es la primera ocurrencia de `hora` estrictamente posterior al `ts` del `alarma_creada` que caiga en un día listado en `byday`. Sin `byday`, el primer disparo es la primera ocurrencia de `hora` posterior al `ts`, y solo cuenta su día de la semana. Con `interval`, `interval` cuenta semanas del calendario ISO (las semanas empiezan el lunes): solo disparan las semanas que distan un múltiplo de `interval` de la semana que contiene el primer disparo. Ejemplo: alarma creada el viernes 2026-10-23 10:00 local con `hora 08:00`, `byday ["FR"]`, `interval 2` → primer disparo viernes 30-10 08:00, siguiente viernes 13-11 08:00 (caso_07).
- `HOURLY`: cada `interval` horas desde el primer disparo.
- `count`: exactamente esa cantidad de disparos, primero incluido. `until`: no hay disparo con fecha local posterior a `until` (es inclusive). `count` y `until` son excluyentes, como en RFC 5545. Sin `count` ni `until`, la repetición no termina.
- Ejemplo: "cada 8 h por 5 días" se escribe `{"freq": "HOURLY", "interval": 8, "count": 15}`. Con `hora 08:00` y creada el 20 a las 06:30: 15 disparos, del 20 08:00 al 25 00:00.

Todo resto de RRULE (`BYHOUR`, `BYMONTHDAY`, `BYSETPOS`, `EXDATE`, …) queda fuera de alcance.

### P1. `alarmas_activas`
- **Entrada:** `alarma_creada`, `alarma_modificada`, `alarma_desactivada`
- **Params:** `now`, `tz`
- **Salida:** `[{ alarma_id, titulo, tipo, hora, repeticion, proximo_disparo: datetime }]` ordenado por `proximo_disparo` asc.
- **Reglas:** 2.1, 2.6, 2.7. Una alarma aparece si no tiene `alarma_desactivada` y su calendario tiene al menos un disparo estrictamente posterior a `now`; ese disparo es `proximo_disparo`.

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
- **Pantallas:** lista de alarmas activas (P1) con toggle; editor de alarma; historial de dosis por destinatario (P3).
- **Al recibir eventos por sync:** tras un pull que traiga `alarma_creada`, `alarma_modificada` o `alarma_desactivada` (incluidos los emitidos por la web con `device_id: "web"`), la app recomputa P1 y reprograma las alarmas del sistema (2.7).
- **Notificaciones / servicios:** `AlarmManager.setExactAndAllowWhileIdle` por alarma; `BroadcastReceiver` para el disparo; notificación con `fullScreenIntent` y acciones Hecho/Posponer; receiver de `BOOT_COMPLETED` que reprograma desde P1.
- **Permisos:**
  - `SCHEDULE_EXACT_ALARM` — las dosis no pueden correrse 15 min por ahorro de batería.
  - `POST_NOTIFICATIONS` — Android 13+.
  - `USE_FULL_SCREEN_INTENT` — mostrar sobre pantalla bloqueada (2.3).
  - `RECEIVE_BOOT_COMPLETED` — 2.7.
- **Widget:** ninguno en v1.

### Server
- **Endpoints:** ninguno fuera del sync genérico.
- **Workers:** ninguno.

### Web
- **Vistas:** las mismas que la app: alarmas activas (P1), adherencia (P2) e historial de dosis (P3).
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
  - hora local de `DTSTART` → `hora`; la fecha de `DTSTART` se ignora (→ §8).
  - `RRULE` → `repeticion`; `UNTIL` en UTC se convierte a fecha local con la zona de la sesión web (la misma que viaja como `params.tz`). Una regla fuera del subconjunto (`BYHOUR`, `BYMONTHDAY`, `BYSETPOS`, `EXDATE`, ordinales en `BYDAY`, `COUNT` junto con `UNTIL`, …) se rechaza con un error por evento, sin frenar el resto del archivo.
  - `CATEGORIES` → `tipo` (`remedio` | `reunion` | `otro`; si falta, `otro`).
  - `X-CAUCE-DESTINATARIO` y `X-CAUCE-REMEDIO` → `destinatario` y `remedio`.
- La exportación produce los dos formatos. En el `.ics` los campos de dominio (`destinatario`, `remedio`) se espejan además en `DESCRIPTION`, porque Google puede descartar las propiedades `X-`. No se usa `VTODO` (Google lo ignora).
  - Interoperabilidad `HOURLY`: Google Calendar no soporta `FREQ=HOURLY`. Si `interval` divide a 24 (24 es divisible por `n`), se emiten `24/n` VEVENTs, cada uno con `RRULE:FREQ=DAILY` y el mismo `COUNT`/`UNTIL` traducido (`COUNT` repartido entre los VEVENTs), con `DTSTART` a `hora`, `hora+n`, … Ejemplo: cada 8 h desde 08:00 → 3 VEVENTs diarios a 08:00, 16:00 y 00:00. Si 24 no es divisible por `n`, se emite un VEVENT por disparo, sin `RRULE`. Si la alarma no tiene `count` ni `until`, se exportan solo los primeros 30 días.
  - Interoperabilidad `WEEKLY`: el `DTSTART` debe ser el primer disparo, que por la regla de §4 con `byday` cae en un día listado en `byday`.
  - `VALARM` se incluye (`ACTION=DISPLAY`, `TRIGGER:PT0M`), pero la exportación es para personas y otros calendarios: Google y Apple pueden ignorar `VALARM` y las propiedades `X-`. La fuente de verdad del timbre es siempre la app, nunca la notificación del calendario.
  - En importación, un `RRULE` `HOURLY` se acepta (está en el subconjunto), aunque Google nunca lo produce.
- El parseo ocurre en la web, antes del push. No es una proyección y no tiene fixtures.

## 6. Dependencias y efectos sobre otros features

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
| P2 | `caso_01_tratamiento_completo` | `count 15` → 15 esperadas, 15 dadas |
| P2 | `caso_02_con_ignoradas` | 15 esperadas, 13 dadas, 2 ignoradas |
| P2 | `caso_03_dosis_duplicada_por_sync` | dos `dosis_dada` con mismo `programada_para` cuentan 1 |
| P2 | `caso_04_pospuesta_tres_veces` | tres `pospuesta` y luego `ignorada` sobre el mismo disparo → 1 esperada, 0 dadas, 1 ignorada; las posposiciones no cuentan |
| P3 | `caso_01_filtro_destinatario` | solo dosis del destinatario pedido, orden desc |

## 8. Fuera de alcance y decisiones

- No hay alarmas por ubicación ("al llegar a casa"). Es otro feature si algún día hace falta.
- No se sincroniza con Google Calendar: solo importación/exportación de archivos `.ics` (§5).
- El destinatario es texto libre y no un catálogo de personas: evita una dependencia con un feature `familia` que hoy no existe.
- `alarma_modificada` lleva el estado completo (no un diff) para que P1 no tenga que reconstruir campo por campo; cuesta bytes, ahorra bugs.
- No hay evento `alarma_borrada`: desactivar es suficiente (2.8).
- (agente) v1 de `alarma_creada`/`alarma_modificada` nunca se produjo (no hubo implementación); las proyecciones leen solo v2.
- (agente) `WEEKLY` sin `byday` repite el mismo día de la semana del primer disparo (el comportamiento por defecto de RFC 5545 cuando no hay `BYDAY`); con `interval` el ciclo ancla en la semana — del calendario ISO, que empieza el lunes (`WKST=MO`) — que contiene ese primer disparo (§4).
- (agente) Al importar `.ics`, la fecha de `DTSTART` se ignora: solo su hora local define `hora`. El calendario de cauce ancla en "primera ocurrencia de `hora` posterior a la creación", no en la fecha original del evento.
- (agente) No hay borrado duro en la web: **Eliminar** emite `alarma_desactivada` con `motivo: usuario` para que el historial de dosis sobreviva (2.8); la UI lo explica.
- (agente) El parseo de carga/exportación vive en la web, antes del push; no es una proyección ni tiene fixtures.
- (agente) La web pide P2 con `tz` = zona IANA del navegador y **sin** `now`: el endpoint `GET /api/projections/...` usa el reloj del servidor cuando falta `now`. Así la clave de caché no cambia en cada render y la proyección sigue siendo pura (el `now` lo inyecta el servidor, no la función).
- (agente) El filtro por destinatario de la tabla de adherencia (P2) es de presentación, en la web: P2 no recibe `destinatario` como param. En el historial (P3) el destinatario sí viaja como param de la proyección.

## 9. Changelog

- `v1` — 2026-10-22 — creación. Fixtures iniciales escritos a mano junto con los schemas.
- `v2` — 2026-10-22 — la web administra alarmas: alta, edición, baja y carga/exportación en bloque, con eventos por `POST /sync/push` (`device_id: "web"`) que el teléfono aplica al recibir el pull (2.7, 2.9). `repeticion` pasa a subconjunto de RRULE RFC 5545 (`null` = una vez; `count`/`until` excluyentes; `until` inclusive) y se elimina el campo `fin`; schemas nuevos `alarma_creada.v2` y `alarma_modificada.v2`. Fixtures reescritos a v2 y tres casos nuevos: modificación desde web, `until` inclusive y semanal `byday`. Aclaraciones (sin cambios de versión, v2 aún sin implementar): regla `WEEKLY` precisa y alineada con RFC 5545 (interval cuenta semanas ISO, anclada en la semana del primer disparo); reglas de interoperabilidad de exportación `.ics` (HOURLY desdoblado en VEVENTs DAILY, DTSTART de WEEKLY en el primer disparo, VALARM/X- opcionales en el receptor, import acepta HOURLY).
