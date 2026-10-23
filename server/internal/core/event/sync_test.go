package event

import (
	"os"
	"path/filepath"
	"testing"
)

// TestEmbeddedSchemaInSync guards the go:generate copy: the binary embeds the
// contract, so it must match the repository's schema/ byte for byte. The test
// is skipped when the module is built without the repository tree (module zip).
func TestEmbeddedSchemaInSync(t *testing.T) {
	root := filepath.Join("..", "..", "..", "..", "schema")
	if _, err := os.Stat(filepath.Join(root, "envelope.v1.json")); err != nil {
		t.Skipf("repository schema tree not available: %v", err)
	}

	embedded := map[string]string{
		"envelope.v1.json": filepath.Join(root, "envelope.v1.json"),
	}
	entries, err := os.ReadDir(filepath.Join(root, "events"))
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range entries {
		if entry.IsDir() || filepath.Ext(entry.Name()) != ".json" {
			continue
		}
		embedded[filepath.Join("events", entry.Name())] = filepath.Join(root, "events", entry.Name())
	}

	found, err := embeddedFiles()
	if err != nil {
		t.Fatal(err)
	}
	if len(found) != len(embedded) {
		t.Fatalf("embedded files = %d, repository files = %d (%v)", len(found), len(embedded), found)
	}
	for rel, src := range embedded {
		want, err := os.ReadFile(src)
		if err != nil {
			t.Fatal(err)
		}
		got, err := schemaFS.ReadFile(pathInSchema(rel))
		if err != nil {
			t.Fatal(err)
		}
		if string(want) != string(got) {
			t.Errorf("%s is stale; run `go generate ./internal/core/event`", rel)
		}
	}
}

func pathInSchema(rel string) string {
	return filepath.ToSlash(filepath.Join("schema", rel))
}
