# Targets de android/server se completan en BOOTSTRAP.md fases 2-3.
.PHONY: lint test-server test-android test apk server-run

lint:
	python3 scripts/lint_contract.py

test-server:
	cd server && go test ./...

test-android:
	cd android && ./gradlew --quiet :core:events:test :feature:alarmas:test

test: lint test-server test-android

apk:
	cd android && ./gradlew :app:assembleDebug && find . -name '*.apk' -path '*debug*'

server-run:
	cd server && go run ./cmd/cauce
