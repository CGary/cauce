// Package projection defines the pure view contract: (events, params) ->
// result. Projections never read the clock, the disk or randomness; the caller
// supplies every input, which is what makes fixtures deterministic in Go and
// Kotlin.
package projection

import (
	"encoding/json"
	"fmt"
	"net/url"
	"sort"
	"time"

	"cauce/server/internal/core/event"
)

// Params holds projection parameters as raw JSON so each feature can decode
// only the keys it understands. In HTTP requests every query value is passed as
// a JSON string; `now` is injected by the endpoint when the client omits it.
type Params map[string]json.RawMessage

// ParamsFromValues converts URL query values to Params. Only the first value of
// a repeated key is used.
func ParamsFromValues(values url.Values) Params {
	params := make(Params, len(values))
	for key, list := range values {
		if len(list) == 0 {
			continue
		}
		raw, err := json.Marshal(list[0])
		if err != nil {
			continue
		}
		params[key] = raw
	}
	return params
}

// Decode unmarshals key into dst. The bool reports whether the key was present.
func (p Params) Decode(key string, dst any) (bool, error) {
	raw, ok := p[key]
	if !ok {
		return false, nil
	}
	if err := json.Unmarshal(raw, dst); err != nil {
		return true, fmt.Errorf("param %q: %w", key, err)
	}
	return true, nil
}

// String returns a string parameter and whether it was present.
func (p Params) String(key string) (string, bool, error) {
	var value string
	ok, err := p.Decode(key, &value)
	return value, ok, err
}

// Now returns params.now as RFC 3339 UTC, or fallback when the client omitted
// it. The fallback is injected by the caller (the HTTP endpoint uses the
// server clock) so the projection function itself stays pure.
func (p Params) Now(fallback time.Time) (time.Time, error) {
	raw, ok := p["now"]
	if !ok {
		if fallback.IsZero() {
			return time.Time{}, fmt.Errorf("missing required param %q", "now")
		}
		return fallback.UTC(), nil
	}
	var text string
	if err := json.Unmarshal(raw, &text); err != nil {
		return time.Time{}, fmt.Errorf("param %q must be an RFC 3339 string: %w", "now", err)
	}
	now, err := time.Parse(time.RFC3339, text)
	if err != nil {
		return time.Time{}, fmt.Errorf("param %q: %w", "now", err)
	}
	return now.UTC(), nil
}

// Projection is one read model identified by "<feature>.<name>".
type Projection interface {
	Key() string
	Run(events []event.Envelope, params Params) (any, error)
}

// Func adapts a plain function to Projection. Tests and the conformance runner
// use it; real features get a named type.
type Func struct {
	ProjectionKey string
	RunFunc       func(events []event.Envelope, params Params) (any, error)
}

func (f Func) Key() string { return f.ProjectionKey }

func (f Func) Run(events []event.Envelope, params Params) (any, error) {
	return f.RunFunc(events, params)
}

// Registry maps "<feature>.<name>" to its projection. It is populated during
// wiring, before the server starts serving, and is read-only afterwards.
type Registry struct {
	byKey map[string]Projection
}

func NewRegistry() *Registry {
	return &Registry{byKey: make(map[string]Projection)}
}

// Register adds p and rejects empty, duplicated or malformed keys so a typo
// fails at startup instead of at request time.
func (r *Registry) Register(p Projection) error {
	if p == nil {
		return fmt.Errorf("register nil projection")
	}
	key := p.Key()
	if !validKey(key) {
		return fmt.Errorf("invalid projection key %q (want <feature>.<name>)", key)
	}
	if _, exists := r.byKey[key]; exists {
		return fmt.Errorf("duplicate projection %q", key)
	}
	r.byKey[key] = p
	return nil
}

// MustRegister panics on error. Wiring uses it so a bad registry never serves.
func (r *Registry) MustRegister(projections ...Projection) {
	for _, p := range projections {
		if err := r.Register(p); err != nil {
			panic(err)
		}
	}
}

// Get looks up a projection by key.
func (r *Registry) Get(key string) (Projection, bool) {
	p, ok := r.byKey[key]
	return p, ok
}

// Keys returns the registered keys sorted.
func (r *Registry) Keys() []string {
	keys := make([]string, 0, len(r.byKey))
	for key := range r.byKey {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
}

// HasFeature reports whether any projection for the feature is registered.
func (r *Registry) HasFeature(feature string) bool {
	prefix := feature + "."
	for key := range r.byKey {
		if len(key) > len(prefix) && key[:len(prefix)] == prefix {
			return true
		}
	}
	return false
}

func validKey(key string) bool {
	feature, name, ok := splitKey(key)
	return ok && validIdent(feature) && validIdent(name)
}

func splitKey(key string) (string, string, bool) {
	for i := 0; i < len(key); i++ {
		if key[i] == '.' {
			if i == 0 || i == len(key)-1 {
				return "", "", false
			}
			return key[:i], key[i+1:], true
		}
	}
	return "", "", false
}

func validIdent(s string) bool {
	if s == "" || s[0] < 'a' || s[0] > 'z' {
		return false
	}
	for i := 1; i < len(s); i++ {
		c := s[i]
		if (c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '_' {
			return false
		}
	}
	return true
}
