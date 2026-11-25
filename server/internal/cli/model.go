package cli

import (
	"encoding/json"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// Event versions the CLI produces (alarmas SPEC section 3, calendario_google SPEC section 3).
const (
	vAlarmaCreada      = 3
	vAlarmaModificada  = 3
	vAlarmaDesactivada = 1
	vAvisoReunion      = 1
)

// Limits the schema does not express; the web form applies the same ones
// (alarmas SPEC section 8; calendario_google SPEC section 8).
const (
	maxCount        = 1000
	maxInterval     = 365
	maxMinutosAntes = 40320
	gcalPrefix      = "gcal:"
)

var (
	horaRE    = regexp.MustCompile(`^([01][0-9]|2[0-3]):[0-5][0-9]$`)
	dateRE    = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}$`)
	weekdayRE = regexp.MustCompile(`^(MO|TU|WE|TH|FR|SA|SU)$`)
	ordinalRE = regexp.MustCompile(`^(-1|[1-5])(MO|TU|WE|TH|FR|SA|SU)$`)
	weekdays  = []string{"MO", "TU", "WE", "TH", "FR", "SA", "SU"}
)

// Repeticion is the RRULE subset of alarma_creada.v3 / alarma_modificada.v3.
type Repeticion struct {
	Freq       string   `json:"freq"`
	Interval   *int     `json:"interval,omitempty"`
	Byday      []string `json:"byday,omitempty"`
	Bymonthday *int     `json:"bymonthday,omitempty"`
	Count      *int     `json:"count,omitempty"`
	Until      string   `json:"until,omitempty"`
}

func (r *Repeticion) clone() *Repeticion {
	if r == nil {
		return nil
	}
	c := *r
	if r.Interval != nil {
		c.Interval = intp(*r.Interval)
	}
	if r.Bymonthday != nil {
		c.Bymonthday = intp(*r.Bymonthday)
	}
	if r.Count != nil {
		c.Count = intp(*r.Count)
	}
	if r.Byday != nil {
		c.Byday = append([]string{}, r.Byday...)
	}
	return &c
}

// AlarmaState is the full state carried by alarma_creada / alarma_modificada.
type AlarmaState struct {
	AlarmaID     string      `json:"alarma_id"`
	Titulo       string      `json:"titulo"`
	Tipo         string      `json:"tipo"`
	Hora         string      `json:"hora"`
	Fecha        string      `json:"fecha,omitempty"`
	Repeticion   *Repeticion `json:"repeticion"`
	Destinatario string      `json:"destinatario,omitempty"`
	Remedio      string      `json:"remedio,omitempty"`
}

// activaRow is one row of P1 alarmas.alarmas_activas.
type activaRow struct {
	AlarmaID       string      `json:"alarma_id"`
	Titulo         string      `json:"titulo"`
	Tipo           string      `json:"tipo"`
	Hora           string      `json:"hora"`
	Fecha          string      `json:"fecha,omitempty"`
	Repeticion     *Repeticion `json:"repeticion"`
	ProximoDisparo string      `json:"proximo_disparo"`
	Restantes      *int        `json:"restantes,omitempty"`
}

// adherenciaRow is one row of P2 alarmas.adherencia_tratamiento.
type adherenciaRow struct {
	AlarmaID       string  `json:"alarma_id"`
	Destinatario   string  `json:"destinatario"`
	Remedio        string  `json:"remedio"`
	DosisEsperadas int     `json:"dosis_esperadas"`
	DosisDadas     int     `json:"dosis_dadas"`
	Ignoradas      int     `json:"ignoradas"`
	UltimaDosis    *string `json:"ultima_dosis"`
}

func isGoogleCalendar(id string) bool { return strings.HasPrefix(id, gcalPrefix) }

// isScheduleLocked mirrors web/src/features/alarmas/model.ts: a remedio is
// only corrected (titulo, remedio, destinatario), never rescheduled (SPEC 2.2).
func isScheduleLocked(row activaRow) bool {
	return row.Tipo == "remedio" && !isGoogleCalendar(row.AlarmaID)
}

// currentState is the vigente state as the projections show it: P1 for the
// calendar and P2 for destinatario and remedio (P1 does not carry them), the
// same way web/src/features/alarmas/views/ActivasView.tsx builds the editor.
func currentState(row activaRow, adh *adherenciaRow) AlarmaState {
	st := AlarmaState{
		AlarmaID:   row.AlarmaID,
		Titulo:     row.Titulo,
		Tipo:       row.Tipo,
		Hora:       row.Hora,
		Fecha:      row.Fecha,
		Repeticion: row.Repeticion.clone(),
	}
	if row.Tipo == "remedio" && adh != nil {
		st.Destinatario = adh.Destinatario
		st.Remedio = adh.Remedio
	}
	return st
}

// remedioCorrection mirrors remedioCorrectionPayload: corrected texts and the
// calendar exactly as P1 shows it (same fecha or none, same hora, same
// repeticion with its original count), so P1 and P2 do not change.
func remedioCorrection(row activaRow, adh adherenciaRow, titulo, destinatario, remedio *string) AlarmaState {
	st := AlarmaState{
		AlarmaID:     row.AlarmaID,
		Titulo:       row.Titulo,
		Tipo:         "remedio",
		Hora:         row.Hora,
		Fecha:        row.Fecha,
		Repeticion:   row.Repeticion.clone(),
		Destinatario: adh.Destinatario,
		Remedio:      adh.Remedio,
	}
	if titulo != nil {
		st.Titulo = strings.TrimSpace(*titulo)
	}
	if destinatario != nil {
		st.Destinatario = strings.TrimSpace(*destinatario)
	}
	if remedio != nil {
		st.Remedio = strings.TrimSpace(*remedio)
	}
	return st
}

// reanchor mirrors activaToForm for an alarm that is not a remedio (alarmas
// SPEC section 8): fecha is the local date of proximo_disparo, an HOURLY
// takes that fire's local hora, and count becomes restantes, so editing
// never repeats fires that already happened.
func reanchor(row activaRow, loc *time.Location) (AlarmaState, error) {
	st := currentState(row, nil)
	next, err := time.Parse(time.RFC3339, row.ProximoDisparo)
	if err != nil {
		return st, err
	}
	local := next.In(loc)
	st.Fecha = local.Format("2006-01-02")
	if st.Repeticion != nil {
		if st.Repeticion.Freq == "HOURLY" {
			st.Hora = local.Format("15:04")
		}
		if st.Repeticion.Count != nil && row.Restantes != nil {
			st.Repeticion.Count = intp(*row.Restantes)
		}
	}
	return st, nil
}

// fixedFields are the fields the conflict check compares: titulo, tipo, hora,
// fecha, repeticion, destinatario and remedio (projections carry no version).
type fixedFields struct {
	Titulo       string      `json:"titulo"`
	Tipo         string      `json:"tipo"`
	Hora         string      `json:"hora"`
	Fecha        string      `json:"fecha,omitempty"`
	Repeticion   *Repeticion `json:"repeticion"`
	Destinatario string      `json:"destinatario,omitempty"`
	Remedio      string      `json:"remedio,omitempty"`
}

func fixedOf(st AlarmaState) fixedFields {
	f := fixedFields{Titulo: st.Titulo, Tipo: st.Tipo, Hora: st.Hora, Fecha: st.Fecha, Repeticion: st.Repeticion.clone()}
	if st.Tipo == "remedio" {
		f.Destinatario, f.Remedio = st.Destinatario, st.Remedio
	}
	return f
}

func (f fixedFields) equal(o fixedFields) bool {
	a, _ := json.Marshal(f)
	b, _ := json.Marshal(o)
	return string(a) == string(b)
}

func normalizeHora(value string) (string, bool) {
	v := strings.TrimSpace(value)
	hh, mm, ok := strings.Cut(v, ":")
	if !ok || len(hh) < 1 || len(hh) > 2 || len(mm) != 2 {
		return "", false
	}
	if len(hh) == 1 {
		hh = "0" + hh
	}
	h := hh + ":" + mm
	return h, horaRE.MatchString(h)
}

func isDate(value string) bool {
	if !dateRE.MatchString(value) {
		return false
	}
	_, err := time.Parse("2006-01-02", value)
	return err == nil
}

// validateRepeticion checks the RRULE subset with precise, flag-named errors.
// The embedded schema checks the same shape again before sending.
func validateRepeticion(r *Repeticion) *cliError {
	if r == nil {
		return nil
	}
	switch r.Freq {
	case "DAILY", "WEEKLY", "MONTHLY", "HOURLY":
	default:
		return &cliError{Code: CodeInvalidEnum, Message: "freq must be one of: daily, weekly, monthly, hourly", Field: "freq", Received: r.Freq}
	}
	if r.Interval != nil && (*r.Interval < 1 || *r.Interval > maxInterval) {
		return errorf(CodeInvalidArgument, "interval must be an integer between 1 and %d", maxInterval).field("interval")
	}
	if r.Count != nil && (*r.Count < 1 || *r.Count > maxCount) {
		return errorf(CodeInvalidArgument, "count must be an integer between 1 and %d", maxCount).field("count")
	}
	if r.Count != nil && r.Until != "" {
		return errorf(CodeInvalidArgument, "count and until are mutually exclusive").field("until")
	}
	if r.Until != "" && !isDate(r.Until) {
		return errorf(CodeInvalidArgument, "until must be a date YYYY-MM-DD").field("until")
	}
	if r.Bymonthday != nil && len(r.Byday) > 0 {
		return errorf(CodeInvalidArgument, "byday and bymonthday are mutually exclusive").field("bymonthday")
	}
	if r.Bymonthday != nil {
		if r.Freq != "MONTHLY" {
			return errorf(CodeInvalidArgument, "bymonthday is only valid with freq monthly").field("bymonthday")
		}
		if d := *r.Bymonthday; d == 0 || d < -1 || d > 31 {
			return errorf(CodeInvalidArgument, "bymonthday must be 1..31 or -1 (last day)").field("bymonthday")
		}
	}
	if len(r.Byday) > 0 {
		seen := map[string]bool{}
		for _, d := range r.Byday {
			if seen[d] {
				return errorf(CodeInvalidArgument, "byday has repeated days").field("byday")
			}
			seen[d] = true
		}
		switch r.Freq {
		case "WEEKLY":
			for _, d := range r.Byday {
				if !weekdayRE.MatchString(d) {
					return (&cliError{Code: CodeInvalidArgument, Message: "weekly byday takes days without ordinal: mo,tu,we,th,fr,sa,su", Field: "byday", Allowed: []string{"mo", "tu", "we", "th", "fr", "sa", "su"}})
				}
			}
		case "MONTHLY":
			if len(r.Byday) != 1 || !ordinalRE.MatchString(r.Byday[0]) {
				return errorf(CodeInvalidArgument, "monthly byday takes exactly one day with ordinal, e.g. 1mo or -1fr").field("byday")
			}
		default:
			return errorf(CodeInvalidArgument, "byday is only valid with freq weekly or monthly").field("byday")
		}
	}
	if r.Freq == "MONTHLY" && len(r.Byday) == 0 && r.Bymonthday == nil {
		return errorf(CodeMissingRequiredFlag, "freq monthly needs --bymonthday or --byday with ordinal").field("bymonthday")
	}
	return nil
}

// validateSchedule applies the checks of the web form that the schema cannot
// express, with the CLI clock in the given zone: a one-time alarm cannot be in
// the past, and until cannot be before the start or before today.
func validateSchedule(st AlarmaState, now time.Time, loc *time.Location) *cliError {
	local := now.In(loc)
	today := local.Format("2006-01-02")
	if st.Repeticion == nil {
		if st.Fecha != "" && st.Fecha+"T"+st.Hora < today+"T"+local.Format("15:04") {
			return errorf(CodeInvalidArgument, "the date and time are already past: a one-time alarm would never ring").field("fecha")
		}
		return nil
	}
	if u := st.Repeticion.Until; u != "" {
		if st.Fecha != "" && u < st.Fecha {
			return errorf(CodeInvalidArgument, "until cannot be before the start date (fecha)").field("until")
		}
		if u < today {
			return errorf(CodeInvalidArgument, "until is already past: the alarm would never ring").field("until")
		}
	}
	return nil
}

// validateState checks texts, hora, fecha, and the schedule of a full state.
func validateState(st AlarmaState) *cliError {
	if n := len([]rune(st.Titulo)); n < 1 || n > 120 {
		return errorf(CodeInvalidArgument, "titulo must have between 1 and 120 characters").field("titulo")
	}
	switch st.Tipo {
	case "remedio", "reunion", "otro":
	default:
		return &cliError{Code: CodeInvalidEnum, Message: "tipo must be one of: remedio, reunion, otro", Field: "tipo", Allowed: []string{"remedio", "reunion", "otro"}}
	}
	if !horaRE.MatchString(st.Hora) {
		return errorf(CodeInvalidArgument, "hora must be HH:MM (24 h)").field("hora")
	}
	if st.Fecha != "" && !isDate(st.Fecha) {
		return errorf(CodeInvalidArgument, "fecha must be a date YYYY-MM-DD").field("fecha")
	}
	if st.Tipo == "remedio" {
		if n := len([]rune(st.Destinatario)); n < 1 || n > 60 {
			return errorf(CodeMissingRequiredFlag, "a remedio needs --destinatario with 1 to 60 characters").field("destinatario")
		}
		if n := len([]rune(st.Remedio)); n < 1 || n > 120 {
			return errorf(CodeMissingRequiredFlag, "a remedio needs --remedio with 1 to 120 characters").field("remedio")
		}
	} else if st.Destinatario != "" || st.Remedio != "" {
		return errorf(CodeInvalidArgument, "--destinatario and --remedio are only valid with tipo remedio").field("destinatario")
	}
	return validateRepeticion(st.Repeticion)
}

// payload is the event payload of a full state.
func (st AlarmaState) payload() json.RawMessage {
	raw, _ := json.Marshal(st)
	return raw
}

// parseByday turns "mo,we" or "1mo" into upper-case RRULE days.
func parseByday(items []string) []string {
	out := make([]string, 0, len(items))
	for _, it := range items {
		out = append(out, strings.ToUpper(strings.TrimSpace(it)))
	}
	return out
}

// sortWeekdays keeps weekly byday in MO..SU order, like the web form.
func sortWeekdays(days []string) []string {
	var out []string
	for _, w := range weekdays {
		for _, d := range days {
			if d == w {
				out = append(out, d)
			}
		}
	}
	if len(out) != len(days) {
		return days
	}
	return out
}

func itoa(n int) string { return strconv.Itoa(n) }
