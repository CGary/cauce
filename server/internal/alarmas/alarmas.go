// Package alarmas implements the server projections of the `alarmas` feature:
// P1 alarmas_activas, P2 adherencia_tratamiento and P3 historial_dosis. They
// are pure (events, params) -> result: no clock, no I/O; `now` and `tz` come
// in as params (SPEC schema/features/alarmas/SPEC.md section 4).
package alarmas

import (
	"encoding/json"
	"fmt"
	"sort"
	"time"

	_ "time/tzdata" // the server image is FROM scratch: no system zoneinfo

	"cauce/server/internal/core/event"
	"cauce/server/internal/core/projection"
)

// repeticion is the RRULE subset of alarma_creada.v2 / alarma_modificada.v2.
// Fields are pointers so an explicit "interval": 1 echoes back as written.
type repeticion struct {
	Freq       string   `json:"freq"`
	Interval   *int     `json:"interval,omitempty"`
	Byday      []string `json:"byday,omitempty"`
	Bymonthday *int     `json:"bymonthday,omitempty"`
	Count      *int     `json:"count,omitempty"`
	Until      string   `json:"until,omitempty"`
}

// intervalDays is `interval` with the RFC 5545 default of 1.
func (r *repeticion) intervalDays() int {
	if r == nil || r.Interval == nil || *r.Interval < 1 {
		return 1
	}
	return *r.Interval
}

// alarmaPayload is the full state of alarma_creada.v2 / alarma_modificada.v2.
// Unknown fields are ignored by encoding/json.
type alarmaPayload struct {
	AlarmaID     string      `json:"alarma_id"`
	Titulo       string      `json:"titulo"`
	Tipo         string      `json:"tipo"`
	Hora         string      `json:"hora"`
	Repeticion   *repeticion `json:"repeticion"`
	Destinatario string      `json:"destinatario"`
	Remedio      string      `json:"remedio"`
}

// alarma is the vigente state: the last alarma_creada / alarma_modificada by
// ts (full state, last ts wins). The calendar anchors at the ts of the
// alarma_creada (SPEC section 4), tracked separately from the vigente ts so
// the anchor does not depend on the order the events arrive in.
type alarma struct {
	alarmaPayload
	ts     time.Time
	creada time.Time
}

type dosisEvento struct {
	alarmaID     string
	programada   string
	destinatario string
	remedio      string
	ts           time.Time
}

type atendidaEvento struct {
	alarmaID   string
	programada string
	accion     string
}

// indice is the whole event log reduced to what the three projections need.
type indice struct {
	estados      map[string]*alarma
	desactivadas map[string]bool
	dosis        []*dosisEvento
	atendidas    []atendidaEvento
}

func indexar(events []event.Envelope) (*indice, error) {
	ix := &indice{
		estados:      map[string]*alarma{},
		desactivadas: map[string]bool{},
	}
	for _, ev := range events {
		switch ev.Type {
		case "alarma_creada", "alarma_modificada":
			// (SPEC section 8): the projections read only v2.
			if ev.V != 2 {
				continue
			}
			var p alarmaPayload
			if err := json.Unmarshal(ev.Payload, &p); err != nil {
				return nil, fmt.Errorf("event %s: %w", ev.ID, err)
			}
			if p.AlarmaID == "" {
				return nil, fmt.Errorf("event %s: missing alarma_id", ev.ID)
			}
			ts, err := time.Parse(time.RFC3339, ev.TS)
			if err != nil {
				return nil, fmt.Errorf("event %s: ts: %w", ev.ID, err)
			}
			st := ix.estados[p.AlarmaID]
			if st == nil {
				st = &alarma{}
				ix.estados[p.AlarmaID] = st
			}
			if st.ts.IsZero() || !ts.Before(st.ts) {
				st.alarmaPayload = p
				st.ts = ts
			}
			if ev.Type == "alarma_creada" && (st.creada.IsZero() || ts.After(st.creada)) {
				st.creada = ts
			}
		case "alarma_desactivada":
			var p struct {
				AlarmaID string `json:"alarma_id"`
			}
			if err := json.Unmarshal(ev.Payload, &p); err != nil {
				return nil, fmt.Errorf("event %s: %w", ev.ID, err)
			}
			if p.AlarmaID != "" {
				ix.desactivadas[p.AlarmaID] = true
			}
		case "dosis_dada":
			var p struct {
				AlarmaID     string `json:"alarma_id"`
				Destinatario string `json:"destinatario"`
				Remedio      string `json:"remedio"`
				Programada   string `json:"programada_para"`
			}
			if err := json.Unmarshal(ev.Payload, &p); err != nil {
				return nil, fmt.Errorf("event %s: %w", ev.ID, err)
			}
			ts, err := time.Parse(time.RFC3339, ev.TS)
			if err != nil {
				return nil, fmt.Errorf("event %s: ts: %w", ev.ID, err)
			}
			ix.dosis = append(ix.dosis, &dosisEvento{
				alarmaID:     p.AlarmaID,
				programada:   p.Programada,
				destinatario: p.Destinatario,
				remedio:      p.Remedio,
				ts:           ts,
			})
		case "alarma_atendida":
			var p struct {
				AlarmaID   string `json:"alarma_id"`
				Programada string `json:"programada_para"`
				Accion     string `json:"accion"`
			}
			if err := json.Unmarshal(ev.Payload, &p); err != nil {
				return nil, fmt.Errorf("event %s: %w", ev.ID, err)
			}
			ix.atendidas = append(ix.atendidas, atendidaEvento{
				alarmaID:   p.AlarmaID,
				programada: p.Programada,
				accion:     p.Accion,
			})
		}
	}
	return ix, nil
}

