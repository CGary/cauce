# Tareas del repo. `just` sin argumentos lista las recetas.
# Las recetas de Docker respetan CAUCE_DATA_DIR, CAUCE_HOST_PORT y COMPOSE_PROJECT_NAME.

default:
    @just --list

# Valida el contrato: schemas, fixtures y SPECs
lint:
    python3 scripts/lint_contract.py

# Copia schema/ al espejo que el servidor embebe (correr tras editar schema/events/)
sync-schemas:
    cd server && go generate ./internal/core/event/

test-server:
    cd server && go test ./...

test-android:
    cd android && ./gradlew --quiet :core:events:test :core:sync:test :feature:alarmas:test

test-web:
    cd web && npx tsc --noEmit && npx eslint . && npx vitest run

build-web:
    cd web && npm run build

# Contrato + servidor + Android + web
test: lint test-server test-android test-web

# Compila el APK debug y muestra su ruta
apk:
    cd android && ./gradlew :app:assembleDebug && find . -name '*.apk' -path '*debug*'

# Corre el servidor con go run, sin Docker (lee server/.env)
server-run:
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -f server/.env ]; then set -a; . ./server/.env; set +a; fi
    if [ -z "${CAUCE_USER_ID:-}" ]; then
        echo "CAUCE_USER_ID is not set. Create server/.env from server/.env.example or export it." >&2
        exit 1
    fi
    cd server && exec go run ./cmd/cauce

# Levanta el servidor en Docker y espera a /healthz (crea server/.env si falta)
up:
    #!/usr/bin/env bash
    set -euo pipefail
    if [ ! -f server/.env ]; then
        cp server/.env.example server/.env
        chmod 600 server/.env
        echo "Created server/.env. CAUCE_USER_ID defaults to u1, which is fine for this single-user install; edit it only if you used another id."
    fi
    set -a; . ./server/.env; set +a
    if [ -z "${CAUCE_USER_ID:-}" ]; then echo "Set CAUCE_USER_ID in server/.env, then run just up again." >&2; exit 1; fi
    export CAUCE_UID="$(id -u)" CAUCE_GID="$(id -g)"
    mkdir -p "${CAUCE_DATA_DIR:-./deploy/data}"
    docker compose up -d --build cauce
    port="${CAUCE_HOST_PORT:-8080}"
    deadline=$(( $(date +%s) + 60 ))
    until curl -fsS "http://127.0.0.1:$port/healthz" >/dev/null 2>&1; do
        if [ "$(date +%s)" -ge "$deadline" ]; then docker compose logs cauce; exit 1; fi
        sleep 1
    done
    echo "cauce is ready on http://127.0.0.1:$port"

# Detiene el servidor y el túnel
down:
    docker compose --profile tunnel down

logs:
    docker compose logs -f cauce

# Arranca Cloudflare Tunnel (necesita TUNNEL_TOKEN en server/.env)
tunnel:
    #!/usr/bin/env bash
    set -euo pipefail
    test -f server/.env || { echo "Create server/.env first (copy server/.env.example) and set TUNNEL_TOKEN." >&2; exit 1; }
    set -a; . ./server/.env; set +a
    if [ -z "${TUNNEL_TOKEN:-}" ]; then echo "Set TUNNEL_TOKEN in server/.env, then run just tunnel again." >&2; exit 1; fi
    CAUCE_UID="$(id -u)" CAUCE_GID="$(id -g)" docker compose --profile tunnel up -d --no-deps cloudflared

# Genera cauce-backup-<fecha>.tar.gz con la base y server/.env
backup:
    #!/usr/bin/env bash
    set -euo pipefail
    export CAUCE_UID="$(id -u)" CAUCE_GID="$(id -g)"
    data_dir="${CAUCE_DATA_DIR:-./deploy/data}"
    mkdir -p "$data_dir"
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"; rm -f "$data_dir/.cauce-backup.db"' EXIT
    if docker compose ps --status running -q cauce | grep -q .; then
        docker compose exec -T cauce /cauce snapshot -o /data/.cauce-backup.db
    else
        docker compose run --rm --no-deps cauce snapshot -o /data/.cauce-backup.db
    fi
    backup="cauce-backup-$(date -u +%Y%m%d-%H%M%S).tar.gz"
    mkdir "$tmp/server"
    cp "$data_dir/.cauce-backup.db" "$tmp/cauce.db"
    cp server/.env "$tmp/server/.env"
    ( umask 077; tar -czf "$backup" -C "$tmp" cauce.db server/.env )
    chmod 600 "$backup"
    echo "Backup: $backup"
    echo "scp $backup usuario@PC-NUEVO:/ruta/cauce/"

# Restaura un backup. FORCE=1 conserva el server/.env local si difiere del backup.
restore file:
    #!/usr/bin/env bash
    set -euo pipefail
    file={{ quote(file) }}
    export CAUCE_UID="$(id -u)" CAUCE_GID="$(id -g)"
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    tar -xzf "$file" -C "$tmp"
    test -f "$tmp/cauce.db" && test -f "$tmp/server/.env"
    if [ -f server/.env ] && ! cmp -s server/.env "$tmp/server/.env"; then
        if [ "${FORCE:-0}" != 1 ]; then
            echo "ERROR: local server/.env differs from the one in the backup. Re-run with FORCE=1 to keep the local .env and continue." >&2
            exit 1
        fi
        echo "WARNING: keeping local server/.env; backup environment differs." >&2
    fi
    data_dir="${CAUCE_DATA_DIR:-./deploy/data}"
    docker compose run --rm --no-deps -v "$tmp:/restore:ro" cauce check -db /restore/cauce.db
    docker compose stop cauce 2>/dev/null || true
    if [ -d "$data_dir" ]; then mv "$data_dir" "$data_dir.prev-$(date -u +%Y%m%d-%H%M%S)"; fi
    mkdir -p "$data_dir"
    cp "$tmp/cauce.db" "$data_dir/cauce.db"
    chmod 600 "$data_dir/cauce.db"
    if [ ! -f server/.env ]; then cp "$tmp/server/.env" server/.env; chmod 600 server/.env; fi
    {{ just_executable() }} up
