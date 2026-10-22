# Infraestructura

Objetivo: costo mensual ≈ 0 más el dominio. Todo lo de abajo es reemplazable sin tocar el contrato.

| Pieza | Servicio | Costo |
|-------|----------|-------|
| Backend | PC viejo con Linux, un binario Go | luz |
| Exposición | Cloudflare Tunnel (`cloudflared`) | gratis |
| Acceso | Cloudflare Access (login Google + service token) | gratis hasta 50 usuarios |
| Blobs | Cloudflare R2 | gratis hasta 10 GB |
| Backup DB | Litestream → R2 | gratis |
| Dashboard | Cloudflare Pages | gratis |
| Dominio | Cloudflare Registrar o el que tengas | ~US$10/año |
| Transcripción | whisper.cpp en el PC | gratis |

## PC viejo

Linux mínimo (Debian/Ubuntu Server). Sin Docker: un binario estático y `systemd`.

```
/opt/cauce/
  cauce                 binario (go build -o cauce ./cmd/cauce, compilado en tu máquina con GOOS=linux GOARCH=amd64)
  .env                  variables (chmod 600)
  data/cauce.db         SQLite
  schema/               copia de schema/ del repo (o embebida en el binario, ver server/README.md)
  whisper/              whisper.cpp compilado + modelo ggml-small.bin (o medium si el CPU aguanta)
```

Unidad `systemd` (`/etc/systemd/system/cauce.service`):

```ini
[Unit]
Description=cauce server
After=network-online.target

[Service]
User=cauce
WorkingDirectory=/opt/cauce
EnvironmentFile=/opt/cauce/.env
ExecStart=/opt/cauce/cauce
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Deploy: `scp` del binario nuevo + `systemctl restart cauce`. Cuando canse, un script de 5 líneas.

## Cloudflare Tunnel

```bash
cloudflared tunnel login
cloudflared tunnel create cauce
cloudflared tunnel route dns cauce api.<tu-dominio>
```

`~/.cloudflared/config.yml`:

```yaml
tunnel: <id>
credentials-file: /home/cauce/.cloudflared/<id>.json
ingress:
  - hostname: api.<tu-dominio>
    service: http://localhost:8080
  - service: http_status:404
```

`cloudflared service install` lo deja como servicio. No se abre ningún puerto en el router; la IP de casa no importa.

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
  - path: /opt/cauce/data/cauce.db
    replicas:
      - type: s3
        bucket: cauce
        path: litestream/cauce.db
        endpoint: https://<account_id>.r2.cloudflarestorage.com
        access-key-id: ...
        secret-access-key: ...
```

`litestream replicate` como servicio. Restaurar: `litestream restore -o cauce.db s3://cauce/litestream/cauce.db`. Point-in-time recovery sin cron.

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
