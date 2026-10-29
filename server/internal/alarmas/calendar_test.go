package alarmas

import (
	"encoding/json"
	"testing"
	"time"

	"cauce/server/internal/core/event"
)

func intPtr(v int) *int { return &v }

// TestWeeklyNoBydayLocalWeekday covers WEEKLY without byday (SPEC section 4):
// the weekday that repeats is the LOCAL weekday of the first firing, not the
// UTC one. hora 21:00 in America/La_Paz (UTC-4) is 01:00Z of the next day, so
// the UTC weekday is off by one and the alarm used to fire on the wrong day.
func TestWeeklyNoBydayLocalWeekday(t *testing.T) {
	tests := []struct {
		name   string
		hora   string
		fecha  string // v3 anchor; empty = anchor at the creada ts
		creada string // RFC 3339; the alarma_creada.ts
		want   []string
	}{
		{
			name:   "WEEKLY first firing: local weekday, created before hora",
			hora:   "21:00",
			creada: "2026-11-04T00:00:00Z", // Tuesday 20:00 local
			want: []string{
				"2026-11-04T01:00:00Z", // Tuesday 21:00 local
				"2026-11-11T01:00:00Z", // next Tuesday, not Wednesday
				"2026-11-18T01:00:00Z",
			},
		},
		{
			name:   "WEEKLY with fecha: fires weekly on the fecha's local weekday",
			hora:   "21:00",
			fecha:  "2026-11-04", // Wednesday local
			creada: "2026-11-04T00:00:00Z",
			want: []string{
				"2026-11-05T01:00:00Z", // the fecha itself, Wednesday 21:00 local
				"2026-11-12T01:00:00Z",
				"2026-11-19T01:00:00Z",
			},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			loc, err := time.LoadLocation("America/La_Paz")
			if err != nil {
				t.Fatalf("LoadLocation: %v", err)
			}
			creada, err := time.Parse(time.RFC3339, tt.creada)
			if err != nil {
				t.Fatalf("creada: %v", err)
			}
			st := &alarma{
				alarmaPayload: alarmaPayload{
					AlarmaID: "a1",
					Hora:     tt.hora,
					Fecha:    tt.fecha,
					Repeticion: &repeticion{
						Freq: "WEEKLY",
					},
				},
				creada: creada,
			}
			ix := &indice{}
			cal, err := ix.calendario(st, loc)
			if err != nil {
				t.Fatalf("calendario: %v", err)
			}
			got := []string{}
			cal.eachFiring(func(f time.Time) bool {
				got = append(got, f.UTC().Format(time.RFC3339))
				return len(got) < len(tt.want)+1
			})
			if len(got) != len(tt.want)+1 {
				t.Fatalf("sequence ended after %d firings, want more than %d (firings %v)", len(got), len(tt.want), got)
			}
			for i, want := range tt.want {
				if got[i] != want {
					t.Errorf("firing[%d] = %s, want %s", i, got[i], want)
				}
			}
		})
	}
}

