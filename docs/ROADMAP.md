# Roadmap

Orden de construcción. Cada ítem es un spec en `schema/features/<id>/SPEC.md`; el agente lo implementa con `/feature <id>`. El criterio de orden: valor inmediato por día de uso y riesgo técnico que conviene retirar temprano.

## 0. Bootstrap (ver `BOOTSTRAP.md`)

Esqueleto de Android, server y web con el feature `alarmas` como primer caso real. `alarmas` va primero porque obliga a resolver lo nativo difícil (alarmas exactas, full-screen intent, boot receiver) y porque los remedios de la familia no admiten "más o menos".

## 1. `gym` — rutinas y timer de descanso

Reemplaza Google Keep + reloj. Rutina como lista de ejercicios con series/reps/peso; timer de descanso como foreground service con vibración y pantalla apagada; registro de cada serie como evento (`serie_completada`). Proyecciones: volumen semanal, última marca por ejercicio.

## 2. `finanzas` — bolsas de gasto

Reemplaza el Google Sheet. El modelo de bolsas (sobres) se mantiene; lo que cambia es la fricción: registrar un gasto es un botón grande + monto, y un widget muestra cuánto queda en cada bolsa. Eventos: `bolsa_definida`, `ingreso_registrado`, `gasto_registrado`, `gasto_anulado`, `periodo_cerrado`. Proyecciones: saldo por bolsa, gasto vs. ingreso del período, tendencia.

## 3. `sync` + `blobs` (core)

Push/pull con cursor, outbox, subida a R2 con WorkManager. Hasta acá todo funcionó solo en el teléfono; a partir de acá existe el backup y el dashboard.

## 4. `notas` — texto, audio, foto

Un solo feature con tres tipos de nota. Audio graba local y emite `nota_audio_creada`; el worker `whisper` agrega `transcripcion_agregada`. Foto reduce y sube. Proyección: lista por fecha, búsqueda en texto/transcripción.

## 5. `ubicacion`

Muestreo en background (cada 10 min, ajustable), evento `ubicacion_registrada`. Mapa en el dashboard. Primer feature con permisos incómodos (`ACCESS_BACKGROUND_LOCATION`).

## 6. `insights` (worker)

Resumen nocturno de eventos agregados → LLM → `insight_generado` → notificación matutina. Más un servidor MCP de solo lectura sobre la base para conversar con los datos desde Claude u OpenCode.

## 7. `salud` — Health Connect

Pasos, pulso, sueño como eventos. Sin cambios de infra.

## 8. `agenda`

Último a propósito. No un plan hora por hora: tres cosas del día + bloques. Consume `alarma_creada` (reuniones) y probablemente `insight_generado`.

## Opcional / cuando duela

- Validar el JWT de Cloudflare Access en el servidor.
- Tablas de proyección materializadas si alguna proyección tarda más de 100 ms.
- Widget de Glance para bolsas (puede entrar con `finanzas` si el agente lo resuelve rápido).
