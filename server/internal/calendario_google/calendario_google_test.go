package calendario_google

import (
	"encoding/json"
	"testing"
	"time"

	"cauce/server/internal/core/event"
	"cauce/server/internal/core/projection"
)

func ip(n int) *int { return &n }

func TestAvisoVigente(t *testing.T) {
	loc, _ := time.LoadLocation("America/La_Paz")
	ini := time.Date(2026, 11, 5, 13, 0, 0, 0, time.UTC)
	allDay := time.Date(2026, 11, 5, 0, 0, 0, 0, time.UTC)
	tests := []struct {
		name    string
		r       reunion
		ed      *avisoEditado
		origen  string
		enNil   bool
		wantEn  time.Time
		wantMin *int
		wantH   string
	}{
		{"google", reunion{inicio: ini, recordatorio: ip(30)}, nil, "google", false, ini.Add(-30 * time.Minute), ip(30), ""},
		{"google 0", reunion{inicio: ini, recordatorio: ip(0)}, nil, "google", false, ini, ip(0), ""},
		{"defecto 10", reunion{inicio: ini}, nil, "defecto", false, ini.Add(-10 * time.Minute), ip(10), ""},
		{"editado minutos manda", reunion{inicio: ini, recordatorio: ip(30)}, &avisoEditado{minutosAntes: ip(5)}, "cauce", false, ini.Add(-5 * time.Minute), ip(5), ""},
		{"apagado", reunion{inicio: ini}, &avisoEditado{apagado: true}, "cauce", true, time.Time{}, nil, ""},
		{"todo el dia 08", reunion{inicio: allDay, todoElDia: true, recordatorio: ip(1440)}, nil, "defecto", false, time.Date(2026, 11, 5, 12, 0, 0, 0, time.UTC), nil, "08:00"},
		{"todo el dia hora", reunion{inicio: allDay, todoElDia: true}, &avisoEditado{hora: "07:00"}, "cauce", false, time.Date(2026, 11, 5, 11, 0, 0, 0, time.UTC), nil, "07:00"},
		{"hora en reunion con hora", reunion{inicio: ini}, &avisoEditado{hora: "09:00"}, "cauce", false, time.Date(2026, 11, 5, 13, 0, 0, 0, time.UTC), nil, "09:00"},
		{"minutos en todo el dia", reunion{inicio: allDay, todoElDia: true}, &avisoEditado{minutosAntes: ip(60)}, "cauce", false, allDay.Add(-time.Hour), ip(60), ""},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			av, en, err := tc.r.avisoVigente(tc.ed, loc)
			if err != nil {
				t.Fatal(err)
			}
			if av.Origen != tc.origen || av.Hora != tc.wantH || av.Apagado != tc.enNil {
				t.Errorf("aviso = %+v", av)
			}
			if (av.MinutosAntes == nil) != (tc.wantMin == nil) || (tc.wantMin != nil && *av.MinutosAntes != *tc.wantMin) {
				t.Errorf("minutos_antes = %v", av.MinutosAntes)
			}
			if tc.enNil {
				if en != nil {
					t.Errorf("aviso_en = %v, want nil", en)
				}
				return
			}
			if en == nil || !en.Equal(tc.wantEn) {
				t.Errorf("aviso_en = %v, want %v", en, tc.wantEn)
			}
		})
	}
}

func TestIncludeFilter(t *testing.T) {
	now := time.Date(2026, 11, 2, 12, 0, 0, 0, time.UTC)
	tests := []struct {
		name      string
		estado    string
		respuesta string
		inicio    time.Time
		want      bool
	}{
		{"confirmada aceptada futura", "confirmada", "aceptada", now.Add(time.Hour), true},
		{"tentativa", "confirmada", "tentativa", now.Add(time.Hour), true},
		{"sin respuesta", "confirmada", "sin_respuesta", now.Add(time.Hour), true},
		{"rechazada", "confirmada", "rechazada", now.Add(time.Hour), false},
		{"cancelada", "cancelada", "aceptada", now.Add(time.Hour), false},
		{"inicio igual a now", "confirmada", "aceptada", now, false},
		{"aviso pasado inicio futuro", "confirmada", "aceptada", now.Add(5 * time.Minute), true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			ev := reunionEv("r", "s", tc.estado, tc.respuesta, tc.inicio)
			got, err := ReunionesProximas{}.Run(ev, paramsFor(now))
			if err != nil {
				t.Fatal(err)
			}
			if has := len(got.([]reunionOut)) == 1; has != tc.want {
				t.Errorf("included = %v, want %v", has, tc.want)
			}
		})
	}
}

func paramsFor(now time.Time) projection.Params {
	n, _ := json.Marshal(now.Format(time.RFC3339))
	tz, _ := json.Marshal("America/La_Paz")
	return projection.Params{"now": n, "tz": tz}
}

func reunionEv(id, serie, estado, respuesta string, inicio time.Time) []event.Envelope {
	p, _ := json.Marshal(map[string]any{
		"reunion_id": id, "serie_id": serie, "cuenta": "a@example.com", "titulo": "t",
		"inicio": inicio.Format(time.RFC3339), "fin": inicio.Add(time.Hour).Format(time.RFC3339),
		"todo_el_dia": false, "recordatorio_min": nil, "estado": estado, "respuesta": respuesta,
	})
	return []event.Envelope{{ID: "e1", Type: "reunion_sincronizada", V: 1, TS: "2026-11-01T00:00:00Z", Payload: p}}
}
