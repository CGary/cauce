#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
project=cauce-e2e
data_dir=$(mktemp -d)
env_backup=$(mktemp)
backups_before=$(mktemp)
backup=''
had_env=0
if [[ -f server/.env ]]; then cp server/.env "$env_backup"; had_env=1; fi
find . -maxdepth 1 -name 'cauce-backup-*.tar.gz' -printf '%f\n' | sort > "$backups_before"
cleanup() {
  set +e
  COMPOSE_PROJECT_NAME="$project" CAUCE_DATA_DIR="$data_dir" CAUCE_HOST_PORT=18089 docker compose down -v >/dev/null 2>&1
  rm -rf "$data_dir" "$data_dir".prev-*
  [[ -z "$backup" ]] || rm -f "$backup"
  if [[ "$had_env" == 1 ]]; then cp "$env_backup" server/.env; else rm -f server/.env; fi
  rm -f "$env_backup" "$backups_before"
}
trap cleanup EXIT

cat > server/.env <<'EOF'
CAUCE_USER_ID=e2e-user
CAUCE_ADDR=:8080
EOF
chmod 600 server/.env

export COMPOSE_PROJECT_NAME="$project" CAUCE_DATA_DIR="$data_dir" CAUCE_HOST_PORT=18089
just up
curl -fsS http://127.0.0.1:18089/healthz >/dev/null

fixture=$(python3 - <<'PY'
import json
from pathlib import Path
path = next(Path('schema/features/alarmas/fixtures').glob('**/*.json'))
data = json.loads(path.read_text())
event = data['events'][0]
event.update(id='e2e-event', user_id='e2e-user', device_id='e2e', ts='2026-10-22T12:00:00Z')
print(json.dumps({'events': [event]}))
PY
)
curl -fsS -X POST http://127.0.0.1:18089/sync/push -H 'Content-Type: application/json' -d "$fixture" | python3 -c 'import json,sys; assert json.load(sys.stdin)["accepted"] == 1'
just backup
new_backup=$(comm -13 "$backups_before" <(find . -maxdepth 1 -name 'cauce-backup-*.tar.gz' -printf '%f\n' | sort) | sed -n '1p')
test -n "$new_backup"
backup="./$new_backup"
test -f "$backup"
just down
rm -rf "$data_dir" && mkdir -p "$data_dir"
just up
curl -fsS http://127.0.0.1:18089/sync/pull?since=0 | python3 -c 'import json,sys; assert len(json.load(sys.stdin)["events"]) == 0'
just restore "$backup"
curl -fsS http://127.0.0.1:18089/sync/pull?since=0 | python3 -c 'import json,sys; assert len(json.load(sys.stdin)["events"]) == 1'
echo 'portable e2e: PASS'
