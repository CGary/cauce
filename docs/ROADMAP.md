# Roadmap

Orden de construcción. Cada ítem es un spec en `schema/features/<id>/SPEC.md`; el agente lo implementa con `/feature <id>`. El criterio de orden: valor inmediato por día de uso y riesgo técnico que conviene retirar temprano.

## 0. Bootstrap (ver `BOOTSTRAP.md`)

Esqueleto de Android, server y web con el feature `alarmas` como primer caso real. `alarmas` va primero porque obliga a resolver lo nativo difícil (alarmas exactas, full-screen intent, boot receiver) y porque los remedios de la familia no admiten "más o menos".

## 1. `calendario_google` — reuniones de todas las cuentas de Google

Lee el calendario del teléfono (todas las cuentas de Google que ya están en Android, sin OAuth) y emite una `reunion_sincronizada` por ocurrencia dentro de los próximos 14 días. El aviso hereda el recordatorio de Google y se edita por serie; `alarmas` consume esos eventos para sonar. Resuelve "alguien me agendó una reunión y no la vi" y los cumpleaños del calendario personal. Sin Google Tasks ni escritura hacia Google.

## 2. `agenda` — las próximas 4 horas de mi vida

Sube desde el último lugar porque es el segundo dolor diario del dueño. En la noche se planifica el día siguiente como bloques con inicio y fin ("tres cosas del día + bloques"); `alarmas` consume los bloques para sonar cuando uno termina y empieza el siguiente (almuerzo incluido), con el mismo patrón que `calendario_google`. La cara visible es un widget grande en la pantalla de inicio, como el del clima pero ocupando casi toda la página, con lo que viene en las próximas 4 horas: bloques, reuniones de Google y alarmas sueltas. La app abre en ese mismo dashboard. Consume `reunion_sincronizada` y probablemente `insight_generado`; cómo mostrar también las alarmas sueltas sin romper "dos specs juntas, nunca tres" se decide en su spec.

## 3. `gym` — rutinas y timer de descanso

Reemplaza Google Keep + reloj. Rutina como lista de ejercicios con series/reps/peso; timer de descanso como foreground service con vibración y pantalla apagada; registro de cada serie como evento (`serie_completada`). Proyecciones: volumen semanal, última marca por ejercicio.

## 4. `finanzas` — bolsas de gasto

Reemplaza el Google Sheet. El modelo de bolsas (sobres) se mantiene; lo que cambia es la fricción: registrar un gasto es un botón grande + monto, y un widget muestra cuánto queda en cada bolsa. Eventos: `bolsa_definida`, `ingreso_registrado`, `gasto_registrado`, `gasto_anulado`, `periodo_cerrado`. Proyecciones: saldo por bolsa, gasto vs. ingreso del período, tendencia.

## 5. `sync` + `blobs` (core)

Push/pull con cursor, outbox, subida a R2 con WorkManager. Hasta acá todo funcionó solo en el teléfono; a partir de acá existe el backup y la consola de administración web (que escribe). Las escrituras de la web dependen de este ítem: hasta que sync exista, las features de administración web se especifican pero no se implementan.

## 6. `notas` — texto, audio, foto

Un solo feature con tres tipos de nota. Audio graba local y emite `nota_audio_creada`; el worker `whisper` agrega `transcripcion_agregada`. Foto reduce y sube. Proyección: lista por fecha, búsqueda en texto/transcripción.

## 7. `ubicacion`

Muestreo en background (cada 10 min, ajustable), evento `ubicacion_registrada`. Mapa en la web. Primer feature con permisos incómodos (`ACCESS_BACKGROUND_LOCATION`).

## 8. `insights` (worker)

Resumen nocturno de eventos agregados → LLM → `insight_generado` → notificación matutina. Más un servidor MCP de solo lectura sobre la base para conversar con los datos desde Claude u OpenCode.

## 9. `salud` — Health Connect

Pasos, pulso, sueño como eventos. Sin cambios de infra.
