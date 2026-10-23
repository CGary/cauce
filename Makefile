# Targets de android/server se completan en BOOTSTRAP.md fases 2-3.
.PHONY: lint test-server test-android test-web build-web test apk server-run

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
		echo "CAUCE_USER_ID is not set. Create server/.env from server/.env.example or export it." >&2; \
		exit 1; \
	fi; \
	cd server && go run ./cmd/cauce
