package conformance_test

import (
	"path/filepath"
	"testing"

	"cauce/server/internal/alarmas"
	"cauce/server/internal/calendario_google"
	"cauce/server/internal/conformance"
	"cauce/server/internal/core/event"
	"cauce/server/internal/core/projection"
)

// TestContractFixtures runs every repository fixture through its projection and
// compares the result with expected. It is strict, like the Kotlin runner: a
// fixture whose projection is not registered fails with "unknown projection"
// until /feature <id> registers it. There is no per-feature skip.
func TestContractFixtures(t *testing.T) {
	root := filepath.Join("..", "..", "..", "schema", "features")
	validator, err := event.NewValidator()
	if err != nil {
		t.Fatalf("NewValidator: %v", err)
	}
	cases, err := conformance.CaseFiles(root)
	if err != nil {
		t.Fatalf("CaseFiles(%s): %v", root, err)
	}
	if len(cases) == 0 {
		t.Fatalf("no fixtures found under %s", root)
	}

	// Feature packages register their projections here (/feature <id>).
	registry := projection.NewRegistry()
	registry.MustRegister(alarmas.Projections()...)
	registry.MustRegister(calendario_google.Projections()...)

	for feature, files := range cases {
		feature, files := feature, files
		t.Run(feature, func(t *testing.T) {
			for _, path := range files {
				if err := conformance.RunFile(path, registry, validator); err != nil {
					t.Errorf("%s: %v", path, err)
				}
			}
		})
	}
}
