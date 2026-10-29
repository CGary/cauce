package alarmas

import (
	"encoding/json"
	"testing"

	"cauce/server/internal/core/event"
	"cauce/server/internal/core/projection"
)

func envelope(id, typ string, v int, ts, payload string) event.Envelope {
	return event.Envelope{
		ID:      id,
		Type:    typ,
		V:       v,
		TS:      ts,
		Payload: json.RawMessage(payload),
	}
}

// runActivas runs P1 with fixed params and returns the marshaled result, so
// the tests also pin the exact keys of every entry (no `restantes`, no
// `fecha`).
func runActivas(t *testing.T, events []event.Envelope) string {
	t.Helper()
	params := projection.Params{
		"now": json.RawMessage(`"2026-11-02T12:00:00Z"`),
		"tz":  json.RawMessage(`"America/La_Paz"`),
	}
	got, err := AlarmasActivas{}.Run(events, params)
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	encoded, err := json.Marshal(got)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	return string(encoded)
}

// TestReunionesGoogle covers the meeting entries of P1 (SPEC section 4,
// rule 2.10 and calendario_google section 4): the aviso rule and the
// exclusions.
func TestReunionesGoogle(t *testing.T) {
	const (
		todoElDia = `{"reunion_id":"r1","serie_id":"s1","cuenta":"ana@example.com","titulo":"Junta","inicio":"2026-11-05T00:00:00Z","fin":"2026-11-06T00:00:00Z","todo_el_dia":true,"recordatorio_min":30,"estado":"confirmada","respuesta":"aceptada"}`
		queda     = `{"reunion_id":"r_ok","serie_id":"s_ok","cuenta":"ana@example.com","titulo":"Queda","inicio":"2026-11-03T14:00:00Z","fin":"2026-11-03T15:00:00Z","todo_el_dia":false,"recordatorio_min":30,"estado":"confirmada","respuesta":"aceptada"}`
		wantQueda = `[{"alarma_id":"gcal:r_ok","titulo":"Queda","tipo":"reunion","hora":"09:30","repeticion":null,"proximo_disparo":"2026-11-03T13:30:00Z"}]`
	)
	tests := []struct {
		name   string
		events []event.Envelope
		want   string
	}{
		{
			name: "todo_el_dia meeting defaults to 08:00 local, reminder ignored",
			events: []event.Envelope{
				envelope("e1", "reunion_sincronizada", 1, "2026-11-01T20:00:00Z", todoElDia),
			},
			want: `[{"alarma_id":"gcal:r1","titulo":"Junta","tipo":"reunion","hora":"08:00","repeticion":null,"proximo_disparo":"2026-11-05T12:00:00Z"}]`,
		},
		{
			name: "edited aviso with hora wins over the all-day default",
			events: []event.Envelope{
				envelope("e1", "reunion_sincronizada", 1, "2026-11-01T20:00:00Z", todoElDia),
				envelope("e2", "aviso_reunion_editado", 1, "2026-11-01T21:00:00Z", `{"serie_id":"s1","hora":"20:00"}`),
			},
			want: `[{"alarma_id":"gcal:r1","titulo":"Junta","tipo":"reunion","hora":"20:00","repeticion":null,"proximo_disparo":"2026-11-06T00:00:00Z"}]`,
		},
		{
			name: "edited minutos_antes overrides recordatorio_min",
			events: []event.Envelope{
				envelope("e1", "reunion_sincronizada", 1, "2026-11-01T20:00:00Z",
					`{"reunion_id":"r2","serie_id":"s2","cuenta":"ana@example.com","titulo":"Yoga","inicio":"2026-11-02T14:00:00Z","fin":"2026-11-02T15:00:00Z","todo_el_dia":false,"recordatorio_min":30,"estado":"confirmada","respuesta":"aceptada"}`),
				envelope("e2", "aviso_reunion_editado", 1, "2026-11-01T21:00:00Z", `{"serie_id":"s2","minutos_antes":10}`),
			},
			want: `[{"alarma_id":"gcal:r2","titulo":"Yoga","tipo":"reunion","hora":"09:50","repeticion":null,"proximo_disparo":"2026-11-02T13:50:00Z"}]`,
		},
		{
			name: "reunion_eliminada_en_origen drops the meeting",
			events: []event.Envelope{
				envelope("e1", "reunion_sincronizada", 1, "2026-11-01T20:00:00Z", todoElDia),
				envelope("e2", "reunion_eliminada_en_origen", 1, "2026-11-01T22:00:00Z", `{"reunion_id":"r1"}`),
				envelope("e3", "reunion_sincronizada", 1, "2026-11-01T20:00:00Z", queda),
			},
			want: wantQueda,
		},
		{
			name: "cancelada does not appear",
			events: []event.Envelope{
				envelope("e1", "reunion_sincronizada", 1, "2026-11-01T20:00:00Z",
					`{"reunion_id":"r3","serie_id":"s3","cuenta":"ana@example.com","titulo":"Anulada","inicio":"2026-11-04T14:00:00Z","fin":"2026-11-04T15:00:00Z","todo_el_dia":false,"recordatorio_min":30,"estado":"cancelada","respuesta":"aceptada"}`),
				envelope("e2", "reunion_sincronizada", 1, "2026-11-01T20:00:00Z", queda),
			},
			want: wantQueda,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := runActivas(t, tt.events); got != tt.want {
				t.Errorf("alarmas_activas =\n%s\nwant\n%s", got, tt.want)
			}
		})
	}
}
