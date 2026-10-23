package conformance_test

import (
	"path/filepath"
	"testing"

	"cauce/server/internal/alarmas"
	"cauce/server/internal/conformance"
	"cauce/server/internal/core/event"
	"cauce/server/internal/core/projection"
)

// TestContractFixtures validates every repository fixture against the embedded
// schemas and runs the projections that are registered. Feature projections are
// added by /feature <id>; until then their fixtures still guard the contract,
// and the skip is reported per feature instead of silently passing.
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

	for feature, files := range cases {
		feature, files := feature, files
		t.Run(feature, func(t *testing.T) {
			if !registry.HasFeature(feature) {
				for _, path := range files {
					if err := conformance.ValidateFile(path, validator); err != nil {
						t.Errorf("%s: %v", path, err)
					}
				}
				t.Logf("%d fixture(s) match the contract; projections pending /feature %s", len(files), feature)
				return
			}
			for _, path := range files {
				if err := conformance.RunFile(path, registry, validator); err != nil {
					t.Errorf("%s: %v", path, err)
				}
			}
		})
	}
}
