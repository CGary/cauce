// Package calendario_google implements the server projection of the
// `calendario_google` feature: P1 reuniones_proximas. It is pure
// (events, params) -> result: `now` and `tz` come in as params (SPEC
// schema/features/calendario_google/SPEC.md section 4).
package calendario_google

import (
	"encoding/json"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"

	_ "time/tzdata" // the server image is FROM scratch: no system zoneinfo

	"cauce/server/internal/core/event"
	"cauce/server/internal/core/projection"
)

// Projections returns the read models for registration.
func Projections() []projection.Projection {
	return []projection.Projection{ReunionesProximas{}}
}

// ReunionesProximas is P1: upcoming meetings with their effective aviso.
type ReunionesProximas struct{}

func (ReunionesProximas) Key() string { return "calendario_google.reuniones_proximas" }

type aviso struct {
	Origen       string `json:"origen"`
	MinutosAntes *int   `json:"minutos_antes,omitempty"`
	Hora         string `json:"hora,omitempty"`
	Apagado      bool   `json:"apagado,omitempty"`
}

type reunionOut struct {
	ReunionID string  `json:"reunion_id"`
	SerieID   string  `json:"serie_id"`
	Cuenta    string  `json:"cuenta"`
	Titulo    string  `json:"titulo"`
	Inicio    string  `json:"inicio"`
	Fin       string  `json:"fin"`
	TodoElDia bool    `json:"todo_el_dia"`
	Aviso     aviso   `json:"aviso"`
	AvisoEn   *string `json:"aviso_en"`
}

// reunion is the vigente state of one occurrence: the last event by ts
// between reunion_sincronizada and reunion_eliminada_en_origen (ties: later
// in input wins).
type reunion struct {
	id           string
	serieID      string
	cuenta       string
	titulo       string
	inicio       time.Time
	fin          time.Time
	todoElDia    bool
	recordatorio *int
	estado       string
	respuesta    string
	ts           time.Time
	eliminada    bool
}

// avisoEditado is the vigente aviso of a series (last by ts, same tie rule).
type avisoEditado struct {
	minutosAntes *int
	hora         string
	apagado      bool
	ts           time.Time
}

func (ReunionesProximas) Run(events []event.Envelope, params projection.Params) (any, error) {
	now, err := params.Now(time.Time{})
	if err != nil {
		return nil, err
	}
	loc, err := location(params)
	if err != nil {
		return nil, err
	}
	reuniones := map[string]*reunion{}
	avisos := map[string]*avisoEditado{}
	for _, ev := range events {
		if ev.V != 1 {
			continue
		}
		switch ev.Type {
		case "reunion_sincronizada":
			var p struct {
				ReunionID       string `json:"reunion_id"`
				SerieID         string `json:"serie_id"`
				Cuenta          string `json:"cuenta"`
				Titulo          string `json:"titulo"`
				Inicio          string `json:"inicio"`
				Fin             string `json:"fin"`
				TodoElDia       bool   `json:"todo_el_dia"`
				RecordatorioMin *int   `json:"recordatorio_min"`
				Estado          string `json:"estado"`
				Respuesta       string `json:"respuesta"`
			}
			if err := json.Unmarshal(ev.Payload, &p); err != nil {
				return nil, fmt.Errorf("event %s: %w", ev.ID, err)
			}
			if p.ReunionID == "" {
				return nil, fmt.Errorf("event %s: missing reunion_id", ev.ID)
			}
			ts, err := time.Parse(time.RFC3339, ev.TS)
			if err != nil {
				return nil, fmt.Errorf("event %s: ts: %w", ev.ID, err)
			}
			inicio, err := time.Parse(time.RFC3339, p.Inicio)
			if err != nil {
				return nil, fmt.Errorf("event %s: inicio: %w", ev.ID, err)
			}
			fin, err := time.Parse(time.RFC3339, p.Fin)
			if err != nil {
				return nil, fmt.Errorf("event %s: fin: %w", ev.ID, err)
			}
			st := reuniones[p.ReunionID]
			if st == nil {
				st = &reunion{}
				reuniones[p.ReunionID] = st
			}
			if st.ts.IsZero() || !ts.Before(st.ts) {
				*st = reunion{
					id: p.ReunionID, serieID: p.SerieID, cuenta: p.Cuenta,
					titulo: p.Titulo, inicio: inicio, fin: fin,
					todoElDia: p.TodoElDia, recordatorio: p.RecordatorioMin,
					estado: p.Estado, respuesta: p.Respuesta, ts: ts,
				}
			}
		case "reunion_eliminada_en_origen":
			var p struct {
				ReunionID string `json:"reunion_id"`
			}
			if err := json.Unmarshal(ev.Payload, &p); err != nil {
				return nil, fmt.Errorf("event %s: %w", ev.ID, err)
			}
			if p.ReunionID == "" {
				return nil, fmt.Errorf("event %s: missing reunion_id", ev.ID)
			}
			ts, err := time.Parse(time.RFC3339, ev.TS)
			if err != nil {
				return nil, fmt.Errorf("event %s: ts: %w", ev.ID, err)
			}
			st := reuniones[p.ReunionID]
			if st == nil {
				st = &reunion{}
				reuniones[p.ReunionID] = st
			}
			if st.ts.IsZero() || !ts.Before(st.ts) {
				*st = reunion{id: p.ReunionID, ts: ts, eliminada: true}
			}
		case "aviso_reunion_editado":
			var p struct {
				SerieID      string `json:"serie_id"`
				MinutosAntes *int   `json:"minutos_antes"`
				Hora         string `json:"hora"`
				Apagado      bool   `json:"apagado"`
			}
			if err := json.Unmarshal(ev.Payload, &p); err != nil {
				return nil, fmt.Errorf("event %s: %w", ev.ID, err)
			}
			if p.SerieID == "" {
				return nil, fmt.Errorf("event %s: missing serie_id", ev.ID)
			}
			ts, err := time.Parse(time.RFC3339, ev.TS)
			if err != nil {
				return nil, fmt.Errorf("event %s: ts: %w", ev.ID, err)
			}
			st := avisos[p.SerieID]
			if st == nil {
				st = &avisoEditado{}
				avisos[p.SerieID] = st
			}
			if st.ts.IsZero() || !ts.Before(st.ts) {
				*st = avisoEditado{minutosAntes: p.MinutosAntes, hora: p.Hora, apagado: p.Apagado, ts: ts}
			}
		}
	}

	out := []reunionOut{}
	for _, r := range reuniones {
		if r.eliminada || r.estado != "confirmada" || r.respuesta == "rechazada" || !r.inicio.After(now) {
			continue
		}
		av, en, err := r.avisoVigente(avisos[r.serieID], loc)
		if err != nil {
			return nil, err
		}
		o := reunionOut{
			ReunionID: r.id, SerieID: r.serieID, Cuenta: r.cuenta, Titulo: r.titulo,
			Inicio:    r.inicio.UTC().Format(time.RFC3339),
			Fin:       r.fin.UTC().Format(time.RFC3339),
			TodoElDia: r.todoElDia, Aviso: av,
		}
		if en != nil {
			s := en.UTC().Format(time.RFC3339)
			o.AvisoEn = &s
		}
		out = append(out, o)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Inicio != out[j].Inicio {
			return out[i].Inicio < out[j].Inicio
		}
		return out[i].ReunionID < out[j].ReunionID
	})
	return out, nil
}

