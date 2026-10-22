# Feature: Alarmas

| Campo        | Valor |
|--------------|-------|
| id           | `alarmas` |
| estado       | `activo` |
| versión spec | `1` |
| superficies  | `android`, `web` |

## 1. Problema

Las alarmas del reloj del teléfono no saben *para qué* suenan: no registran si di el remedio ni cuántas dosis van, y hay que recrearlas a mano cada tratamiento. Quiero alarmas que sepan qué son, registren qué pasó y se apaguen solas cuando termina el tratamiento.

## 2. Proceso de negocio

1. Una alarma tiene título, hora, tipo (`remedio` | `reunion` | `otro`) y repetición: `una_vez`, `diaria`, `dias_semana [L..D]` o `cada_n_horas` con fecha de fin (uso típico: "cada 8 h por 5 días").
2. Las alarmas de tipo `remedio` tienen un destinatario (texto libre, p. ej. el nombre de la persona a cargo) y el nombre del remedio.
3. Al sonar, la alarma muestra pantalla completa aunque el teléfono esté bloqueado, con dos acciones: **Hecho** y **Posponer 10 min**. Suena y vibra hasta que se atiende o pasan 2 minutos (→ queda `ignorada`).
4. **Hecho** en una alarma `remedio` registra una dosis (→ P2). En los demás tipos solo cierra la alarma.
5. Se puede posponer hasta 3 veces; a la cuarta, la alarma se marca `ignorada`.
6. Una alarma con fecha de fin se desactiva sola después de su último disparo. Una alarma `una_vez` se desactiva al atenderse.
7. Las alarmas sobreviven al reinicio del teléfono: al arrancar, la app reprograma todas las activas (→ P1).
8. Una alarma desactivada no se borra: su historial de dosis sigue visible.

## 3. Eventos

| Tipo | v | Productor | Campos (además de `id`, `user_id`, `ts`, `device_id`) |
|------|---|-----------|-------------------------------------------------------|
| `alarma_creada` | 1 | app | `alarma_id: uuid`, `titulo: string`, `tipo: enum`, `hora: "HH:MM"`, `repeticion: object`, `fin: date?`, `destinatario: string?`, `remedio: string?` |
| `alarma_modificada` | 1 | app | `alarma_id`, mismos campos que `alarma_creada` (estado completo, no diff) |
| `alarma_desactivada` | 1 | app | `alarma_id`, `motivo: enum(usuario, fin_tratamiento, una_vez_atendida)` |
| `alarma_disparada` | 1 | app | `alarma_id`, `programada_para: datetime` |
| `alarma_atendida` | 1 | app | `alarma_id`, `programada_para: datetime`, `accion: enum(hecho, pospuesta, ignorada)`, `pospuesta_n: int(1..3)?` |
| `dosis_dada` | 1 | app | `alarma_id`, `destinatario`, `remedio`, `programada_para: datetime` |

### Eventos consumidos de otros features

| Tipo | Feature dueño | Para qué |
|------|---------------|----------|
| Ninguno | | |

## 4. Proyecciones

Convenciones comunes a las tres: todo `datetime` de entrada y salida es RFC 3339 en UTC (`...Z`). `hora` y `fin` son locales; la zona entra como `params.tz` (IANA, p. ej. `America/La_Paz`). El estado vigente de una alarma es el último evento entre `alarma_creada` y `alarma_modificada` ordenado por `ts`.

**Calendario de disparos** (usado por P1 y P2): el primer disparo es la primera ocurrencia de `hora` estrictamente posterior al `ts` del `alarma_creada`. Luego:
- `una_vez`: solo ese disparo.
- `diaria`: cada día a `hora`.
- `dias_semana`: cada día listado (1 = lunes … 7 = domingo) a `hora`.
- `cada_n_horas`: cada `n` horas desde el primer disparo, mientras el disparo sea anterior a `fin + 1 día` a la misma `hora` (exclusivo). Ejemplo: `hora 08:00`, `n 8`, creada el 20 a las 06:30, `fin` 24 → 15 disparos, del 20 08:00 al 25 00:00.
- Con `fin` en cualquier repetición: no hay disparos con fecha local posterior a `fin`.

### P1. `alarmas_activas`
- **Entrada:** `alarma_creada`, `alarma_modificada`, `alarma_desactivada`
- **Params:** `now`, `tz`
- **Salida:** `[{ alarma_id, titulo, tipo, hora, repeticion, fin (null si no hay), proximo_disparo: datetime }]` ordenado por `proximo_disparo` asc.
- **Reglas:** 2.1, 2.6. Una alarma aparece si no tiene `alarma_desactivada` y su calendario tiene al menos un disparo estrictamente posterior a `now`; ese disparo es `proximo_disparo`.

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
- **Vistas:** adherencia por tratamiento (P2) y historial de dosis (P3). Solo lectura.

## 6. Dependencias y efectos sobre otros features

- `agenda` (futuro) probablemente consuma `alarma_creada` para mostrar reuniones en el día. Mientras no exista, nada.

## 7. Fixtures (tests de conformidad)

| Proyección | Caso | Qué verifica |
|------------|------|--------------|
| P1 | `caso_01_diaria` | `proximo_disparo` es hoy si la hora no pasó, mañana si ya pasó |
| P1 | `caso_02_cada_8h_con_fin` | dentro del tratamiento muestra el próximo disparo; una alarma cuyo tratamiento terminó no aparece |
| P1 | `caso_03_modificada_pisa_creada` | `alarma_modificada` reemplaza completo, no mezcla campos |
| P1 | `caso_04_desactivada_no_aparece` | 2.6; además `dias_semana` salta al próximo día habilitado |
| P2 | `caso_01_tratamiento_completo` | 5 días cada 8 h → 15 esperadas, 15 dadas |
| P2 | `caso_02_con_ignoradas` | 15 esperadas, 13 dadas, 2 ignoradas |
| P2 | `caso_03_dosis_duplicada_por_sync` | dos `dosis_dada` con mismo `programada_para` cuentan 1 |
| P2 | `caso_04_pospuesta_tres_veces` | tres `pospuesta` y luego `ignorada` sobre el mismo disparo → 1 esperada, 0 dadas, 1 ignorada; las posposiciones no cuentan |
| P3 | `caso_01_filtro_destinatario` | solo dosis del destinatario pedido, orden desc |

## 8. Fuera de alcance y decisiones

- No hay alarmas por ubicación ("al llegar a casa"). Es otro feature si algún día hace falta.
- No se sincroniza con Google Calendar.
- El destinatario es texto libre y no un catálogo de personas: evita una dependencia con un feature `familia` que hoy no existe.
- `alarma_modificada` lleva el estado completo (no un diff) para que P1 no tenga que reconstruir campo por campo; cuesta bytes, ahorra bugs.
- No hay evento `alarma_borrada`: desactivar es suficiente (2.8).

## 9. Changelog

- `v1` — 2026-10-22 — creación. Fixtures iniciales escritos a mano junto con los schemas.
