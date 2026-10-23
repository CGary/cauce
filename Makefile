.PHONY: lint test-server test-android test-web build-web test apk server-run up down logs tunnel backup restore

lint:
	python3 scripts/lint_contract.py

test-server:
	cd server && go test ./...

test-android:
	cd android && ./gradlew --quiet :core:events:test :core:sync:test :feature:alarmas:test

test-web:
	cd web && npx tsc --noEmit && npx eslint . && npx vitest run

build-web:
	cd web && npm run build

test: lint test-server test-android test-web

apk:
	cd android && ./gradlew :app:assembleDebug && find . -name '*.apk' -path '*debug*'

server-run:
	@if [ -f server/.env ]; then set -a; . ./server/.env; set +a; fi; \
	if [ -z "$$CAUCE_USER_ID" ]; then \
		echo "CAUCE_USER_ID is not set. Create server/.env from server/.env.example or export it." >&2; exit 1; \
	fi; cd server && go run ./cmd/cauce

up:
	@if [ ! -f server/.env ]; then cp server/.env.example server/.env; chmod 600 server/.env; echo "Created server/.env. CAUCE_USER_ID defaults to u1, which is fine for this single-user install; edit it only if you used another id."; fi; \
	set -a; . ./server/.env; set +a; \
	if [ -z "$$CAUCE_USER_ID" ]; then echo "Set CAUCE_USER_ID in server/.env, then run make up again." >&2; exit 1; fi; \
	data_dir="$${CAUCE_DATA_DIR:-./deploy/data}"; mkdir -p "$$data_dir"; \
	CAUCE_UID="$$(id -u)" CAUCE_GID="$$(id -g)" docker compose up -d --build cauce; \
	port="$${CAUCE_HOST_PORT:-8080}"; deadline="$$(($$(date +%s) + 60))"; \
	until curl -fsS "http://127.0.0.1:$$port/healthz" >/dev/null 2>&1; do \
		if [ "$$(date +%s)" -ge "$$deadline" ]; then docker compose logs cauce; exit 1; fi; sleep 1; \
	done; echo "cauce is ready on http://127.0.0.1:$$port"

down:
	docker compose --profile tunnel down

logs:
	docker compose logs -f cauce

tunnel:
	@test -f server/.env || { echo "Create server/.env first (copy server/.env.example) and set TUNNEL_TOKEN." >&2; exit 1; }; \
	set -a; . ./server/.env; set +a; \
	if [ -z "$$TUNNEL_TOKEN" ]; then echo "Set TUNNEL_TOKEN in server/.env, then run make tunnel again." >&2; exit 1; fi; \
	CAUCE_UID="$$(id -u)" CAUCE_GID="$$(id -g)" docker compose --profile tunnel up -d --no-deps cloudflared

backup:
	@set -e; data_dir="$${CAUCE_DATA_DIR:-./deploy/data}"; mkdir -p "$$data_dir"; \
	tmp="$$(mktemp -d)"; trap 'rm -rf "$$tmp"; rm -f "$$data_dir/.cauce-backup.db"' EXIT; \
	if CAUCE_UID="$$(id -u)" CAUCE_GID="$$(id -g)" docker compose ps --status running -q cauce | grep -q .; then \
		CAUCE_UID="$$(id -u)" CAUCE_GID="$$(id -g)" docker compose exec -T cauce /cauce snapshot -o /data/.cauce-backup.db; \
	else \
		CAUCE_UID="$$(id -u)" CAUCE_GID="$$(id -g)" docker compose run --rm --no-deps cauce snapshot -o /data/.cauce-backup.db; \
	fi; \
	backup="cauce-backup-$$(date -u +%Y%m%d-%H%M%S).tar.gz"; \
	cp "$$data_dir/.cauce-backup.db" "$$tmp/cauce.db"; rm -f "$$data_dir/.cauce-backup.db"; \
	cp server/.env "$$tmp/server.env"; mkdir "$$tmp/server"; mv "$$tmp/server.env" "$$tmp/server/.env"; \
	( umask 077; tar -czf "$$backup" -C "$$tmp" cauce.db server/.env ); \
	chmod 600 "$$backup"; \
	echo "Backup: $$backup"; echo "scp $$backup usuario@PC-NUEVO:/ruta/cauce/"

restore:
	@set -e; FILE="$(FILE)"; test -n "$$FILE" || { echo "Usage: make restore FILE=cauce-backup-...tar.gz" >&2; exit 1; }; \
	tmp="$$(mktemp -d)"; trap 'rm -rf "$$tmp"' EXIT; tar -xzf "$$FILE" -C "$$tmp"; \
	test -f "$$tmp/cauce.db" && test -f "$$tmp/server/.env"; \
	if [ -f server/.env ] && ! cmp -s server/.env "$$tmp/server/.env"; then \
		if [ "$${FORCE:-0}" != 1 ]; then echo "ERROR: local server/.env differs from the one in the backup. Re-run with FORCE=1 to keep the local .env and continue." >&2; exit 1; fi; \
		echo "WARNING: keeping local server/.env; backup environment differs." >&2; \
	fi; \
	data_dir="$${CAUCE_DATA_DIR:-./deploy/data}"; \
	CAUCE_UID="$$(id -u)" CAUCE_GID="$$(id -g)" docker compose run --rm --no-deps -v "$$tmp:/restore:ro" cauce check -db /restore/cauce.db; \
	CAUCE_UID="$$(id -u)" CAUCE_GID="$$(id -g)" docker compose stop cauce 2>/dev/null || true; \
	if [ -d "$$data_dir" ]; then mv "$$data_dir" "$$data_dir.prev-$$(date -u +%Y%m%d-%H%M%S)"; fi; mkdir -p "$$data_dir"; \
	cp "$$tmp/cauce.db" "$$data_dir/cauce.db"; chmod 600 "$$data_dir/cauce.db"; \
	if [ ! -f server/.env ]; then cp "$$tmp/server/.env" server/.env; chmod 600 server/.env; fi; \
	$(MAKE) up
