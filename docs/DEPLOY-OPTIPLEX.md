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
5. Resistencia a reinicios: ver [Reinicio del servidor](#reinicio-del-servidor).

## Reinicio del servidor

Cauce vuelve solo después de un `sudo reboot`, sin scripts propios, porque:

- `docker.service` y `containerd` están habilitados en systemd, y el contenedor tiene `restart: unless-stopped`: al arrancar, dockerd reinicia **el mismo contenedor** con la misma imagen, sin reconstruir ni recrear.
- `tailscaled` está habilitado y el Funnel creado con `--bg` se guarda en el estado de Tailscale: al arrancar vuelve a publicar `127.0.0.1:8080`.
- El servidor Go atiende SIGTERM y cierra limpio (Docker da 10 s). La base está en `deploy/data/`, en el disco raíz.

No hace falta una unidad de systemd con `docker compose up -d`, y no conviene: si la imagen o el compose cambiaron desde el último `just up`, recrearía el contenedor al arrancar, que equivale a un deploy sin revisar. Tampoco hay que pasar a `restart: always`: `unless-stopped` respeta una parada manual.

**Antes de reiniciar**

1. `docker compose ps`: `cauce` debe estar `Up`. Un contenedor parado a mano (`docker stop`, `docker compose stop`) **no** vuelve al reiniciar, y uno borrado (`docker compose down`) necesita `just up`.
2. `just backup`: no modifica nada y deja `cauce-backup-*.tar.gz` (gitignorado, modo 600) en la raíz del repo.
3. Que no haya un `just up` ni un build a medias. Reiniciar siempre con `sudo reboot`, nunca cortando la luz.

**Después de reiniciar**

1. `systemctl is-active docker tailscaled` → `active` dos veces; `uname -r` muestra el kernel nuevo.
2. `docker compose ps` → `cauce` en `Up`; `curl -s 127.0.0.1:8080/healthz` → 200.
3. `tailscale funnel status` → Funnel on, `proxy http://127.0.0.1:8080`; `curl` a `/healthz` por la URL pública del Funnel → 200.
4. La API exige token (D20): por la URL del Funnel, `/sync/pull?since=0` sin cabecera → 401; con `Authorization: Bearer …` → 200 (ver [Autenticación](#autenticación)).
5. La consola: abrir `https://<proyecto>.pages.dev` en el navegador, iniciar sesión con Google y ver la lista de alarmas (pasa por Access, la Pages Function y el Funnel). Sin sesión, `/api/...` responde 403 o redirige al login de Access.

**Si algo no levanta**

| Síntoma | Qué hacer |
|---------|-----------|
| Docker inactivo | `journalctl -u docker -b`, luego `sudo systemctl start docker` |
| Contenedor `Exited` | `docker compose logs --tail 100 cauce`, luego `docker compose start cauce` (mismo contenedor) |
| Contenedor inexistente | `just up` (reconstruye desde `HEAD`; los datos no se tocan) |
| Funnel apagado | `sudo tailscale funnel --bg 8080` |
| Tailscale sin sesión | Acción humana: el dueño vuelve a iniciar sesión |
| Docker o la red fallan con el kernel nuevo | Arrancar el kernel anterior desde GRUB (Debian conserva el previo) y avisar |

Nunca `docker compose down -v`, `just restore`, borrar `deploy/data/` ni tocar `server/.env`. `cloudflared-quick` cambia de URL en cada reinicio; Cauce no depende de él (usa el Funnel) y no se toca.

## Autenticación

Dos cerrojos independientes (D20 en `DECISIONS.md`): el servidor exige un token compartido y la web exige además un login de Cloudflare Access que la Pages Function verifica. **Ningún valor real va en este repo**: ni el token, ni el dominio del equipo de Access, ni el AUD, ni el email.

### Dónde vive cada secreto

| Nombre | Dónde | Valor |
|--------|-------|-------|
| `CAUCE_API_TOKEN` | `server/.env` del servidor (modo 600) | el token; mínimo 32 caracteres. Sin él el servidor no arranca |
| `cauceApiToken` | `~/.gradle/gradle.properties` de la máquina que compila el APK | el mismo token (queda dentro del APK) |
| `API_TOKEN` | secreto del proyecto de Pages | el mismo token |
| `ACCESS_TEAM_DOMAIN` | secreto del proyecto de Pages | `<equipo>.cloudflareaccess.com` |
| `ACCESS_AUD` | secreto del proyecto de Pages | el *Application Audience (AUD) Tag* de la aplicación de Access |
| `ACCESS_ALLOWED_EMAIL` | secreto del proyecto de Pages (opcional) | el email del dueño; si está, el JWT debe traer ese email |
| `API_ORIGIN` | secreto del proyecto de Pages (ya existía, D14) | el origen público del servidor |

Generar el token en el servidor, sin pegarlo en el chat ni en la historia del shell: `openssl rand -hex 32`. Los secretos de Pages se cargan con `wrangler pages secret put <NOMBRE> --project-name <proyecto>` (pide el valor por stdin) o en el panel; un cambio de secreto exige un nuevo deploy de Pages. `CAUCE_AUTH=off` desactiva el token en el servidor: solo para desarrollo local o instancias aisladas, nunca en el OptiPlex.

### Puesta en marcha (en este orden)

Los clientes primero y el servidor al final: un servidor sin token ignora la cabecera `Authorization`, así que el teléfono y la web nuevos funcionan contra el servidor viejo, y nada queda cortado entre pasos.

1. **Access** (acción humana en Zero Trust): crear la aplicación *self-hosted* para el hostname de Pages, con política *Allow* por email (login Google). Si se quiere que los previews `<hash>.<proyecto>.pages.dev` también pidan login, agregar ese comodín a la aplicación; si no, la Function igual los rechaza con 403. Anotar el dominio del equipo y el AUD.
2. **Pages:** cargar `API_TOKEN`, `ACCESS_TEAM_DOMAIN`, `ACCESS_AUD` y, si se quiere, `ACCESS_ALLOWED_EMAIL`; luego `cd web && VITE_API_BASE= npm run build` y `wrangler pages deploy web/dist --project-name <proyecto>`. Desde aquí la consola solo responde con sesión de Access válida.
3. **Teléfono:** agregar `cauceApiToken=<token>` a `~/.gradle/gradle.properties`, `just apk` e instalar el APK.
4. **Servidor:** agregar `CAUCE_API_TOKEN=<token>` a `server/.env` y `just up`. El log de arranque dice `auth: bearer token required on every route except GET /healthz`.

### Verificar

- `curl -s -o /dev/null -w '%{http_code}\n' 'https://<origen-publico>/sync/pull?since=0'` → `401`.
- `curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $(sed -n 's/^CAUCE_API_TOKEN=//p' server/.env)" 'http://127.0.0.1:8080/sync/pull?since=0'` → `200` (lee el token sin imprimirlo).
- `curl -s -o /dev/null -w '%{http_code}\n' https://<origen-publico>/healthz` → `200` sin cabecera.
- Pages sin sesión de Access: `curl -s -o /dev/null -w '%{http_code}\n' https://<hash>.<proyecto>.pages.dev/api/projections/alarmas/alarmas_activas` → `403` (o `302` al login si Access cubre ese hostname). En el navegador, con sesión, la consola carga.
- Teléfono: tocar Sincronizar o abrir la app; Home **no** muestra «Sin sincronizar: el servidor rechazó el token de la app (401)…».

### Rotar el token

Hay un solo token, así que rotar corta brevemente a los clientes que todavía tienen el viejo; la app es local-first y su outbox reenvía todo después, sin pérdida.

1. Generar uno nuevo (`openssl rand -hex 32`).
2. `API_TOKEN` en Pages y nuevo deploy.
3. `CAUCE_API_TOKEN` en `server/.env` y `just up`. Desde aquí el APK viejo recibe 401 y lo muestra en Home.
4. `cauceApiToken` en `~/.gradle/gradle.properties`, `just apk` e instalar.

Si el token se filtró, hacer los pasos 2–4 de inmediato. Rotar el login humano (Access) no toca el token ni el teléfono.

## Restricciones

- **Nunca** imprimir ni registrar contraseñas, tokens o `.env`. Para `sudo` sin TTY la contraseña se canaliza desde `pc-gary` con `sops -d --extract '["passwords"]["gary_optiplex"]' ~/fleet/secrets/main.yaml | ssh gary@optiplex sudo -S -p '' <cmd>`; la salida nunca se muestra.
- No tocar `cloudflared-quick` ni el puerto 8080 de otro servicio: HexCell (otro proyecto) comparte el servidor pero no publica puertos al host.
- No activar el perfil `tunnel` del compose: el servidor lo comparten varios proyectos y el túnel con nombre será **uno solo, como servicio del host** (sustituirá a `cloudflared-quick` cuando haya dominio), con una ruta por backend. Cloudflare Access también espera al dominio.

## Cloudflare (cuenta creada el 2026-10-25)

- Credenciales: `cloudflare.api_token` y `cloudflare.account_id` en `~/fleet/secrets/main.yaml` (sops), presente en `pc-gary` y en el servidor `optiplex`. Se pueden usar desde **cualquiera de los dos**, siempre inyectadas por comando (`CLOUDFLARE_API_TOKEN=$(sops -d --extract '["cloudflare"]["api_token"]' ~/fleet/secrets/main.yaml) wrangler …`). Nunca se escriben descifradas en disco, en el repo, en `.env` ni en el chat.
- La consola `web/` se publica en **Cloudflare Pages** (proyecto `cauce-web`, URL `cauce-web-2dr.pages.dev` hasta tener dominio); sin integración GitHub. Desde `pc-gary` u `optiplex`, con `CF=$(sops -d --extract '["cloudflare"]["api_token"]' ~/fleet/secrets/main.yaml)`:
  1. `npm i -g wrangler` si `command -v wrangler` falla (Node 22 ya está vía fnm).
  2. Una vez: `CLOUDFLARE_API_TOKEN=$CF wrangler pages project create cauce-web --production-branch main`.
  3. `web/public/_redirects` con `/* /index.html 200` (rutas de la SPA); `cd web && npm run build` con `VITE_API_BASE` **vacía** (las peticiones van al mismo origen).
  4. `CLOUDFLARE_API_TOKEN=$CF wrangler pages deploy web/dist --project-name cauce-web`.
- **Proxy de mismo origen (preferido)**: `web/functions/` es una Pages Function que reenvía `/api/*` y `/sync/*` al origen real de la API, definido en el proyecto de Pages como variable de entorno `API_ORIGIN` (p. ej. `https://api.example.com`, sin barra final; nunca en el repo; se fija en el panel de Pages o con `wrangler pages secret put API_ORIGIN --project-name cauce-web`). Evita CORS y el bloqueo de «acceso a red local» de navegadores cuyo DNS resuelve el host de la API a una dirección privada (VPN activa). Con esto, `VITE_API_BASE` queda vacía en el build.
- **CORS** (solo si se llama a la API desde otro origen): la SPA (`https://cauce-web-2dr.pages.dev`) y la API están en orígenes distintos. Si el servidor Go aún no responde CORS, es un cambio en `server/internal/core/` (middleware con origen permitido por variable de entorno, p. ej. `CAUCE_CORS_ORIGIN`, documentada en `server/.env.example`). Este encargo **autoriza explícitamente** ese cambio de core; decirlo en el resumen.
- **URL pública de la API hasta tener dominio**: preferir Tailscale Funnel (fija): el dueño habilita *Funnel* y *HTTPS certificates* en la consola de Tailscale (acción humana, pedirla), luego en el servidor `sudo tailscale funnel --bg 8080` → `https://optiplex.<tailnet>.ts.net` (el nombre exacto lo imprime `tailscale funnel status`; no escribirlo en el repo). Si no está habilitado, usar la URL de `tunnel-url` (cambia en cada reinicio del servicio) y avisar.
- Litestream/R2, Pages y whisper.cpp quedan fuera de este encargo.

## Pendiente externo (no bloquea)

- Dominio propio (~10 USD/año) para una URL estable y Cloudflare Access.
- Registrar la contraseña `vault_gary_optiplex_become_password` en el vault de `Scriptorium` si Ansible va a gestionar el host.
