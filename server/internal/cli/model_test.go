package cli

import (
	"encoding/json"
	"testing"
	"time"
)

func TestParseFlags(t *testing.T) {
	specs := append([]flagSpec{
		{Name: "titulo", Kind: kindString},
		{Name: "count", Kind: kindInt, Min: intp(1), Max: intp(maxCount)},
		{Name: "bymonthday", Kind: kindInt, Min: intp(-1), Max: intp(31)},
		{Name: "freq", Kind: kindEnum, Enum: freqEnum},
		{Name: "byday", Kind: kindList},
	}, universalFlags...)
	cases := []struct {
		name string
		args []string
		code string
		want map[string]any
	}{
		{"space and equals", []string{"--titulo", "Gym", "--count=3"}, "", map[string]any{"titulo": "Gym", "count": 3}},
		{"negative value", []string{"--bymonthday", "-1"}, "", map[string]any{"bymonthday": -1}},
		{"enum is case-insensitive", []string{"--freq", "HOURLY"}, "", map[string]any{"freq": "hourly"}},
		{"list", []string{"--byday", "mo,we"}, "", map[string]any{"byday": []string{"mo", "we"}}},
		{"bool", []string{"--json", "--no-input=true"}, "", map[string]any{"json": true, "no-input": true}},
		{"unknown flag", []string{"--titel", "x"}, CodeInvalidArgument, nil},
		{"bad enum", []string{"--freq", "yearly"}, CodeInvalidEnum, nil},
		{"count over max", []string{"--count", "1001"}, CodeInvalidArgument, nil},
		{"not an int", []string{"--count", "many"}, CodeInvalidArgument, nil},
		{"missing value", []string{"--titulo"}, CodeInvalidArgument, nil},
		{"positional", []string{"extra"}, CodeInvalidArgument, nil},
		{"duplicate", []string{"--titulo", "a", "--titulo", "b"}, CodeInvalidArgument, nil},
		{"empty list item", []string{"--byday", "mo,,we"}, CodeInvalidArgument, nil},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, err := parseFlags(tc.args, specs)
			if tc.code != "" {
				if err == nil || err.Code != tc.code {
					t.Fatalf("err = %v, want code %s", err, tc.code)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			a, _ := json.Marshal(got.values)
			b, _ := json.Marshal(tc.want)
			if string(a) != string(b) {
				t.Fatalf("values = %s, want %s", a, b)
			}
		})
	}
}

func TestValidateRepeticion(t *testing.T) {
	cases := []struct {
		name  string
		rep   *Repeticion
		field string
	}{
		{"once", nil, ""},
		{"hourly every 8 h count 15", &Repeticion{Freq: "HOURLY", Interval: intp(8), Count: intp(15)}, ""},
		{"count over 1000", &Repeticion{Freq: "DAILY", Count: intp(1001)}, "count"},
		{"interval over 365", &Repeticion{Freq: "DAILY", Interval: intp(366)}, "interval"},
		{"count and until", &Repeticion{Freq: "DAILY", Count: intp(2), Until: "2026-12-01"}, "until"},
		{"bad until", &Repeticion{Freq: "DAILY", Until: "2026-02-30"}, "until"},
		{"bymonthday outside monthly", &Repeticion{Freq: "WEEKLY", Bymonthday: intp(3)}, "bymonthday"},
		{"bymonthday zero", &Repeticion{Freq: "MONTHLY", Bymonthday: intp(0)}, "bymonthday"},
		{"monthly last day", &Repeticion{Freq: "MONTHLY", Bymonthday: intp(-1)}, ""},
		{"monthly needs a day", &Repeticion{Freq: "MONTHLY"}, "bymonthday"},
		{"monthly ordinal", &Repeticion{Freq: "MONTHLY", Byday: []string{"-1FR"}}, ""},
		{"monthly two days", &Repeticion{Freq: "MONTHLY", Byday: []string{"1MO", "2MO"}}, "byday"},
		{"weekly with ordinal", &Repeticion{Freq: "WEEKLY", Byday: []string{"1MO"}}, "byday"},
		{"weekly repeated day", &Repeticion{Freq: "WEEKLY", Byday: []string{"MO", "MO"}}, "byday"},
		{"byday on daily", &Repeticion{Freq: "DAILY", Byday: []string{"MO"}}, "byday"},
		{"byday and bymonthday", &Repeticion{Freq: "MONTHLY", Byday: []string{"1MO"}, Bymonthday: intp(1)}, "bymonthday"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			err := validateRepeticion(tc.rep)
			if tc.field == "" {
				if err != nil {
					t.Fatalf("unexpected error %v", err)
				}
				return
			}
			if err == nil || err.Field != tc.field {
				t.Fatalf("err = %v, want field %s", err, tc.field)
			}
		})
	}
}