func (ix *indice) calendario(st *alarma, loc *time.Location) (*calendario, error) {
	anchor := st.creada
	if anchor.IsZero() {
		// A modificada without its creada in the log: anchor on the edit's ts.
		anchor = st.ts
	}
	return newCalendario(st.Hora, st.Repeticion, anchor, loc)
}

// Projections returns the three read models for registration.
func Projections() []projection.Projection {
	return []projection.Projection{
		AlarmasActivas{},
		AdherenciaTratamiento{},
		HistorialDosis{},
	}
}

// AlarmasActivas is P1: active alarms with their next firing.
type AlarmasActivas struct{}

func (AlarmasActivas) Key() string { return "alarmas.alarmas_activas" }

type alarmaActiva struct {
	AlarmaID       string      `json:"alarma_id"`
	Titulo         string      `json:"titulo"`
	Tipo           string      `json:"tipo"`
	Hora           string      `json:"hora"`
	Repeticion     *repeticion `json:"repeticion"`
	ProximoDisparo string      `json:"proximo_disparo"`
	Restantes      *int        `json:"restantes,omitempty"`
}

func (AlarmasActivas) Run(events []event.Envelope, params projection.Params) (any, error) {
	now, err := params.Now(time.Time{})
	if err != nil {
		return nil, err
	}
	loc, err := location(params)
	if err != nil {
		return nil, err
	}
	ix, err := indexar(events)
	if err != nil {
		return nil, err
	}
	out := []alarmaActiva{}
	for id, st := range ix.estados {
		if ix.desactivadas[id] {
			continue
		}
		cal, err := ix.calendario(st, loc)
		if err != nil {
			return nil, err
		}
		proximo, ok := nextAfter(cal, now)
		if !ok {
			continue
		}
		var restantes *int
		if st.Repeticion != nil && st.Repeticion.Count != nil {
			value := cal.remainingFirings(proximo)
			restantes = &value
		}
		out = append(out, alarmaActiva{
			AlarmaID:       st.AlarmaID,
			Titulo:         st.Titulo,
			Tipo:           st.Tipo,
			Hora:           st.Hora,
			Repeticion:     st.Repeticion,
			ProximoDisparo: proximo.UTC().Format(time.RFC3339),
			Restantes:      restantes,
		})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].ProximoDisparo != out[j].ProximoDisparo {
			return out[i].ProximoDisparo < out[j].ProximoDisparo
		}
		return out[i].AlarmaID < out[j].AlarmaID
	})
	return out, nil
}

// AdherenciaTratamiento is P2: expected, taken and ignored doses per remedy
// alarm, deactivated ones included.
type AdherenciaTratamiento struct{}

func (AdherenciaTratamiento) Key() string { return "alarmas.adherencia_tratamiento" }

type adherencia struct {
	AlarmaID       string  `json:"alarma_id"`
	Destinatario   string  `json:"destinatario"`
	Remedio        string  `json:"remedio"`
	DosisEsperadas int     `json:"dosis_esperadas"`
	DosisDadas     int     `json:"dosis_dadas"`
	Ignoradas      int     `json:"ignoradas"`
	UltimaDosis    *string `json:"ultima_dosis"`
}