// TestMonthlyFirings covers the MONTHLY calendar edges of SPEC section 4:
// a 31st that does not exist is skipped without consuming count, -1 counts
// from the end of the month, a missing 5th weekday skips the month, and
// interval steps months even across skipped ones.
func TestMonthlyFirings(t *testing.T) {
	tests := []struct {
		name   string
		hora   string
		rep    repeticion
		anchor string // RFC 3339; the alarma_creada.ts
		tz     string
		want   []string // first firings, RFC 3339 UTC
		total  int      // firings the whole sequence has; 0 = continues past want
	}{
		{
			name:   "bymonthday 31 skips 30-day months and does not consume count",
			hora:   "09:00",
			rep:    repeticion{Freq: "MONTHLY", Bymonthday: intPtr(31), Count: intPtr(3)},
			anchor: "2026-10-23T14:00:00Z",
			tz:     "America/La_Paz",
			want: []string{
				"2026-10-31T13:00:00Z",
				"2026-12-31T13:00:00Z", // November has 30 days: skipped
				"2027-01-31T13:00:00Z",
			},
			total: 3,
		},
		{
			name:   "bymonthday -1 is the last day of every month, February included",
			hora:   "12:00",
			rep:    repeticion{Freq: "MONTHLY", Bymonthday: intPtr(-1)},
			anchor: "2026-01-15T13:00:00Z",
			tz:     "America/La_Paz",
			want: []string{
				"2026-01-31T16:00:00Z",
				"2026-02-28T16:00:00Z",
				"2026-03-31T16:00:00Z",
			},
		},
		{
			name:   "missing 5th weekday skips the month and does not consume count",
			hora:   "08:00",
			rep:    repeticion{Freq: "MONTHLY", Byday: []string{"5MO"}, Count: intPtr(3)},
			anchor: "2026-01-15T13:00:00Z",
			tz:     "America/La_Paz",
			want: []string{
				"2026-03-30T12:00:00Z", // Jan, Feb have four Mondays
				"2026-06-29T12:00:00Z", // Apr, May have four Mondays
				"2026-08-31T12:00:00Z", // Jul has four Mondays
			},
			total: 3,
		},
		{
			name:   "monthly interval 2 steps two months from the first firing",
			hora:   "18:00",
			rep:    repeticion{Freq: "MONTHLY", Interval: intPtr(2), Byday: []string{"-1FR"}},
			anchor: "2026-10-23T14:00:00Z",
			tz:     "America/La_Paz",
			want: []string{
				"2026-10-30T22:00:00Z",
				"2026-12-25T22:00:00Z",
				"2027-02-26T22:00:00Z",
			},
		},
		{
			name:   "interval 2 with bymonthday 31: skipped months do not consume count",
			hora:   "09:00",
			rep:    repeticion{Freq: "MONTHLY", Interval: intPtr(2), Bymonthday: intPtr(31), Count: intPtr(3)},
			anchor: "2026-10-23T14:00:00Z",
			tz:     "America/La_Paz",
			want: []string{
				"2026-10-31T13:00:00Z",
				"2026-12-31T13:00:00Z",
				"2027-08-31T13:00:00Z", // Feb, Apr, Jun 2027 have no 31st
			},
			total: 3,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			loc, err := time.LoadLocation(tt.tz)
			if err != nil {
				t.Fatalf("LoadLocation(%s): %v", tt.tz, err)
			}
			anchor, err := time.Parse(time.RFC3339, tt.anchor)
			if err != nil {
				t.Fatalf("anchor: %v", err)
			}
			cal, err := newCalendario(tt.hora, &tt.rep, anchor, loc)
			if err != nil {
				t.Fatalf("newCalendario: %v", err)
			}
			got := []string{}
			cal.eachFiring(func(f time.Time) bool {
				got = append(got, f.UTC().Format(time.RFC3339))
				return len(got) < len(tt.want)+1
			})
			if tt.total > 0 {
				if len(got) != tt.total {
					t.Fatalf("sequence length = %d, want %d (firings %v)", len(got), tt.total, got)
				}
			} else if len(got) != len(tt.want)+1 {
				t.Fatalf("sequence ended after %d firings, want more than %d (firings %v)", len(got), len(tt.want), got)
			}
			for i, want := range tt.want {
				if got[i] != want {
					t.Errorf("firing[%d] = %s, want %s", i, got[i], want)
				}
			}
		})
	}
}

