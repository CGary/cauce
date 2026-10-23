package conformance

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"cauce/server/internal/core/event"
	"cauce/server/internal/core/projection"
)

const fixture = `{
	"projection": "demo.echo",
	"params": {"now": "2026-10-22T13:00:00Z"},
	"events": [
		{
			"id": "e1",
			"type": "alarma_creada",
			"v": 2,
			"ts": "2026-10-20T10:00:00Z",
			"payload": {
				"alarma_id": "a1",
				"titulo": "Vitaminas",
				"tipo": "otro",
				"hora": "08:00",
				"repeticion": {"freq": "DAILY"}
			}
		}
	],
	"expected": [{"id": "e1", "user_id": "u1", "device_id": "test"}]
}`

func writeFixture(t *testing.T, body string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "caso.json")
	if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
	return path
}

func echoRegistry() *projection.Registry {
	reg := projection.NewRegistry()
	reg.MustRegister(projection.Func{
		ProjectionKey: "demo.echo",
		RunFunc: func(events []event.Envelope, params projection.Params) (any, error) {
			out := make([]map[string]string, 0, len(events))
			for _, ev := range events {
				out = append(out, map[string]string{"id": ev.ID, "user_id": ev.UserID, "device_id": ev.DeviceID})
			}
			return out, nil
		},
	})
	return reg
}

func TestRunFile(t *testing.T) {
	validator, err := event.NewValidator()
	if err != nil {
		t.Fatal(err)
	}
	path := writeFixture(t, fixture)
	if err := RunFile(path, echoRegistry(), validator); err != nil {
		t.Fatalf("RunFile: %v", err)
	}
}

func TestRunFileRejectsUnknownProjection(t *testing.T) {
	validator, err := event.NewValidator()
	if err != nil {
		t.Fatal(err)
	}
	unknown := strings.Replace(fixture, "demo.echo", "demo.missing", 1)
	err = RunFile(writeFixture(t, unknown), echoRegistry(), validator)
	if err == nil || !strings.Contains(err.Error(), "unknown projection") {
		t.Fatalf("error = %v, want unknown projection", err)
	}
}

func TestRunFileRejectsMismatch(t *testing.T) {
	validator, err := event.NewValidator()
	if err != nil {
		t.Fatal(err)
	}
	mismatch := strings.Replace(fixture, `"user_id": "u1"`, `"user_id": "u2"`, 1)
	err = RunFile(writeFixture(t, mismatch), echoRegistry(), validator)
	if err == nil || !strings.Contains(err.Error(), "result mismatch") {
		t.Fatalf("error = %v, want result mismatch", err)
	}
}

func TestValidateFileRejectsBadPayload(t *testing.T) {
	validator, err := event.NewValidator()
	if err != nil {
		t.Fatal(err)
	}
	bad := strings.Replace(fixture, `"hora": "08:00"`, `"hora": "8:00"`, 1)
	if err := ValidateFile(writeFixture(t, bad), validator); err == nil {
		t.Fatal("want validation error")
	}
}

func TestCompareJSONNumberTolerance(t *testing.T) {
	var a, b any
	if err := json.Unmarshal([]byte(`{"x":0.30000000000000004}`), &a); err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal([]byte(`{"x":0.3}`), &b); err != nil {
		t.Fatal(err)
	}
	// Plain unmarshal uses float64; compareJSON handles it through the default
	// branch, so normalize through json.Number to exercise tolerance.
	var na, nb any
	if err := decodeNumber([]byte(`{"x":0.30000000000000004}`), &na); err != nil {
		t.Fatal(err)
	}
	if err := decodeNumber([]byte(`{"x":0.3}`), &nb); err != nil {
		t.Fatal(err)
	}
	if err := CompareJSON(na, nb); err != nil {
		t.Fatalf("numbers within tolerance should match: %v", err)
	}
	if err := CompareJSON(a, b); err == nil {
		t.Fatal("plain float64 path is not the contract; expected mismatch")
	}
}

func TestCaseFiles(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "demo", "fixtures", "view")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "caso.json"), []byte(fixture), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(root, "_TEMPLATE"), 0o755); err != nil {
		t.Fatal(err)
	}

	files, err := CaseFiles(root)
	if err != nil {
		t.Fatal(err)
	}
	if len(files["demo"]) != 1 {
		t.Fatalf("demo files = %v", files["demo"])
	}
	if _, ok := files["_TEMPLATE"]; ok {
		t.Fatal("_TEMPLATE should be skipped")
	}
}
