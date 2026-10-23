// Package conformance is the Go side of the fixture contract: it walks
// schema/features/*/fixtures, runs the named projection from the registry and
// compares the result structurally with `expected`.
package conformance

import (
	"bytes"
	"encoding/json"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"

	"cauce/server/internal/core/event"
	"cauce/server/internal/core/projection"
)

// Fixture is one conformance case.
type Fixture struct {
	Projection string                     `json:"projection"`
	Params     map[string]json.RawMessage `json:"params"`
	Events     []json.RawMessage          `json:"events"`
	Expected   json.RawMessage            `json:"expected"`
}

// Load reads and shape-checks a fixture file.
func Load(path string) (*Fixture, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, fmt.Errorf("invalid JSON: %w", err)
	}
	for _, key := range []string{"projection", "params", "events", "expected"} {
		if _, ok := raw[key]; !ok {
			return nil, fmt.Errorf("missing key %q", key)
		}
	}
	fx := &Fixture{}
	if err := json.Unmarshal(raw["projection"], &fx.Projection); err != nil {
		return nil, fmt.Errorf("projection: %w", err)
	}
	if err := json.Unmarshal(raw["params"], &fx.Params); err != nil {
		return nil, fmt.Errorf("params: %w", err)
	}
	if err := json.Unmarshal(raw["events"], &fx.Events); err != nil {
		return nil, fmt.Errorf("events: %w", err)
	}
	fx.Expected = raw["expected"]
	if fx.Projection == "" {
		return nil, fmt.Errorf("projection is empty")
	}
	return fx, nil
}

// DecodeEvents applies the fixture defaults (user_id "u1", device_id "test")
// and validates every event against the embedded contract.
func (fx *Fixture) DecodeEvents(v *event.Validator) ([]event.Envelope, error) {
	events := make([]event.Envelope, 0, len(fx.Events))
	for i, raw := range fx.Events {
		var fields map[string]json.RawMessage
		if err := json.Unmarshal(raw, &fields); err != nil {
			return nil, fmt.Errorf("events[%d]: %w", i, err)
		}
		if _, ok := fields["user_id"]; !ok {
			fields["user_id"] = json.RawMessage(`"u1"`)
		}
		if _, ok := fields["device_id"]; !ok {
			fields["device_id"] = json.RawMessage(`"test"`)
		}
		frame, err := json.Marshal(fields)
		if err != nil {
			return nil, err
		}
		ev, err := decodeFrame(frame, v)
		if err != nil {
			return nil, fmt.Errorf("events[%d]: %w", i, err)
		}
		events = append(events, *ev)
	}
	return events, nil
}

func decodeFrame(frame []byte, v *event.Validator) (*event.Envelope, error) {
	if v != nil {
		return v.ValidateEnvelope(frame)
	}
	var ev event.Envelope
	if err := json.Unmarshal(frame, &ev); err != nil {
		return nil, err
	}
	return &ev, nil
}

// ValidateFile checks a fixture's events against the contract without running a
// projection. It is useful for features whose server projections do not exist
// yet.
func ValidateFile(path string, v *event.Validator) error {
	fx, err := Load(path)
	if err != nil {
		return err
	}
	_, err = fx.DecodeEvents(v)
	return err
}

// RunFile loads, runs and compares one fixture.
func RunFile(path string, reg *projection.Registry, v *event.Validator) error {
	fx, err := Load(path)
	if err != nil {
		return err
	}
	proj, ok := reg.Get(fx.Projection)
	if !ok {
		return fmt.Errorf("unknown projection %q (registered: %s)",
			fx.Projection, strings.Join(reg.Keys(), ", "))
	}
	events, err := fx.DecodeEvents(v)
	if err != nil {
		return err
	}
	params := fx.Params
	if params == nil {
		params = projection.Params{}
	}
	actual, err := proj.Run(events, params)
	if err != nil {
		return fmt.Errorf("projection %s: %w", fx.Projection, err)
	}
	encoded, err := json.Marshal(actual)
	if err != nil {
		return fmt.Errorf("encode projection result: %w", err)
	}
	var want any
	if err := decodeNumber(fx.Expected, &want); err != nil {
		return fmt.Errorf("expected: %w", err)
	}
	var got any
	if err := decodeNumber(encoded, &got); err != nil {
		return fmt.Errorf("actual: %w", err)
	}
	if err := CompareJSON(want, got); err != nil {
		return fmt.Errorf("result mismatch: %w", err)
	}
	return nil
}

// CaseFiles groups every fixture under featuresRoot by feature id, sorted.
func CaseFiles(featuresRoot string) (map[string][]string, error) {
	entries, err := os.ReadDir(featuresRoot)
	if err != nil {
		return nil, err
	}
	out := make(map[string][]string)
	for _, entry := range entries {
		if !entry.IsDir() || strings.HasPrefix(entry.Name(), "_") {
			continue
		}
		pattern := filepath.Join(featuresRoot, entry.Name(), "fixtures", "*", "*.json")
		files, err := filepath.Glob(pattern)
		if err != nil {
			return nil, err
		}
		if len(files) > 0 {
			sort.Strings(files)
			out[entry.Name()] = files
		}
	}
	return out, nil
}

// CompareJSON compares two JSON values decoded with json.Number. Object key
// order is irrelevant, array order matters, numbers compare with 1e-9
// tolerance.
func CompareJSON(want, got any) error {
	return compare("$", want, got)
}

func compare(path string, want, got any) error {
	switch w := want.(type) {
	case map[string]any:
		g, ok := got.(map[string]any)
		if !ok {
			return fmt.Errorf("%s: want object, got %T", path, got)
		}
		for key, wv := range w {
			gv, exists := g[key]
			if !exists {
				return fmt.Errorf("%s.%s: missing", path, key)
			}
			if err := compare(path+"."+key, wv, gv); err != nil {
				return err
			}
		}
		for key := range g {
			if _, exists := w[key]; !exists {
				return fmt.Errorf("%s.%s: unexpected", path, key)
			}
		}
		return nil
	case []any:
		g, ok := got.([]any)
		if !ok {
			return fmt.Errorf("%s: want array, got %T", path, got)
		}
		if len(w) != len(g) {
			return fmt.Errorf("%s: want %d items, got %d", path, len(w), len(g))
		}
		for i := range w {
			if err := compare(fmt.Sprintf("%s[%d]", path, i), w[i], g[i]); err != nil {
				return err
			}
		}
		return nil
	case json.Number:
		g, ok := got.(json.Number)
		if !ok {
			return fmt.Errorf("%s: want number %s, got %T", path, w, got)
		}
		wf, err := w.Float64()
		if err != nil {
			return fmt.Errorf("%s: %w", path, err)
		}
		gf, err := g.Float64()
		if err != nil {
			return fmt.Errorf("%s: %w", path, err)
		}
		if math.Abs(wf-gf) > 1e-9 {
			return fmt.Errorf("%s: want %v, got %v", path, wf, gf)
		}
		return nil
	default:
		if !reflect.DeepEqual(want, got) {
			return fmt.Errorf("%s: want %v, got %v", path, want, got)
		}
		return nil
	}
}

func decodeNumber(data []byte, dst any) error {
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()
	return dec.Decode(dst)
}