func TestValidateSchedule(t *testing.T) {
	loc, _ := time.LoadLocation("America/La_Paz")
	now := time.Date(2026, 11, 24, 14, 0, 0, 0, time.UTC) // 10:00 local
	cases := []struct {
		name  string
		st    AlarmaState
		field string
	}{
		{"once later today", AlarmaState{Hora: "11:00", Fecha: "2026-11-24"}, ""},
		{"once earlier today", AlarmaState{Hora: "09:59", Fecha: "2026-11-24"}, "fecha"},
		{"once yesterday", AlarmaState{Hora: "23:00", Fecha: "2026-11-23"}, "fecha"},
		{"once without fecha", AlarmaState{Hora: "06:00"}, ""},
		{"until before fecha", AlarmaState{Hora: "08:00", Fecha: "2026-12-10", Repeticion: &Repeticion{Freq: "DAILY", Until: "2026-12-01"}}, "until"},
		{"until past", AlarmaState{Hora: "08:00", Repeticion: &Repeticion{Freq: "DAILY", Until: "2026-11-23"}}, "until"},
		{"until today", AlarmaState{Hora: "08:00", Repeticion: &Repeticion{Freq: "DAILY", Until: "2026-11-24"}}, ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			err := validateSchedule(tc.st, now, loc)
			if tc.field == "" {
				if err != nil {
					t.Fatalf("unexpected error %v", err)
				}
				return
			}
			if err == nil || err.Field != tc.field {
				t.Fatalf("err = %v, want field %s", err, tc.field)
			}
		})
	}
}

func TestReanchor(t *testing.T) {
	loc, _ := time.LoadLocation("America/La_Paz")
	cases := []struct {
		name      string
		row       activaRow
		wantFecha string
		wantHora  string
		wantCount *int
	}{
		{
			name:      "hourly takes the hour of the next fire and restantes as count",
			row:       activaRow{AlarmaID: "a1", Titulo: "Agua", Tipo: "otro", Hora: "08:00", Repeticion: &Repeticion{Freq: "HOURLY", Interval: intp(8), Count: intp(15)}, ProximoDisparo: "2026-11-24T20:00:00Z", Restantes: intp(8)},
			wantFecha: "2026-11-24", wantHora: "16:00", wantCount: intp(8),
		},
		{
			name:      "daily keeps hora; local date can differ from the UTC date",
			row:       activaRow{AlarmaID: "a2", Titulo: "Gym", Tipo: "otro", Hora: "22:30", Repeticion: &Repeticion{Freq: "DAILY"}, ProximoDisparo: "2026-11-25T02:30:00Z"},
			wantFecha: "2026-11-24", wantHora: "22:30",
		},
		{
			name:      "once recovers its date",
			row:       activaRow{AlarmaID: "a3", Titulo: "Banco", Tipo: "reunion", Hora: "16:00", Repeticion: nil, ProximoDisparo: "2026-12-01T20:00:00Z"},
			wantFecha: "2026-12-01", wantHora: "16:00",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			st, err := reanchor(tc.row, loc)
			if err != nil {
				t.Fatal(err)
			}
			if st.Fecha != tc.wantFecha || st.Hora != tc.wantHora {
				t.Fatalf("fecha/hora = %s %s, want %s %s", st.Fecha, st.Hora, tc.wantFecha, tc.wantHora)
			}
			if tc.wantCount != nil && (st.Repeticion == nil || st.Repeticion.Count == nil || *st.Repeticion.Count != *tc.wantCount) {
				t.Fatalf("count = %+v, want %d", st.Repeticion, *tc.wantCount)
			}
			if tc.row.Repeticion != nil && tc.row.Repeticion.Count != nil && *tc.row.Repeticion.Count != 15 {
				t.Fatal("reanchor mutated the P1 row")
			}
		})
	}
}

