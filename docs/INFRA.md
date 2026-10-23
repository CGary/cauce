# Infraestructura

Objetivo: costo mensual ≈ 0 más el dominio. Todo lo de abajo es reemplazable sin tocar el contrato.

| Pieza | Servicio | Costo |
|-------|----------|-------|
| Backend | PC viejo con Linux, Docker Compose | luz |
| Exposición | Cloudflare Tunnel (`cloudflared`) | gratis |
| Acceso | Cloudflare Access (login Google + service token) | gratis hasta 50 usuarios |
| Blobs | Cloudflare R2 | gratis hasta 10 GB |
| Backup DB | Litestream → R2 | gratis |
| Consola de administración | Cloudflare Pages | gratis |
| Dominio | Cloudflare Registrar o el que tengas | ~US$10/año |
| Transcripción | whisper.cpp en el PC | gratis |

## PC viejo

Linux mínimo (Debian/Ubuntu Server) con Docker Compose y `just`. `just up` construye la imagen, monta `deploy/data/` y publica el servidor solo en `127.0.0.1`. El estado persistente es únicamente `deploy/data/cauce.db` y `server/.env`; el resto se reconstruye desde Git.

El túnel no arranca por defecto. Cuando esté configurado, se habilita con `just tunnel` y su hostname público apunta a `http://cauce:8080` dentro de Compose.

## Mudanza a otra máquina

1. En el PC nuevo: `git clone ...` y `just up`.
2. En el PC viejo: `just down` y `just backup`.
3. Copia el archivo indicado con `scp`.
4. En el PC nuevo: `just restore cauce-backup-...tar.gz`.
5. Arranca el túnel solo en el PC nuevo con `just tunnel`.

No se pierde nada: la outbox del teléfono reintenta cuando el servidor vuelve a estar disponible y el push es idempotente por `event id`.

## Cloudflare Tunnel

El túnel corre como el servicio `cloudflared` de Compose (perfil `tunnel`), no como servicio del host.

1. Creá el túnel y el hostname una sola vez:

```bash
cloudflared tunnel login
cloudflared tunnel create cauce
cloudflared tunnel route dns cauce api.<tu-dominio>
```

2. Configurá el ingress del túnel en el dashboard de Cloudflare hacia `http://cauce:8080` (el nombre del servicio en la red de Compose), no a `localhost:8080`.

3. Copiá el token del túnel a `server/.env`:

```
TUNNEL_TOKEN=...
```

4. Arrancalo con `just tunnel` (equivale a exportar `server/.env` y correr `docker compose --profile tunnel up -d cloudflared`).

No se abre ningún puerto en el router; la IP de casa no importa.

## Cloudflare Access

Dos aplicaciones en Zero Trust → Access:

1. **`api.<tu-dominio>`** — política *Allow*: emails = el tuyo (login Google) **y** un *Service Token* llamado `android-app`. La app manda los headers `CF-Access-Client-Id` / `CF-Access-Client-Secret` en cada request. Guardá el secret en el teléfono con `EncryptedSharedPreferences`; se pega una vez en una pantalla de ajustes.
2. **`app.<tu-dominio>`** (Pages) — política *Allow*: tu email.

El servidor Go confía en que solo llegan requests que pasaron por Access. Si querés endurecerlo, validá el JWT de `Cf-Access-Jwt-Assertion` contra el JWKS de tu equipo; es una función de 30 líneas y está en el roadmap como opcional.

## R2

Bucket `cauce` (privado). Crear un API token con permisos *Object Read & Write* solo para ese bucket. Variables:

```
R2_ACCOUNT_ID=
R2_ACCESS_KEY_ID=
R2_SECRET_ACCESS_KEY=
R2_BUCKET=cauce
```

El endpoint es `https://<account_id>.r2.cloudflarestorage.com`. El SDK de S3 funciona tal cual con ese endpoint y región `auto`.

## Litestream (backup continuo de SQLite)

```yaml
# /etc/litestream.yml
dbs:
  - path: /ruta/a/cauce/deploy/data/cauce.db
    replicas:
      - type: s3
        bucket: cauce
        path: litestream/cauce.db
        endpoint: https://<account_id>.r2.cloudflarestorage.com
        access-key-id: ...
        secret-access-key: ...
```

El `path` es el archivo que Compose monta desde `deploy/data/`: usá la ruta absoluta a ese directorio en el host. Litestream todavía corre en el host, fuera de Compose (ver D13). Ejecutá `litestream replicate`. Restaurar: `litestream restore -o cauce.db s3://cauce/litestream/cauce.db`. Point-in-time recovery sin cron.

## whisper.cpp

```bash
git clone https://github.com/ggml-org/whisper.cpp && cd whisper.cpp
make -j
./models/download-ggml-model.sh small
./main -m models/ggml-small.bin -l es -f nota.wav -otxt
```

El worker Go convierte m4a → wav 16 kHz con `ffmpeg` y llama a `main`. En un CPU viejo, `small` transcribe 1 minuto de audio en ~30–60 s; es asíncrono, no importa. Si el PC tiene 8 GB de RAM y paciencia, `medium` mejora bastante el español.

## Cloudflare Pages

Conectar el repo, directorio `web/`, build `npm run build`, salida `dist/`. Variable `VITE_API_BASE=https://api.<tu-dominio>`. Proteger con Access (arriba).

## Qué se reemplaza si algún día hay más usuarios

| Hoy | Mañana |
|-----|--------|
| PC viejo | Fly.io / Fargate (mismo binario) |
| SQLite + Litestream | Postgres (cambio en `core/store`) |
| Cloudflare Access | auth real en `auth.UserFromRequest` |
| whisper.cpp local | API de transcripción (mismo worker, otra función) |