// avisoVigente applies the aviso rule of SPEC section 4: edited (apagado /
// minutos_antes / hora) wins; else all-day 08:00 local (Google ignored); else
// the Google reminder; else 10 minutes before. A nil time is `aviso_en: null`.
func (r *reunion) avisoVigente(ed *avisoEditado, loc *time.Location) (aviso, *time.Time, error) {
	y, m, d := r.inicio.In(loc).Date()
	if r.todoElDia {
		y, m, d = r.inicio.UTC().Date()
	}
	minus := func(n int) *time.Time {
		t := r.inicio.Add(-time.Duration(n) * time.Minute)
		return &t
	}
	if ed != nil {
		switch {
		case ed.apagado:
			return aviso{Origen: "cauce", Apagado: true}, nil, nil
		case ed.minutosAntes != nil:
			return aviso{Origen: "cauce", MinutosAntes: ed.minutosAntes}, minus(*ed.minutosAntes), nil
		case ed.hora != "":
			hour, min, err := parseHora(ed.hora)
			if err != nil {
				return aviso{}, nil, err
			}
			t := horaLocal(y, m, d, hour, min, loc)
			return aviso{Origen: "cauce", Hora: ed.hora}, &t, nil
		}
	}
	if r.todoElDia {
		t := horaLocal(y, m, d, 8, 0, loc)
		return aviso{Origen: "defecto", Hora: "08:00"}, &t, nil
	}
	if r.recordatorio != nil {
		return aviso{Origen: "google", MinutosAntes: r.recordatorio}, minus(*r.recordatorio), nil
	}
	ten := 10
	return aviso{Origen: "defecto", MinutosAntes: &ten}, minus(10), nil
}

func parseHora(hora string) (int, int, error) {
	parts := strings.Split(hora, ":")
	if len(parts) != 2 {
		return 0, 0, fmt.Errorf("hora %q: want HH:MM", hora)
	}
	hour, err := strconv.Atoi(parts[0])
	if err != nil || hour < 0 || hour > 23 {
		return 0, 0, fmt.Errorf("hora %q: invalid hour", hora)
	}
	min, err := strconv.Atoi(parts[1])
	if err != nil || min < 0 || min > 59 {
		return 0, 0, fmt.Errorf("hora %q: invalid minute", hora)
	}
	return hour, min, nil
}

// location resolves params.tz (IANA), required.
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

// horaLocal builds the wall time (y, m, d, hour:min) in loc with the RFC 5545
// rule: a nonexistent local time uses the offset in effect before the gap and
// an ambiguous one resolves to its first occurrence.
func horaLocal(y int, m time.Month, d, hour, min int, loc *time.Location) time.Time {
	u := time.Date(y, m, d, hour, min, 0, 0, time.UTC)
	preGap := offsetAt(time.Date(y, m, d-1, 23, 0, 0, 0, loc))
	offs := []int{preGap}
	for _, p := range []time.Time{
		time.Date(y, m, d, 0, 0, 0, 0, loc),
		time.Date(y, m, d, 12, 0, 0, 0, loc),
		time.Date(y, m, d+1, 0, 0, 0, 0, loc),
	} {
		if off := offsetAt(p); !containsInt(offs, off) {
			offs = append(offs, off)
		}
	}
	var occs []time.Time
	for _, off := range offs {
		cand := u.Add(-time.Duration(off) * time.Second)
		yy, mm, dd := cand.In(loc).Date()
		hh, mi, _ := cand.In(loc).Clock()
		if yy == y && mm == m && dd == d && hh == hour && mi == min {
			occs = append(occs, cand)
		}
	}
	if len(occs) > 1 {
		sort.Slice(occs, func(i, j int) bool { return occs[i].Before(occs[j]) })
		return occs[0]
	}
	if len(occs) == 1 {
		return occs[0]
	}
	return u.Add(-time.Duration(preGap) * time.Second)
}

func offsetAt(t time.Time) int {
	_, off := t.Zone()
	return off
}

func containsInt(xs []int, x int) bool {
	for _, v := range xs {
		if v == x {
			return true
		}
	}
	return false
}