func TestRemedioCorrectionKeepsCalendar(t *testing.T) {
	row := activaRow{AlarmaID: "r1", Titulo: "Amoxi", Tipo: "remedio", Hora: "08:00", Fecha: "2026-11-20",
		Repeticion: &Repeticion{Freq: "HOURLY", Interval: intp(8), Count: intp(15)}, ProximoDisparo: "2026-11-24T20:00:00Z", Restantes: intp(3)}
	adh := adherenciaRow{AlarmaID: "r1", Destinatario: "Ana", Remedio: "Amoxicilina"}
	got := remedioCorrection(row, adh, strp(" Amoxicilina Ana "), nil, strp("Amoxicilina 500 mg"))
	want := AlarmaState{AlarmaID: "r1", Titulo: "Amoxicilina Ana", Tipo: "remedio", Hora: "08:00", Fecha: "2026-11-20",
		Repeticion: &Repeticion{Freq: "HOURLY", Interval: intp(8), Count: intp(15)}, Destinatario: "Ana", Remedio: "Amoxicilina 500 mg"}
	a, _ := json.Marshal(got)
	b, _ := json.Marshal(want)
	if string(a) != string(b) {
		t.Fatalf("correction = %s\nwant %s", a, b)
	}
}

func TestParseBulk(t *testing.T) {
	cases := []struct {
		name   string
		doc    string
		n      int
		errors int
	}{
		{"spec example", `{"formato":"cauce.alarmas","version":1,"alarmas":[
			{"titulo":"Amoxicilina Juan","tipo":"remedio","hora":"08:00","repeticion":{"freq":"HOURLY","interval":8,"count":15},"destinatario":"Juan","remedio":"Amoxicilina"},
			{"titulo":"Reunión de equipo","tipo":"reunion","hora":"10:00","repeticion":{"freq":"WEEKLY","byday":["MO","WE"]}}]}`, 2, 0},
		{"wrong format", `{"formato":"otro","version":1,"alarmas":[]}`, 0, 1},
		{"wrong version", `{"formato":"cauce.alarmas","version":2,"alarmas":[]}`, 0, 1},
		{"not json", `nope`, 0, 1},
		{"empty list", `{"formato":"cauce.alarmas","version":1,"alarmas":[]}`, 0, 1},
		{"remedio without destinatario", `{"formato":"cauce.alarmas","version":1,"alarmas":[{"titulo":"x","tipo":"remedio","hora":"08:00","repeticion":null,"remedio":"y"}]}`, 0, 1},
		{"missing repeticion", `{"formato":"cauce.alarmas","version":1,"alarmas":[{"titulo":"x","tipo":"otro","hora":"08:00"}]}`, 0, 1},
		{"unknown rep field", `{"formato":"cauce.alarmas","version":1,"alarmas":[{"titulo":"x","tipo":"otro","hora":"08:00","repeticion":{"freq":"DAILY","bysetpos":1}}]}`, 0, 1},
		{"count cap", `{"formato":"cauce.alarmas","version":1,"alarmas":[{"titulo":"x","tipo":"otro","hora":"08:00","repeticion":{"freq":"DAILY","count":5000}}]}`, 0, 1},
		{"one bad of two", `{"formato":"cauce.alarmas","version":1,"alarmas":[{"titulo":"x","tipo":"otro","hora":"08:00","repeticion":null},{"titulo":"y","tipo":"otro","hora":"8am","repeticion":null}]}`, 1, 1},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			states, errs := parseBulk([]byte(tc.doc))
			if len(states) != tc.n || len(errs) != tc.errors {
				t.Fatalf("states=%d errors=%v, want %d and %d", len(states), errs, tc.n, tc.errors)
			}
		})
	}
}

func TestFixedFieldsEqual(t *testing.T) {
	a := fixedOf(AlarmaState{Titulo: "x", Tipo: "otro", Hora: "08:00", Repeticion: &Repeticion{Freq: "DAILY", Interval: intp(1)}})
	b := fixedOf(AlarmaState{Titulo: "x", Tipo: "otro", Hora: "08:00", Repeticion: &Repeticion{Freq: "DAILY", Interval: intp(1)}})
	if !a.equal(b) {
		t.Fatal("equal states compare different")
	}
	b.Hora = "09:00"
	if a.equal(b) {
		t.Fatal("different hora compares equal")
	}
}
