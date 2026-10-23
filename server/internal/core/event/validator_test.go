package event

import (
	"strings"
	"testing"
)

const validAlarma = `{
	"id": "e1",
	"user_id": "u1",
	"device_id": "test",
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
}`

func TestValidateEnvelope(t *testing.T) {
	v, err := NewValidator()
	if err != nil {
		t.Fatalf("NewValidator: %v", err)
	}

	tests := []struct {
		name    string
		raw     string
		wantSub string
	}{
		{name: "valid", raw: validAlarma},
		{name: "malformed JSON", raw: `{`, wantSub: "invalid JSON"},
		{name: "missing envelope field", raw: `{"id":"e1"}`, wantSub: "invalid envelope"},
		{name: "unknown type", raw: strings.Replace(validAlarma, `"alarma_creada"`, `"no_existe"`, 1), wantSub: "no schema for event type"},
		{name: "unknown version", raw: strings.Replace(validAlarma, `"v": 2`, `"v": 9`, 1), wantSub: "no schema for event type"},
		{name: "invalid payload", raw: strings.Replace(validAlarma, `"hora": "08:00"`, `"hora": "8:00"`, 1), wantSub: "invalid payload"},
		{name: "unknown payload field allowed", raw: strings.Replace(validAlarma, `"titulo": "Vitaminas",`, `"titulo": "Vitaminas", "extra": true,`, 1)},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			ev, err := v.ValidateEnvelope([]byte(tt.raw))
			if tt.wantSub != "" {
				if err == nil {
					t.Fatalf("want error containing %q, got nil", tt.wantSub)
				}
				if !strings.Contains(err.Error(), tt.wantSub) {
					t.Fatalf("error %q does not contain %q", err, tt.wantSub)
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if ev.Type != "alarma_creada" {
				t.Fatalf("type = %q", ev.Type)
			}
		})
	}
}

func TestSchemaKeys(t *testing.T) {
	v, err := NewValidator()
	if err != nil {
		t.Fatalf("NewValidator: %v", err)
	}
	have := make(map[SchemaKey]bool)
	for _, key := range v.Keys() {
		have[key] = true
	}
	for _, want := range []SchemaKey{
		{Type: "alarma_creada", V: 1},
		{Type: "alarma_creada", V: 2},
		{Type: "dosis_dada", V: 1},
	} {
		if !have[want] {
			t.Errorf("missing schema %+v", want)
		}
	}
}