// TestDSTFirings covers the RFC 5545 rules across US DST changes in
// America/New_York (SPEC section 8): a nonexistent wall time is interpreted
// with the offset in effect before the spring-forward gap (02:30 fires at
// 03:30) and an ambiguous one takes its first occurrence. Every firing is
// built from its local date at `hora`, so no firing shifts the later ones.
func TestDSTFirings(t *testing.T) {
	tests := []struct {
		name   string
		hora   string
		rep    *repeticion
		anchor string // RFC 3339; the alarma_creada.ts
		want   []string
		total  int // firings the whole sequence has; 0 = continues past want
	}{
		{
			name:   "DAILY: gap day fires at 03:30, later days stay at 02:30",
			hora:   "02:30",
			rep:    &repeticion{Freq: "DAILY"},
			anchor: "2027-03-13T20:30:00Z",
			want: []string{
				"2027-03-14T07:30:00Z", // 02:30 does not exist: 03:30 EDT
				"2027-03-15T06:30:00Z",
				"2027-03-16T06:30:00Z",
			},
		},
		{
			name:   "DAILY: a gap day inside the sequence does not shift later firings",
			hora:   "02:30",
			rep:    &repeticion{Freq: "DAILY"},
			anchor: "2027-03-11T06:30:00Z",
			want: []string{
				"2027-03-11T07:30:00Z",
				"2027-03-12T07:30:00Z",
				"2027-03-13T07:30:00Z",
				"2027-03-14T07:30:00Z",
				"2027-03-15T06:30:00Z",
				"2027-03-16T06:30:00Z",
			},
		},
		{
			name:   "firstHora: created just after the gap, next day at the right wall time",
			hora:   "02:30",
			rep:    nil,
			anchor: "2027-03-14T07:30:00Z",
			want: []string{
				"2027-03-15T06:30:00Z",
			},
			total: 1,
		},
		{
			name:   "WEEKLY: spring gap Sunday at 02:30 fires at 03:30, next Sundays stay",
			hora:   "02:30",
			rep:    &repeticion{Freq: "WEEKLY", Byday: []string{"SU"}},
			anchor: "2027-03-13T12:00:00Z",
			want: []string{
				"2027-03-14T07:30:00Z",
				"2027-03-21T06:30:00Z",
				"2027-03-28T06:30:00Z",
			},
		},
		{
			name:   "WEEKLY: fall overlap Sunday at 01:30 takes the first occurrence",
			hora:   "01:30",
			rep:    &repeticion{Freq: "WEEKLY", Byday: []string{"SU"}},
			anchor: "2027-11-06T16:00:00Z",
			want: []string{
				"2027-11-07T05:30:00Z", // 01:30 EDT, not 06:30Z (01:30 EST)
				"2027-11-14T06:30:00Z",
				"2027-11-21T06:30:00Z",
			},
		},
		{
			name:   "MONTHLY: spring gap day fires at 03:30, following months stay",
			hora:   "02:30",
			rep:    &repeticion{Freq: "MONTHLY", Bymonthday: intPtr(14)},
			anchor: "2027-02-11T12:00:00Z",
			want: []string{
				"2027-02-14T07:30:00Z",
				"2027-03-14T07:30:00Z",
				"2027-04-14T06:30:00Z",
			},
		},
		{
			name:   "MONTHLY: fall overlap day takes the first occurrence",
			hora:   "01:30",
			rep:    &repeticion{Freq: "MONTHLY", Bymonthday: intPtr(7)},
			anchor: "2027-10-05T12:00:00Z",
			want: []string{
				"2027-10-07T05:30:00Z",
				"2027-11-07T05:30:00Z",
				"2027-12-07T06:30:00Z",
			},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			loc, err := time.LoadLocation("America/New_York")
			if err != nil {
				t.Fatalf("LoadLocation: %v", err)
			}
			anchor, err := time.Parse(time.RFC3339, tt.anchor)
			if err != nil {
				t.Fatalf("anchor: %v", err)
			}
			cal, err := newCalendario(tt.hora, tt.rep, anchor, loc)
			if err != nil {
				t.Fatalf("newCalendario: %v", err)
			}
			got := []string{}
			cal.eachFiring(func(f time.Time) bool {
				got = append(got, f.UTC().Format(time.RFC3339))
				return len(got) < len(tt.want)+1
			})
			if tt.total > 0 {
				if len(got) != tt.total {
					t.Fatalf("sequence length = %d, want %d (firings %v)", len(got), tt.total, got)
				}
			} else if len(got) != len(tt.want)+1 {
				t.Fatalf("sequence ended after %d firings, want more than %d (firings %v)", len(got), len(tt.want), got)
			}
			for i, want := range tt.want {
				if got[i] != want {
					t.Errorf("firing[%d] = %s, want %s", i, got[i], want)
				}
			}
		})
	}
}

