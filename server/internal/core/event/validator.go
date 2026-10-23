package event

import (
	"embed"
	"encoding/json"
	"fmt"
	"io/fs"
	"path"
	"sort"
	"strings"

	"github.com/santhosh-tekuri/jsonschema/v6"
)

//go:embed schema
var schemaFS embed.FS

const schemaRoot = "schema"

// SchemaKey identifies one published event schema.
type SchemaKey struct {
	Type string
	V    int
}

// Validator holds the compiled envelope and per-event-type schemas. It is safe
// for concurrent use: jsonschema.Schema.Validate is read-only.
type Validator struct {
	envelope *jsonschema.Schema
	events   map[SchemaKey]*jsonschema.Schema
}

// NewValidator compiles the embedded contract. It fails loudly if an embedded
// schema is not valid draft 2020-12.
func NewValidator() (*Validator, error) {
	compiler := jsonschema.NewCompiler()
	compiler.AssertFormat()

	var envelope *jsonschema.Schema
	events := make(map[SchemaKey]*jsonschema.Schema)

	names, err := embeddedFiles()
	if err != nil {
		return nil, err
	}
	for _, name := range names {
		doc, err := readEmbeddedDoc(name)
		if err != nil {
			return nil, err
		}
		if err := compiler.AddResource(name, doc); err != nil {
			return nil, fmt.Errorf("add schema %s: %w", name, err)
		}
	}

	envelope, err = compiler.Compile(schemaRoot + "/envelope.v1.json")
	if err != nil {
		return nil, fmt.Errorf("compile envelope schema: %w", err)
	}

	for _, name := range names {
		if !strings.HasPrefix(name, schemaRoot+"/events/") {
			continue
		}
		key, err := parseEventSchemaName(path.Base(name))
		if err != nil {
			return nil, err
		}
		sch, err := compiler.Compile(name)
		if err != nil {
			return nil, fmt.Errorf("compile %s: %w", name, err)
		}
		events[key] = sch
	}

	return &Validator{envelope: envelope, events: events}, nil
}

// ValidateEnvelope validates the frame against envelope.v1.json and the payload
// against events/<type>.v<v>.json. Unknown envelope fields are allowed by the
// schema (additive versioning); unknown event types are rejected.
func (v *Validator) ValidateEnvelope(raw []byte) (*Envelope, error) {
	doc, err := jsonschema.UnmarshalJSON(strings.NewReader(string(raw)))
	if err != nil {
		return nil, fmt.Errorf("invalid JSON: %w", err)
	}
	if err := v.envelope.Validate(doc); err != nil {
		return nil, fmt.Errorf("invalid envelope: %w", err)
	}

	var ev Envelope
	if err := json.Unmarshal(raw, &ev); err != nil {
		return nil, fmt.Errorf("invalid envelope: %w", err)
	}
	if err := v.ValidatePayload(ev); err != nil {
		return nil, err
	}
	return &ev, nil
}

// ValidatePayload checks ev.Payload against the schema for (ev.Type, ev.V).
func (v *Validator) ValidatePayload(ev Envelope) error {
	sch, ok := v.events[SchemaKey{Type: ev.Type, V: ev.V}]
	if !ok {
		return fmt.Errorf("no schema for event type %q v%d", ev.Type, ev.V)
	}
	doc, err := jsonschema.UnmarshalJSON(strings.NewReader(string(ev.Payload)))
	if err != nil {
		return fmt.Errorf("event %q (%s v%d): payload is not valid JSON: %w", ev.ID, ev.Type, ev.V, err)
	}
	if err := sch.Validate(doc); err != nil {
		return fmt.Errorf("event %q (%s v%d): invalid payload: %w", ev.ID, ev.Type, ev.V, err)
	}
	return nil
}

// Keys lists the event schema versions the server knows, sorted for tests and
// diagnostics.
func (v *Validator) Keys() []SchemaKey {
	keys := make([]SchemaKey, 0, len(v.events))
	for k := range v.events {
		keys = append(keys, k)
	}
	sort.Slice(keys, func(i, j int) bool {
		if keys[i].Type != keys[j].Type {
			return keys[i].Type < keys[j].Type
		}
		return keys[i].V < keys[j].V
	})
	return keys
}

func embeddedFiles() ([]string, error) {
	var names []string
	err := fs.WalkDir(schemaFS, schemaRoot, func(name string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if !d.IsDir() {
			names = append(names, name)
		}
		return nil
	})
	return names, err
}

func readEmbeddedDoc(name string) (any, error) {
	f, err := schemaFS.Open(name)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	doc, err := jsonschema.UnmarshalJSON(f)
	if err != nil {
		return nil, fmt.Errorf("parse %s: %w", name, err)
	}
	return doc, nil
}

func parseEventSchemaName(base string) (SchemaKey, error) {
	name := strings.TrimSuffix(base, ".json")
	i := strings.LastIndex(name, ".v")
	if i < 0 {
		return SchemaKey{}, fmt.Errorf("bad event schema filename %q (expected <tipo>.v<N>.json)", base)
	}
	var v int
	if _, err := fmt.Sscanf(name[i+2:], "%d", &v); err != nil || v < 1 {
		return SchemaKey{}, fmt.Errorf("bad event schema version in %q", base)
	}
	return SchemaKey{Type: name[:i], V: v}, nil
}
