# web/

Vacío a propósito. Lo genera el agente en `BOOTSTRAP.md` fase 4.

Consola de administración (Vite + TypeScript + Preact) desplegada en Cloudflare Pages. Misma información que la app, pero para administrar: lee y **escribe** — produce los mismos tipos de eventos que la app (crear, editar, desactivar, cargar en lote) enviándolos por `POST /sync/push` con `device_id: "web"`. No tiene lógica de proyecciones: consume `/api/projections/...` del servidor, que calcula todas las proyecciones.
