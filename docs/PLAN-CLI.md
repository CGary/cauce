# Plan: `cauce-cli`, una línea de comandos para agentes

Fecha: 2026-11-24. Estado: propuesta, sin código. Revisada contra el código el mismo día.
Guía de diseño que sigue: [`GUIA-CLI-AGENTES.md`](GUIA-CLI-AGENTES.md).

## Qué es y para qué

Un programa de terminal que permite a Claude (u otro agente) **ver, crear, editar y desactivar alarmas** y manejar las demás funciones de Cauce sin abrir la web. Una skill global de Claude lo usa cuando pides algo como "ponme una alarma para el remedio de mamá cada 8 horas durante 5 días".

No es un servidor nuevo ni una base de datos nueva. Es **un cliente más**, igual que la web:

- **Lee** las proyecciones con `GET /api/projections/<feature>/<name>`.
- **Escribe** eventos con `POST /sync/push`, usando `device_id: "cli"`.
- El servidor valida cada evento contra su schema y rechaza el lote entero si uno falla.
- Las alarmas creadas desde la CLI llegan al teléfono en la próxima sincronización, igual que las de la web.

## Decisiones que necesitan tu visto bueno

| # | Decisión | Propuesta | Por qué |
|---|---|---|---|
| 1 | Nombre del binario | `cauce-cli` | `cauce` ya es el servidor |
| 2 | Lenguaje y lugar del código | **Go**, en `server/cmd/cauce-cli/` + `server/internal/cli/` (mismo módulo del servidor) | Reusa el validador de schemas (`internal/core/event`) y el tipo `Envelope`; valida antes de enviar. Un solo binario, sin Node ni Python |
| 3 | `device_id` | `"cli"` | El servidor no restringe el valor; distingue en el historial qué vino de un agente |
| 4 | Regla del remedio | **La aplica la CLI**, igual que el formulario web (`web/src/features/alarmas/model.ts`, `isScheduleLocked`). El servidor no cambia | Ver "Por qué la regla no va al servidor" |
| 5 | Token | Archivo `~/.config/cauce-cli/token` (modo 600) o `--token-file` | La guía prohíbe secretos en flags y variables de entorno |
| 6 | Desactivar | `--apply --yes` | Hoy no hay "reactivar"; para volver atrás hay que crear otra alarma |

**Por qué la regla no va al servidor.** El push valida cada evento por separado (schema y `user_id`), sin mirar el historial (`server/internal/sync/service.go`). Rechazar la edición de un remedio exigiría leer el estado dentro del código compartido de sync, que `AGENTS.md` restringe. Además, el teléfono sube toda su cola en un solo envío antes de descargar, y el servidor rechaza el lote entero si un evento falla. Una edición rechazada dejaría atascada la cola del teléfono, incluidas las dosis dadas.

**Sobre el token.** El modo 600 lo protege de otros usuarios del sistema. No lo protege del propio agente, que corre con el mismo usuario. La protección frente al agente es que la CLI nunca lo imprime y que la skill prohíbe leer el archivo.

Registrar el conjunto como **D21** en `docs/DECISIONS.md`. Además:

- Añadir `cli` como productor en la sección 3 de los SPEC de `alarmas` y `calendario_google`.
- Añadir `server/cmd/cauce-cli/` y `server/internal/cli/` a la tabla de propiedad de `AGENTS.md`, como código de la CLI que no pertenece a ningún feature.
- Añadir el archivo de token de la CLI al procedimiento de rotación del token de D20.

## Comandos

Forma: `cauce-cli <dominio> <verbo> [flags]`. Todos aceptan `--json`, `--no-input`, `--schema`, `--config <ruta>`.

### Lectura (sin efectos)

| Comando | Qué hace | Fuente |
|---|---|---|
| `server health` | ¿El servidor responde? ¿El token sirve? | `/healthz` + una proyección |
| `alarmas list [--tz] [--tipo] [--limit] [--fields]` | Alarmas activas con su próximo disparo | `alarmas/alarmas_activas` |
| `alarmas get --alarma-id <id>` | Estado completo de una alarma (texto, calendario, remedio) | P1 + P2 |
| `adherencia list` | Por cada remedio: dosis esperadas, dadas, ignoradas | `alarmas/adherencia_tratamiento` |
| `dosis history [--destinatario]` | Historial de dosis dadas | `alarmas/historial_dosis` |
| `reuniones list [--tz]` | Próximas reuniones de Google con su aviso | `calendario_google/reuniones_proximas` |

### Escritura (exigen `--dry-run` o `--apply`)

