# server/

Vacío a propósito. Lo genera el agente en `BOOTSTRAP.md` fase 2.

Un binario Go (`cmd/cauce`), SQLite, `net/http` estándar. Endpoints: `/sync/push`, `/sync/pull`, `/blobs/presign`, `/api/projections/{feature}/{name}`. Workers como goroutines. Ver `docs/ARCHITECTURE.md` §3–§5 y `docs/INFRA.md` para el despliegue.