func (AdherenciaTratamiento) Run(events []event.Envelope, params projection.Params) (any, error) {
	now, err := params.Now(time.Time{})
	if err != nil {
		return nil, err
	}
	loc, err := location(params)
	if err != nil {
		return nil, err
	}
	ix, err := indexar(events)
	if err != nil {
		return nil, err
	}
	dadas := map[string]map[string]bool{}
	ultima := map[string]time.Time{}
	for _, d := range ix.dosis {
		if dadas[d.alarmaID] == nil {
			dadas[d.alarmaID] = map[string]bool{}
		}
		dadas[d.alarmaID][d.programada] = true
		if t, ok := ultima[d.alarmaID]; !ok || d.ts.After(t) {
			ultima[d.alarmaID] = d.ts
		}
	}
	ignoradas := map[string]map[string]bool{}
	for _, a := range ix.atendidas {
		if a.accion != "ignorada" {
			continue
		}
		if ignoradas[a.alarmaID] == nil {
			ignoradas[a.alarmaID] = map[string]bool{}
		}
		ignoradas[a.alarmaID][a.programada] = true
	}
	out := []adherencia{}
	for id, st := range ix.estados {
		if st.Tipo != "remedio" {
			continue
		}
		cal, err := ix.calendario(st, loc)
		if err != nil {
			return nil, err
		}
		row := adherencia{
			AlarmaID:     id,
			Destinatario: st.Destinatario,
			Remedio:      st.Remedio,
			DosisDadas:   len(dadas[id]),
			UltimaDosis:  nil,
		}
		cal.eachFiring(func(t time.Time) bool {
			if t.After(now) {
				return false
			}
			row.DosisEsperadas++
			return true
		})
		for programada := range ignoradas[id] {
			if !dadas[id][programada] {
				row.Ignoradas++
			}
		}
		if t, ok := ultima[id]; ok {
			text := t.UTC().Format(time.RFC3339)
			row.UltimaDosis = &text
		}
		out = append(out, row)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].AlarmaID < out[j].AlarmaID })
	return out, nil
}

// HistorialDosis is P3: the dose log, newest first, optionally filtered by
// destinatario. One entry per (alarma_id, programada_para) with the smallest
// ts, so sync retries do not duplicate rows.
type HistorialDosis struct{}

func (HistorialDosis) Key() string { return "alarmas.historial_dosis" }

type dosisHistorial struct {
	TS           string `json:"ts"`
	Destinatario string `json:"destinatario"`
	Remedio      string `json:"remedio"`
}

func (HistorialDosis) Run(events []event.Envelope, params projection.Params) (any, error) {
	destinatario, filtrar, err := params.String("destinatario")
	if err != nil {
		return nil, err
	}
	ix, err := indexar(events)
	if err != nil {
		return nil, err
	}
	seen := map[[2]string]*dosisEvento{}
	for _, d := range ix.dosis {
		if filtrar && d.destinatario != destinatario {
			continue
		}
		key := [2]string{d.alarmaID, d.programada}
		if prev, ok := seen[key]; !ok || d.ts.Before(prev.ts) {
			seen[key] = d
		}
	}
	type fila struct {
		row dosisHistorial
		key [2]string
	}
	filas := make([]fila, 0, len(seen))
	for key, d := range seen {
		filas = append(filas, fila{
			row: dosisHistorial{
				TS:           d.ts.UTC().Format(time.RFC3339),
				Destinatario: d.destinatario,
				Remedio:      d.remedio,
			},
			key: key,
		})
	}
	sort.Slice(filas, func(i, j int) bool {
		if filas[i].row.TS != filas[j].row.TS {
			return filas[i].row.TS > filas[j].row.TS
		}
		if filas[i].key[0] != filas[j].key[0] {
			return filas[i].key[0] < filas[j].key[0]
		}
		return filas[i].key[1] < filas[j].key[1]
	})
	out := make([]dosisHistorial, len(filas))
	for i, f := range filas {
		out[i] = f.row
	}
	return out, nil
}

// nextAfter returns the first firing strictly after `after`.
func nextAfter(cal *calendario, after time.Time) (time.Time, bool) {
	var found time.Time
	ok := false
	cal.eachFiring(func(t time.Time) bool {
		if t.After(after) {
			found, ok = t, true
			return false
		}
		return true
	})
	return found, ok
}

// location resolves params.tz (IANA), required by P1 and P2.
func location(params projection.Params) (*time.Location, error) {
	tz, ok, err := params.String("tz")
	if err != nil {
		return nil, err
	}
	if !ok || tz == "" {
		return nil, fmt.Errorf("missing required param %q", "tz")
	}
	loc, err := time.LoadLocation(tz)
	if err != nil {
		return nil, fmt.Errorf("param %q: %w", "tz", err)
	}
	return loc, nil
}