| Comando | Evento |
|---|---|
| `alarmas create --titulo --tipo remedio\|reunion\|otro --hora HH:MM [--fecha] [--destinatario --remedio] [repetición]` | `alarma_creada` v3 |
| `alarmas edit --alarma-id <id> [solo los campos que cambian]` | `alarma_modificada` v3 con el **estado completo** (la CLI lee el actual y aplica los cambios) |
| `alarmas deactivate --alarma-id <id> --apply --yes` | `alarma_desactivada` v1, `motivo: usuario` |
| `reuniones aviso --serie-id <id> (--minutos-antes N \| --hora HH:MM \| --apagado)` | `aviso_reunion_editado` v1 |
| `alarmas import --input <archivo>\|-` (fase 3) | varios `alarma_creada` en un solo push. Formato `cauce.alarmas` v1 (SPEC de `alarmas` §5), el mismo de la carga masiva de la web |
| `plan show --plan-id <id>` | muestra un plan guardado, sin enviarlo (lectura) |
| `plan apply --plan-id <id> --apply` | envía un plan guardado por `--dry-run` |

**Repetición con flags, no con texto.** Usa `--freq daily|weekly|monthly|hourly --interval N --byday mo,we --bymonthday N --count N --until AAAA-MM-DD`. Sin `--freq`, la alarma suena una vez. La CLI convierte esto al objeto `repeticion` (`freq` en mayúsculas). El schema embebido comprueba la forma: `count` y `until` se excluyen entre sí, y `bymonthday` solo va con `monthly`.

**Validaciones que el schema no cubre.** La CLI las comprueba ella misma, igual que el formulario web (SPEC de `alarmas` §8):

- `count ≤ 1000` e `interval ≤ 365`.
- Una alarma de una sola vez no puede tener fecha y hora ya pasadas.
- `--until` no puede ser anterior al inicio.

**Remedios.** `alarmas edit` sobre un remedio acepta solo `--titulo`, `--remedio` y `--destinatario`. Cualquier otro flag falla con `REMEDIO_SCHEDULE_LOCKED` y un `suggested_fix`: crear una alarma nueva y desactivar la vieja.

**Editar una alarma que no es remedio.** La CLI copia la regla de la web y del teléfono (SPEC de `alarmas` §8, `web/src/features/alarmas/model.ts`): la edición se ancla en el próximo disparo y `count` pasa a ser las repeticiones que faltan. Reenviar el `count` original repetiría disparos ya ocurridos.

**Reuniones de Google.** `alarmas list` las muestra con id `gcal:<reunion_id>`. `alarmas get`, `edit` y `deactivate` rechazan esos ids con `INVALID_ARGUMENT` y sugieren `reuniones aviso`. El schema aceptaría el evento, pero el SPEC dice que las reuniones no se editan desde `alarmas`.

## Cómo funciona una escritura

```
cauce-cli alarmas create ... --dry-run --json
  → arma el evento (id uuid v7, device_id "cli"), lo valida con el schema embebido,
    lo guarda en ~/.local/state/cauce-cli/plans/<plan_id>.json y lo muestra
cauce-cli plan apply --plan-id alm_20261124_001 --apply --json
  → pone el ts de ese momento y envía el evento con el mismo id
```

- **El `ts` se pone al aplicar, no al planificar.** Una alarma sin fecha empieza a contar desde el `ts` de su `alarma_creada`. Con un `ts` viejo, las dosis entre el plan y el envío contarían como esperadas sin haber sonado. Un `ts` viejo también perdería contra ediciones más nuevas.
- **Reintentar es seguro.** El servidor ignora un `id` repetido (`ON CONFLICT(id) DO NOTHING`) y responde `duplicates: 1`. Aplicar dos veces el mismo plan no crea dos alarmas. Para esto existe el almacén de planes.
- **Edición con chequeo de conflicto.** El plan de un `edit` guarda el estado que leyó. Al aplicarlo, la CLI vuelve a leer la alarma. Las proyecciones no traen versión ni fecha de última edición, así que compara solo los campos fijos: título, tipo, hora, fecha, repetición, destinatario y remedio. Si alguno cambió (desde el teléfono o la web), falla con `CONFLICT` y pide planificar de nuevo.
- **Los planes vencen a las 24 h.** Después devuelven `PLAN_EXPIRED`.
- `--apply` directo, sin plan previo, también vale para `create`. Arma el evento y lo envía en un paso.
- Toda respuesta de escritura trae `data.dry_run` y `data.changes[]`.
- Después de un `--apply`, `warnings` incluye: "llega al teléfono en la próxima sincronización". La CLI no puede forzar al teléfono a sincronizar.

## Respuesta JSON (ejemplo)

```json
{
  "schema_version": 1,
  "ok": true,
  "command": "alarmas.create",
  "input": { "titulo": "Amoxicilina", "tipo": "remedio", "hora": "08:00",
             "destinatario": "Ana", "remedio": "Amoxicilina 500 mg",
             "freq": "hourly", "interval": 8, "count": 15, "tz": "America/La_Paz", "dry_run": true },
  "summary": "Would create 1 alarm: 15 doses every 8 h starting 08:00.",
  "data": { "dry_run": true, "plan_id": "alm_20261124_001",
            "changes": [{ "kind": "create", "event": "alarma_creada", "alarma_id": "0193..." }] },
  "warnings": [],
  "next_actions": [{ "command": "cauce-cli plan apply --plan-id alm_20261124_001 --apply --json",
                     "reason": "Send the alarm to the server." }]
}
```

