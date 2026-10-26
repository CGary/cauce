# Despliegue en el OptiPlex

Encargo para el agente que trabaje en este repo: **poner Cauce a correr en el servidor `optiplex` y dejarlo accesible por el Quick Tunnel ya existente.** Este documento contiene solo lo que no se deduce del repo (estado del servidor y cómo entrar). Qué es Cauce y cómo se arranca: [`INFRA.md`](INFRA.md), [`../justfile`](../justfile), [`../compose.yaml`](../compose.yaml).

## El servidor (estado al 2026-10-24)

| Dato | Valor |
|------|-------|
| Hostname / usuario | `optiplex` / `gary` (sudo) |
| Acceso | `ssh gary@optiplex` por Tailscale (el nombre resuelve dentro de la tailnet; la IP LAN está en el router). Solo clave SSH; la de `pc-gary` ya está autorizada. **Este repo es público: no escribir aquí IPs, hostnames del túnel ni datos de la red doméstica** |
| SO | Debian 13 (trixie), sin escritorio |
| Hardware | i7-4770, 8 hilos, 9,7 GiB RAM, SSD 196 GB libres |
| Docker | 29.x + Compose v5, cgroup v2 (`systemd`). `gary` está en el grupo `docker` |
| Instalado además | `git`, `just`, `cron`, `cloudflared`, `tmux`, `fish`, `nvim` |
| Puerto 8080 | Ocupado por un contenedor **placeholder** `whoami` (`docker rm -f whoami` antes de levantar Cauce) |
| Túnel | Servicio del host `cloudflared-quick` → `http://localhost:8080`. URL pública aleatoria (`*.trycloudflare.com`), cambia en cada reinicio: verla con `tunnel-url` |

Cómo se aprovisionó (scripts idempotentes, repo `Scriptorium` en `pc-gary`): `~/Scriptorium/scripts/debian/server-bootstrap.sh`, `server-comfort.sh`, `cloudflared-quick.sh`, runbook `README-optiplex.md`.

## Lo que hay que hacer

1. Código en el servidor: `git clone https://github.com/CGary/cauce.git ~/cauce` (repo público, sin claves en el servidor). Rama `main`.
2. `server/.env` a partir de `server/.env.example` **sin** `TUNNEL_TOKEN` (no hay dominio todavía). Variables que necesiten secreto se crean en el servidor, nunca se pegan en el chat ni en git.
3. `docker rm -f whoami && just up` → construye la imagen, publica en `127.0.0.1:8080`, espera `/healthz`; datos en `deploy/data/`.
4. Verificar: `curl -s localhost:8080/healthz` y luego `curl -s https://$(tunnel-url)/healthz` desde fuera.
5. Resistencia a reinicios: el compose ya lleva `restart: unless-stopped`; comprobar con `sudo reboot` solo si es barato.

## Restricciones

- **Nunca** imprimir ni registrar contraseñas, tokens o `.env`. Para `sudo` sin TTY la contraseña se canaliza desde `pc-gary` con `sops -d --extract '["passwords"]["gary_optiplex"]' ~/fleet/secrets/main.yaml | ssh gary@optiplex sudo -S -p '' <cmd>`; la salida nunca se muestra.
- No tocar `cloudflared-quick` ni el puerto 8080 de otro servicio: HexCell (otro proyecto) comparte el servidor pero no publica puertos al host.
- No activar el perfil `tunnel` del compose: el servidor lo comparten varios proyectos y el túnel con nombre será **uno solo, como servicio del host** (sustituirá a `cloudflared-quick` cuando haya dominio), con una ruta por backend. Cloudflare Access también espera al dominio.

## Cloudflare (cuenta creada el 2026-10-25)

- Credenciales: `cloudflare.api_token` y `cloudflare.account_id` en `~/fleet/secrets/main.yaml` de `pc-gary` (sops). Se usan **solo desde `pc-gary`**, inyectadas por comando (`CLOUDFLARE_API_TOKEN=$(sops -d --extract '["cloudflare"]["api_token"]' ~/fleet/secrets/main.yaml) wrangler …`). Nunca van al servidor, al repo ni al chat.
- La consola `web/` se publica en **Cloudflare Pages** (proyecto `cauce-web`, URL `cauce-web.pages.dev` hasta tener dominio); sin integración GitHub. Desde `pc-gary`, con `CF=$(sops -d --extract '["cloudflare"]["api_token"]' ~/fleet/secrets/main.yaml)`:
  1. `npm i -g wrangler` si `command -v wrangler` falla (Node 22 ya está vía fnm).
  2. Una vez: `CLOUDFLARE_API_TOKEN=$CF wrangler pages project create cauce-web --production-branch main`.
  3. `web/public/_redirects` con `/* /index.html 200` (rutas de la SPA); `cd web && VITE_API_BASE=<URL pública de la API> npm run build`.
  4. `CLOUDFLARE_API_TOKEN=$CF wrangler pages deploy web/dist --project-name cauce-web`.
- **CORS**: la SPA (`https://cauce-web.pages.dev`) y la API están en orígenes distintos. Si el servidor Go aún no responde CORS, es un cambio en `server/internal/core/` (middleware con origen permitido por variable de entorno, p. ej. `CAUCE_CORS_ORIGIN`, documentada en `server/.env.example`). Este encargo **autoriza explícitamente** ese cambio de core; decirlo en el resumen.
- **URL pública de la API hasta tener dominio**: preferir Tailscale Funnel (fija): el dueño habilita *Funnel* y *HTTPS certificates* en la consola de Tailscale (acción humana, pedirla), luego en el servidor `sudo tailscale funnel --bg 8080` → `https://optiplex.<tailnet>.ts.net` (el nombre exacto lo imprime `tailscale funnel status`; no escribirlo en el repo). Si no está habilitado, usar la URL de `tunnel-url` (cambia en cada reinicio del servicio) y avisar.
- Litestream/R2, Pages y whisper.cpp quedan fuera de este encargo.

## Pendiente externo (no bloquea)

- Dominio propio (~10 USD/año) para una URL estable y Cloudflare Access.
- Registrar la contraseña `vault_gary_optiplex_become_password` en el vault de `Scriptorium` si Ansible va a gestionar el host.