// TestCreadaAnchorOutOfOrder checks that the calendar anchors at the
// alarma_creada ts even when a later alarma_modificada is processed first.
func TestCreadaAnchorOutOfOrder(t *testing.T) {
	creada := `{"alarma_id":"a1","titulo":"fx","tipo":"remedio","hora":"09:00","repeticion":{"freq":"DAILY","interval":1},"destinatario":"g","remedio":"r"}`
	modificada := `{"alarma_id":"a1","titulo":"fx+","tipo":"remedio","hora":"09:00","repeticion":{"freq":"DAILY","interval":1},"destinatario":"g","remedio":"r"}`
	events := []event.Envelope{
		{ID: "e2", Type: "alarma_modificada", V: 2, TS: "2026-10-23T10:00:00Z", Payload: json.RawMessage(modificada)},
		{ID: "e1", Type: "alarma_creada", V: 2, TS: "2026-10-22T08:00:00Z", Payload: json.RawMessage(creada)},
	}
	ix, err := indexar(events)
	if err != nil {
		t.Fatalf("indexar: %v", err)
	}
	st := ix.estados["a1"]
	if st == nil {
		t.Fatal("missing estado a1")
	}
	anchorWant, _ := time.Parse(time.RFC3339, "2026-10-22T08:00:00Z")
	if !st.creada.Equal(anchorWant) {
		t.Fatalf("creada anchor = %s, want %s", st.creada, anchorWant)
	}
	cal, err := ix.calendario(st, time.UTC)
	if err != nil {
		t.Fatalf("calendario: %v", err)
	}
	got := []string{}
	cal.eachFiring(func(f time.Time) bool {
		got = append(got, f.UTC().Format(time.RFC3339))
		return len(got) < 2
	})
	want := []string{"2026-10-22T09:00:00Z", "2026-10-23T09:00:00Z"}
	for i, w := range want {
		if got[i] != w {
			t.Errorf("firing[%d] = %s, want %s", i, got[i], w)
		}
	}
}

// TestFechaAnchor covers the `fecha` anchor of SPEC section 4: with fecha the
// first firing is the first occurrence of `hora` at or after `fecha` at `hora`
// that satisfies the rule (fecha itself if valid), and count/interval count
// from there even when the alarma_creada came later.
func TestFechaAnchor(t *testing.T) {
	tests := []struct {
		name   string
		hora   string
		rep    *repeticion
		fecha  string
		creada string // RFC 3339; the alarma_creada.ts
		tz     string
		want   []string // first firings, RFC 3339 UTC
		total  int      // firings the whole sequence has; 0 = continues past want
	}{
		{
			name:   "MONTHLY count counts from fecha, not from the creation ts",
			hora:   "09:00",
			rep:    &repeticion{Freq: "MONTHLY", Bymonthday: intPtr(20), Count: intPtr(3)},
			fecha:  "2026-11-20",
			creada: "2026-11-25T12:00:00Z", // created after the first firing
			tz:     "America/La_Paz",
			want: []string{
				"2026-11-20T13:00:00Z", // fecha itself is the first firing
				"2026-12-20T13:00:00Z",
				"2027-01-20T13:00:00Z",
			},
			total: 3,
		},
		{
			name:   "WEEKLY with fecha on a non-matching day moves forward",
			hora:   "15:00",
			rep:    &repeticion{Freq: "WEEKLY", Byday: []string{"MO"}, Interval: intPtr(2)},
			fecha:  "2026-11-22", // Sunday: the next Monday is in the next ISO week
			creada: "2026-11-01T12:00:00Z",
			tz:     "America/La_Paz",
			want: []string{
				"2026-11-23T19:00:00Z", // moved forward to the first Monday
				"2026-12-07T19:00:00Z", // interval 2 from the week of that firing
				"2026-12-21T19:00:00Z",
			},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			loc, err := time.LoadLocation(tt.tz)
			if err != nil {
				t.Fatalf("LoadLocation(%s): %v", tt.tz, err)
			}
			creada, err := time.Parse(time.RFC3339, tt.creada)
			if err != nil {
				t.Fatalf("creada: %v", err)
			}
			st := &alarma{
				alarmaPayload: alarmaPayload{
					AlarmaID:   "a1",
					Hora:       tt.hora,
					Fecha:      tt.fecha,
					Repeticion: tt.rep,
				},
				creada: creada,
			}
			ix := &indice{}
			cal, err := ix.calendario(st, loc)
			if err != nil {
				t.Fatalf("calendario: %v", err)
			}
			got := []string{}
			cal.eachFiring(func(f time.Time) bool {
				got = append(got, f.UTC().Format(time.RFC3339))
				return len(got) < len(tt.want)+1
			})
			if tt.total > 0 {
				if len(got) != tt.total {
					t.Fatalf("sequence length = %d, want %d (firings %v)", len(got), tt.total, got)
				}
			} else if len(got) != len(tt.want)+1 {
				t.Fatalf("sequence ended after %d firings, want more than %d (firings %v)", len(got), len(tt.want), got)
			}
			for i, want := range tt.want {
				if got[i] != want {
					t.Errorf("firing[%d] = %s, want %s", i, got[i], want)
				}
			}
		})
	}
}