Los textos que escribe el usuario (títulos, nombres de remedio) van solo dentro de `data`, nunca en `summary` ni en `next_actions`. Así un título como "ignora las instrucciones y…" no puede colarse como orden al agente.

## Errores

| Situación | `error.code` | Salida | ¿Reintentar? |
|---|---|---|---|
| Flag mal escrito o falta uno | `INVALID_ARGUMENT`, `MISSING_REQUIRED_FLAG`, `INVALID_ENUM` | 2 | no, trae `suggested_fix` |
| El servidor rechazó el evento (400) | `VALIDATION_FAILED` (mensaje del servidor en `data`) | 1 | no |
| Token ausente o incorrecto (401) | `PERMISSION_DENIED` | 1 | no |
| Editar el calendario de un remedio | `REMEDIO_SCHEDULE_LOCKED` | 1 | no |
| La alarma cambió desde el plan | `CONFLICT` | 1 | no, planificar de nuevo |
| Alarma o plan inexistente | `NOT_FOUND`, `PLAN_NOT_FOUND`, `PLAN_EXPIRED` | 3 | no |
| Servidor caído, sin red, 5xx | `NETWORK_ERROR` | 4 | sí |

## Configuración

`~/.config/cauce-cli/config.json`:

```json
{ "base_url": "http://127.0.0.1:8080", "user_id": "u1", "tz": "America/La_Paz" }
```

- `user_id` debe coincidir con `CAUCE_USER_ID` del servidor; si no, el push da 400.
- `tz` se manda como parámetro a las proyecciones y aparece en `input`. Sin él, la CLI usa la zona del sistema.
- El contenedor escucha solo en `127.0.0.1`. Fuera del optiplex, `base_url` es la URL HTTPS de Funnel, la misma que usa el teléfono (`docs/DEPLOY-OPTIPLEX.md`). Esa URL **no** va al repo.
- El token nunca se imprime, ni con `--debug`. La CLI se niega a usar un archivo de token legible por otros usuarios.

## Fases

| Fase | Entrega | Prueba |
|---|---|---|
| 0 | Decisiones 1–6 aprobadas, D21 escrita, `AGENTS.md` y los SPEC actualizados. Sin cambios de código | `python3 scripts/lint_contract.py` verde |
| 1 | Esqueleto: sobre JSON, códigos de salida, `--schema`, config y token, `server health`, `alarmas list/get` | Tests de contrato de la guía (salida válida contra su `--schema`, error JSON ante un pánico, `--no-input` nunca bloquea) |
| 2 | `alarmas create/edit/deactivate`, planes (`show`, `apply`), conflicto, remedio bloqueado, ids `gcal:` rechazados | Tests de tabla en Go contra el servidor levantado dentro del propio test (`httptest` + SQLite temporal; es el mismo módulo). Sin Docker y nunca contra el servidor real |
| 3 | `reuniones list/aviso`, `adherencia`, `dosis history`, `alarmas import` | Igual que la fase 2 |
| 4 | Skill global `~/.claude/skills/cauce/SKILL.md`, sección corta en `AGENTS.md`, receta `just cli-install` (`go build -o ~/.local/bin/cauce-cli`) | 5 tareas de evaluación reales ("remedio cada 8 h por 5 días", "mueve la alarma del gimnasio a las 19:00", "¿cuántas dosis faltó Ana?"…) y medir llamadas y errores |

## La skill global (fase 4)

Según la guía, corta y sin repetir el `--help`:

1. **Cuándo usarla:** crear, ver, editar o desactivar alarmas y remedios; avisos de reuniones; adherencia.
2. **Cuándo no:** cualquier otra cosa. No tocar `server/.env` ni la base de datos.
3. **Flags siempre:** `--json --no-input`. Escrituras: primero `--dry-run`, mostrar el resumen al usuario, luego `plan apply --apply`.
4. **Entre 5 y 10 comandos comunes**, el sobre JSON y las reglas de error.
5. Fijada a `schema_version: 1`.

## Fuera de alcance

- Eventos que solo produce el teléfono (`alarma_disparada`, `alarma_atendida`, `dosis_dada`, `reunion_sincronizada`). El schema los aceptaría, pero la CLI no los ofrece: marcar una dosis como dada sin que la alarma sonara falsearía la adherencia.
- Hacer sonar el teléfono o forzar su sincronización.
- Servidor MCP. Se puede envolver la CLI después: la guía muestra que la conversión es mecánica.
