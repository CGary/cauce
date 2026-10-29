# Alarmas tan intrusivas como el reloj del sistema

Encargo para el agente que trabaje en este repo: **que una alarma de Cauce suene a la hora exacta, con el teléfono en silencio y sobre la pantalla de bloqueo, igual que la app de reloj que trae el teléfono.** Afecta solo a `alarmas`; no hay feature de recordatorios aparte, y `calendario_google` delega el sonido en `alarmas` (su SPEC 2.10). Este documento contiene lo que no se deduce del repo: los síntomas, la evidencia y las decisiones. El flujo de trabajo es el de siempre: [`../AGENTS.md`](../AGENTS.md) y el skill `/feature alarmas`.

## Síntomas (dueño, 2026-10-29)

1. La alarma llega tarde, hasta un minuto.
2. Con el teléfono en silencio no suena.
3. El bloqueo del teléfono interfiere: no aparece la pantalla de la alarma.

## Evidencia en el teléfono (Android 16, ColorOS, APK debug 0.1.0)

Leída por ADB con `dumpsys`. Para repetirla, conectar ADB y usar los comandos de [Verificación](#verificación).

| Qué | Resultado | Lectura |
|---|---|---|
| `dumpsys alarm`, alarmas `FIRE` de Cauce | `window=+1h0m0s`, `exactAllowReason=policy_permission` | La app pide alarma exacta y tiene permiso, pero ColorOS le asigna una ventana de 1 h: puede sonar en cualquier momento de esa hora |
| `dumpsys alarm`, alarma del reloj de ColorOS a la misma hora | `window=0` | Las alarmas de despertador no se alinean |
| `dumpsys audio` | El modo silencio silencia `STREAM_RING`, `STREAM_NOTIFICATION` y `STREAM_SYSTEM`; `STREAM_ALARM` no está silenciado | El canal de alarma funciona; lo que se pierde es el sonido de **notificación** |
| Canal `alarmas_disparos_v2` | Importancia 4, sonido `alarm_alert`, `usage=USAGE_ALARM` | Aun así es un sonido de notificación, y ColorOS lo calla en silencio |
| `cmd appops get` | `USE_FULL_SCREEN_INTENT` y `SYSTEM_ALERT_WINDOW` con un rechazo registrado justo después de un disparo | ColorOS bloqueó abrir la pantalla completa |
| Batería | La app está en la lista blanca de ahorro y en el bucket de standby 5 (exento); `RUN_ANY_IN_BACKGROUND: allow` | La batería ya no es el problema |

## Causas en el código (feature `alarmas`, módulo `android/feature/alarmas`)

| Síntoma | Dónde | Causa |
|---|---|---|
| 1 | `device/AlarmScheduler.kt` `scheduleAt` | `setExactAndAllowWhileIdle`, que ColorOS alinea. El reloj del sistema usa `setAlarmClock`, que no se alinea, saca al teléfono de Doze y muestra el ícono de alarma |
| 2 | `device/AlarmNotifications.kt` | El sonido y la vibración son los del canal de notificación con `FLAG_INSISTENT`: los reproduce el sistema y obedece al modo silencio. Un reloj reproduce él mismo el tono por `USAGE_ALARM` desde un servicio en primer plano |
| 3 | `device/AlarmAlertActivity.kt` `showOverLockScreen` | `requestDismissKeyguard()`: con PIN o huella pide desbloquear en vez de mostrarse encima. Un reloj solo usa `showWhenLocked` y `turnScreenOn` |

## Lo que hay que hacer

### 1. SPEC primero (`schema/features/alarmas/SPEC.md`)

- §5 Android, «Notificaciones / servicios»: `AlarmManager.setAlarmClock` por disparo (y por posposición). El tono y la vibración de la alarma los reproduce un servicio en primer plano por `USAGE_ALARM`, que no respeta el modo silencio y que No molestar deja pasar por defecto. La notificación queda sin sonido propio: lleva la pantalla completa y las acciones Hecho y Posponer, y es la notificación del servicio.
- §5 Permisos, uno por línea con su justificación (lo exige el skill `new-feature`):
  - corregir `SCHEDULE_EXACT_ALARM`: el manifiesto ya usa `USE_EXACT_ALARM` en Android 13+ y `SCHEDULE_EXACT_ALARM` solo hasta API 32;
  - `FOREGROUND_SERVICE` y `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`: tipo `systemExempted`, previsto para apps con alarmas exactas que siguen sonando en segundo plano;
  - `WAKE_LOCK`: la CPU no duerme mientras suena.
- §5 o §8: requisitos del teléfono en ColorOS (ver [abajo](#configuración-del-teléfono-coloros)), porque sin ellos el sistema puede cerrar la app a la fuerza y se pierden todas las alarmas programadas.
- §8, una línea `(agente)`: por qué `setAlarmClock` (ventana de 1 h de ColorOS con `setExactAndAllowWhileIdle`), por qué un servicio (sonido de notificación callado en silencio) y por qué se quita `requestDismissKeyguard`.
- Subir `versión spec` y agregar la línea del changelog. **Ojo:** al 2026-10-29 hay trabajo sin commitear de `calendario_google` que lleva la SPEC de `alarmas` a v5. Este cambio va **encima** de esa v5, como v6; no se mezclan en un mismo commit.
- Sin cambios de eventos, de proyecciones ni de fixtures: todo el cambio está en el dispositivo. La regla de eventos compartidos entre features no aplica.

### 2. Código (solo `android/feature/alarmas/**`)

- **`AlarmScheduler.scheduleAt`**: para el disparo y la posposición, `setAlarmClock(AlarmClockInfo(millis, showIntent), pending)`, con `showIntent` abriendo la lista de alarmas. Mantener el fallback inexacto si `canScheduleExactAlarms()` es falso (API 31-32). El **timeout de 2 min** no debe aparecer como «próxima alarma» en la barra: puede quedar con `setExactAndAllowWhileIdle` como respaldo, pero el corte real lo hace el servicio (siguiente punto), porque con la ventana de ColorOS ese respaldo puede llegar tarde.
- **Servicio nuevo `AlarmRingingService`** (`foregroundServiceType="systemExempted"`):
  - Lo arranca `AlarmActions.fire` con `startForegroundService`. Arrancar un servicio en primer plano desde segundo plano está permitido al recibir una alarma exacta; `dumpsys` muestra la lista blanca temporal de 10 s.
  - `startForeground` con la notificación actual (pantalla completa, Hecho, Posponer) en un canal **sin sonido ni vibración**. Los canales son inmutables: el canal nuevo lleva otro id y se borra `alarmas_disparos_v2`, como ya se hizo con el canal anterior.
  - Reproduce el tono por defecto de alarma (`RingtoneManager.TYPE_ALARM`) en bucle con `MediaPlayer` o `Ringtone` y `AudioAttributes.USAGE_ALARM`, más vibración en bucle con atributos de alarma. Toma un `WakeLock` parcial.
  - Corta a los 2 minutos llamando a `AlarmActions.timeout`. También se detiene con Hecho y con Posponer, y cuando aparece un `alarma_atendida` terminal en el log. Esa observación ya existe en `AlarmAlertActivity`: reutilizarla, no duplicar lógica.
  - Varias alarmas a la vez: el servicio suena mientras haya alguna sin atender y para con la última.
  - Declararlo en `feature/alarmas/src/main/AndroidManifest.xml` con los permisos nuevos.
- **`AlarmAlertActivity`**: quitar `requestDismissKeyguard`. Hecho y Posponer funcionan sin desbloquear.
- **`AlarmNotifications`**: deja de poner sonido y `FLAG_INSISTENT`. Conserva `CATEGORY_ALARM`, la prioridad máxima, la pantalla completa y `VISIBILITY_PUBLIC`.
- **`AlarmListScreen`**: ya ofrece abrir los ajustes de alarmas exactas y de pantalla completa. Si `canUseFullScreenIntent()` es falso, el aviso debe verse. Lo propio de ColorOS (ventanas emergentes, pantalla de bloqueo, inicio automático) no tiene API pública: a lo sumo un texto que diga qué activar.
- Tests JVM (`./gradlew :core:events:test :feature:alarmas:test`): la lógica pura que se extraiga (cuándo cortar, cuándo seguir sonando con varias alarmas) va con tests en `feature/alarmas/src/test`. `FiringTest` y `SnoozeTest` deben seguir verdes.

### 3. Documentación

- `BOOTSTRAP.md:113` dice «Exact alarms via `setExactAndAllowWhileIdle`»: pasarlo a `setAlarmClock` + servicio en primer plano que hace sonar la alarma.
- No hace falta tocar `AGENTS.md`, `docs/ARCHITECTURE.md`, `docs/DECISIONS.md`, `docs/ROADMAP.md` ni los skills: hablan de «alarmas exactas» y «foreground service» en general y siguen siendo ciertos.
- `schema/features/calendario_google/SPEC.md:25` («ignorada a los 2 minutos») cambia solo si el dueño cambia esa duración.

## Configuración del teléfono (ColorOS)

Ajustes → Apps → Cauce. Solo el dueño puede activarlo:

- Batería: **Sin restricciones** (ya está).
- **Inicio automático**: activado.
- **Ventanas emergentes / Mostrar sobre otras apps**: permitido.
- **Mostrar en pantalla de bloqueo**: permitido.
- Notificaciones → Alarmas: pantalla completa permitida.

## Verificación

1. `python3 scripts/lint_contract.py` (necesita `pip install jsonschema`) y `cd android && ./gradlew :core:events:test :feature:alarmas:test`.
2. `./gradlew :app:assembleDebug`. **Instalar en el teléfono necesita el permiso explícito del dueño**: `AGENTS.md` dice «do not install» y el dueño pidió no recompilar sin preguntar.
3. Con el APK nuevo instalado y una alarma programada:
   - `adb shell dumpsys alarm | grep -A3 com.cauce.app.debug` → las alarmas `FIRE` con `window=0`.
   - `adb shell dumpsys alarm | grep -i "next alarm clock\|nextAlarmClock"` muestra la de Cauce, y aparece el ícono de alarma en la barra.
4. Pruebas en el teléfono, cada una con una alarma de prueba a 2-3 minutos:
   - en **silencio** y bloqueado con PIN: suena por el volumen de alarma, se enciende la pantalla y la alarma aparece sobre el bloqueo sin pedir PIN;
   - **Hecho** y **Posponer** desde la pantalla bloqueada; Posponer vuelve a sonar a los 10 min;
   - sin atender: deja de sonar a los 2 min y queda `ignorada` en el historial;
   - comparar la hora real del disparo con la programada (menos de 5 s de diferencia).

## Restricciones

- Este repo es público: no escribir aquí la IP del teléfono, el puerto de ADB ni datos de la red doméstica.
- Tocar solo `android/feature/alarmas/**`, `schema/features/alarmas/**` y `BOOTSTRAP.md`. Si hace falta algo de `android/core/**`, parar y preguntar.
- Commits `alarmas: …` con el email noreply; push solo si el dueño lo pide, revisando antes lo que se publica (`CLAUDE.md`).
- Decisión pendiente del dueño, que no bloquea: cuánto suena antes de quedar `ignorada`. La spec dice 2 min; un reloj típico suena 5-10 min y sube el volumen de a poco. Si cambia, va a §2.3 y a `calendario_google` §2.10.
