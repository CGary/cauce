package cli

import (
	"encoding/json"
	"fmt"
	"io"
	"net/url"
	"os"
	"strconv"
	"strings"

	"github.com/google/uuid"
)

var (
	tipoEnum   = []string{"remedio", "reunion", "otro"}
	freqEnum   = []string{"once", "daily", "weekly", "monthly", "hourly"}
	listFields = []string{"alarma_id", "titulo", "tipo", "hora", "fecha", "repeticion", "proximo_disparo", "restantes"}
)

func tzFlag() flagSpec {
	return flagSpec{Name: "tz", Kind: kindString, Desc: "IANA time zone for local dates and hours (default: config tz, then the system zone)."}
}

func limitFlag() flagSpec {
	return flagSpec{Name: "limit", Kind: kindInt, Min: intp(1), Max: intp(500), Desc: "Maximum items to return (default 50)."}
}

func cursorFlag() flagSpec {
	return flagSpec{Name: "cursor", Kind: kindString, Desc: "next_cursor from a previous page."}
}

// scheduleFlags are the calendar flags of create and edit.
func scheduleFlags() []flagSpec {
	return []flagSpec{
		{Name: "hora", Kind: kindString, Pattern: `^[0-9]{1,2}:[0-5][0-9]$`, Desc: "Local time of the first fire of the day, HH:MM (24 h)."},
		{Name: "fecha", Kind: kindString, Pattern: `^[0-9]{4}-[0-9]{2}-[0-9]{2}$`, Desc: "Local date of the first fire, YYYY-MM-DD. Without it, the first fire is the next hora after now."},
		{Name: "freq", Kind: kindEnum, Enum: freqEnum, Desc: "Repetition. once (default on create) rings one time."},
		{Name: "interval", Kind: kindInt, Min: intp(1), Max: intp(maxInterval), Desc: "Every N days, weeks, months or hours (by freq)."},
		{Name: "byday", Kind: kindList, Desc: "weekly: days mo,tu,we,th,fr,sa,su. monthly: one day with ordinal, e.g. 1mo or -1fr."},
		{Name: "bymonthday", Kind: kindInt, Min: intp(-1), Max: intp(31), Desc: "monthly only: day of month 1..31, or -1 for the last day."},
		{Name: "count", Kind: kindInt, Min: intp(1), Max: intp(maxCount), Desc: "Total number of fires, first included. Excludes --until."},
		{Name: "until", Kind: kindString, Pattern: `^[0-9]{4}-[0-9]{2}-[0-9]{2}$`, Desc: "Last local date (inclusive) with a fire. Excludes --count."},
	}
}

func textFlags() []flagSpec {
	return []flagSpec{
		{Name: "titulo", Kind: kindString, Desc: "Title, 1 to 120 characters."},
		{Name: "tipo", Kind: kindEnum, Enum: tipoEnum, Desc: "Alarm type."},
		{Name: "destinatario", Kind: kindString, Desc: "tipo remedio only: who takes it, 1 to 60 characters."},
		{Name: "remedio", Kind: kindString, Desc: "tipo remedio only: the medicine, 1 to 120 characters."},
	}
}

var repFlagNames = []string{"freq", "interval", "byday", "bymonthday", "count", "until"}

func alarmasCommands() []*command {
	alarmaID := flagSpec{Name: "alarma-id", Kind: kindString, Required: true, Desc: "alarma_id from alarmas list."}
	create := append(textFlags(), scheduleFlags()...)
	for i := range create {
		if create[i].Name == "titulo" || create[i].Name == "tipo" || create[i].Name == "hora" {
			create[i].Required = true
		}
	}
	edit := append([]flagSpec{alarmaID}, append(textFlags(), scheduleFlags()...)...)
	remedioIf := map[string]any{
		"if":   map[string]any{"properties": map[string]any{"tipo": map[string]any{"const": "remedio"}}, "required": []string{"tipo"}},
		"then": map[string]any{"required": []string{"destinatario", "remedio"}},
	}
	return []*command{
		{
			Domain: "alarmas", Verb: "list", Kind: "read",
			Short:       "Active alarms with their next fire (P1), Google meetings included.",
			Description: "Lists the active alarms with their next fire time (projection alarmas_activas), sorted by next fire. Use it to find an alarma_id or to answer what rings next. Google Calendar meetings appear with ids gcal:<reunion_id> and are read-only here; edit their aviso with reuniones aviso. Returns items, count, total, has_more and next_cursor.",
			Flags: []flagSpec{tzFlag(),
				{Name: "tipo", Kind: kindEnum, Enum: tipoEnum, Desc: "Only alarms of this type."},
				limitFlag(), cursorFlag(),
				{Name: "fields", Kind: kindList, Enum: listFields, Desc: "Comma-separated fields per item: " + strings.Join(listFields, ",") + "."}},
			Run: runAlarmasList,
		},
		{
			Domain: "alarmas", Verb: "get", Kind: "read",
			Short:       "Full state of one active alarm (texts, calendar, remedio).",
			Description: "Shows the full state of one active alarm: titulo, tipo, hora, fecha, repeticion and, for a remedio, destinatario and remedio (from adherencia_tratamiento). Use it before an edit to see what will change and whether the calendar is locked. Google meeting ids (gcal:) are rejected; use reuniones list. Returns data.alarma, proximo_disparo, restantes, schedule_locked and editable_fields.",
			Flags:       []flagSpec{alarmaID, tzFlag()},
			Run:         runAlarmasGet,
		},
		{
			Domain: "alarmas", Verb: "create", Kind: "write",
			Short:       "Create an alarm (alarma_creada v3).",
			Description: "Creates one alarm as an alarma_creada v3 event with a new UUID v7 alarma_id and device_id cli. Repetition comes from flags (--freq, --interval, --byday, --bymonthday, --count, --until); without --freq it rings once. Use --dry-run to save a plan and review it, then plan apply; --apply sends at once. A remedio needs --destinatario and --remedio. Returns the planned or sent changes.",
			Flags:       create,
			Constraints: remedioIf,
			Run:         runAlarmasCreate,
		},
		{
			Domain: "alarmas", Verb: "edit", Kind: "write",
			Short:       "Edit an alarm; sends the full new state (alarma_modificada v3).",
			Description: "Edits one active alarm: pass only the flags that change; the CLI reads the current state and sends the complete new state as alarma_modificada v3. A remedio only accepts --titulo, --remedio and --destinatario (REMEDIO_SCHEDULE_LOCKED otherwise). Other alarms are re-anchored on their next fire, so count becomes the remaining fires. With --freq the repetition is rebuilt from the flags given; without it, repetition flags change the current one. Returns the planned or sent changes.",
			Flags:       edit,
			Run:         runAlarmasEdit,
		},
		{
			Domain: "alarmas", Verb: "deactivate", Kind: "write",
			Short:       "Deactivate an alarm (alarma_desactivada, motivo usuario); needs --yes.",
			Description: "Deactivates one active alarm with alarma_desactivada v1, motivo usuario. History is kept, but there is no reactivation: to undo, create a new alarm. --apply also needs --yes; --dry-run saves a plan that plan apply sends with --apply --yes. Google meeting ids (gcal:) are rejected; turn their aviso off with reuniones aviso --apagado. Returns the planned or sent change.",
			Flags:       []flagSpec{alarmaID, tzFlag(), {Name: "yes", Kind: kindBool, Desc: "Confirm the irreversible deactivation (with --apply)."}},
			Run:         runAlarmasDeactivate,
		},
		{
			Domain: "alarmas", Verb: "import", Kind: "write",
			Short:       "Create many alarms from a cauce.alarmas v1 JSON file in one push.",
			Description: "Creates one alarma_creada v3 per item of a cauce.alarmas v1 file (alarmas SPEC section 5, the web bulk-load format), all in one push. Read it from --input <file> or --input - (stdin). Every item is validated first; any invalid item fails the whole import with data.errors and nothing is sent. Returns the planned or sent changes.",
			Flags:       []flagSpec{{Name: "input", Kind: kindString, Required: true, Desc: "Path of the JSON file, or - for stdin."}, tzFlag()},
			Run:         runAlarmasImport,
		},
	}
}

// fetchActivas reads P1 in the resolved zone.
func (c *cmdCtx) fetchActivas() ([]activaRow, *cliError) {
	tz, _, err := c.tz()
	if err != nil {
		return nil, err
	}
	cl, err := c.client()
	if err != nil {
		return nil, err
	}
	var rows []activaRow
	if err := cl.projection(c.ctx, "alarmas", "alarmas_activas", url.Values{"tz": {tz}}, &rows); err != nil {
		return nil, err
	}
	return rows, nil
}

func (c *cmdCtx) fetchAdherencia() ([]adherenciaRow, *cliError) {
	tz, _, err := c.tz()
	if err != nil {
		return nil, err
	}
	cl, err := c.client()
	if err != nil {
		return nil, err
	}
	var rows []adherenciaRow
	if err := cl.projection(c.ctx, "alarmas", "adherencia_tratamiento", url.Values{"tz": {tz}}, &rows); err != nil {
		return nil, err
	}
	return rows, nil
}

// rejectGcal refuses Google meeting ids in alarmas get/edit/deactivate.
func rejectGcal(id string) *cliError {
	if !isGoogleCalendar(id) {
		return nil
	}
	return (&cliError{Code: CodeInvalidArgument, Message: "gcal: ids are Google Calendar meetings; they are not edited from alarmas. Use reuniones list to find the serie_id and reuniones aviso to change or turn off the aviso.", Field: "alarma-id"}).
		fix("cauce-cli reuniones list --json")
}

// findAlarma returns the P1 row and, for a remedio, the P2 row.
func (c *cmdCtx) findAlarma(id string) (*activaRow, *adherenciaRow, *cliError) {
	rows, err := c.fetchActivas()
	if err != nil {
		return nil, nil, err
	}
	var row *activaRow
	for i := range rows {
		if rows[i].AlarmaID == id {
			row = &rows[i]
			break
		}
	}
	if row == nil {
		return nil, nil, (&cliError{Code: CodeNotFound, Message: "no active alarm with this alarma_id (deactivated, finished, or never existed)", Field: "alarma-id"}).
			fix("cauce-cli alarmas list --json")
	}
	if row.Tipo != "remedio" {
		return row, nil, nil
	}
	adh, err := c.fetchAdherencia()
	if err != nil {
		return nil, nil, err
	}
	for i := range adh {
		if adh[i].AlarmaID == id {
			return row, &adh[i], nil
		}
	}
	return nil, nil, errorf(CodeInternal, "the remedio has no row in adherencia_tratamiento; cannot read destinatario and remedio")
}

func runAlarmasList(c *cmdCtx) (*success, error) {
	limit := 50
	if c.flags.has("limit") {
		limit = c.flags.int("limit")
	}
	c.input["limit"] = limit
	offset := 0
	if cur := c.flags.str("cursor"); cur != "" {
		n, err := strconv.Atoi(cur)
		if err != nil || n < 0 {
			return nil, (&cliError{Code: CodeInvalidArgument, Message: "--cursor must be a next_cursor value from a previous page", Field: "cursor", Received: cur})
		}
		offset = n
	}
	fields := c.flags.list("fields")
	for _, f := range fields {
		if !contains(listFields, f) {
			return nil, &cliError{Code: CodeInvalidEnum, Message: "--fields takes: " + strings.Join(listFields, ","), Field: "fields", Received: f, Allowed: listFields}
		}
	}
	rows, err := c.fetchActivas()
	if err != nil {
		return nil, err
	}
	tipo := c.flags.str("tipo")
	var filtered []activaRow
	for _, r := range rows {
		if tipo == "" || r.Tipo == tipo {
			filtered = append(filtered, r)
		}
	}
	total := len(filtered)
	if offset > total {
		offset = total
	}
	end := offset + limit
	if end > total {
		end = total
	}
	page := filtered[offset:end]
	items := make([]map[string]any, 0, len(page))
	for _, r := range page {
		items = append(items, project(r, fields))
	}
	data := map[string]any{"items": items, "count": len(items), "total": total, "has_more": end < total}
	var warnings []string
	var next []NextAction
	if end < total {
		data["next_cursor"] = strconv.Itoa(end)
		warnings = append(warnings, fmt.Sprintf("Showing %d of %d alarms; pass --cursor %d for more.", len(items), total, end))
		next = append(next, NextAction{Command: fmt.Sprintf("cauce-cli alarmas list --cursor %d --json", end), Reason: "Next page."})
	}
	gcal := 0
	for _, r := range filtered {
		if isGoogleCalendar(r.AlarmaID) {
			gcal++
		}
	}
	return &success{
		Summary:  fmt.Sprintf("%d active alarm(s), %d from Google Calendar.", total, gcal),
		Data:     data,
		Warnings: warnings,
		Next:     next,
	}, nil
}

func project(r activaRow, fields []string) map[string]any {
	raw, _ := json.Marshal(r)
	var m map[string]any
	_ = json.Unmarshal(raw, &m)
	if len(fields) == 0 {
		return m
	}
	out := map[string]any{}
	for _, f := range fields {
		if v, ok := m[f]; ok {
			out[f] = v
		}
	}
	return out
}

func contains(list []string, v string) bool {
	for _, x := range list {
		if x == v {
			return true
		}
	}
	return false
}

func runAlarmasGet(c *cmdCtx) (*success, error) {
	id := c.flags.str("alarma-id")
	if err := rejectGcal(id); err != nil {
		return nil, err
	}
	row, adh, err := c.findAlarma(id)
	if err != nil {
		return nil, err
	}
	st := currentState(*row, adh)
	locked := isScheduleLocked(*row)
	editable := []string{"titulo", "tipo", "hora", "fecha", "freq", "interval", "byday", "bymonthday", "count", "until"}
	if locked {
		editable = []string{"titulo", "destinatario", "remedio"}
	}
	data := map[string]any{
		"alarma":          st,
		"proximo_disparo": row.ProximoDisparo,
		"schedule_locked": locked,
		"editable_fields": editable,
	}
	if row.Restantes != nil {
		data["restantes"] = *row.Restantes
	}
	summary := fmt.Sprintf("Active %s alarm; next fire %s.", row.Tipo, row.ProximoDisparo)
	return &success{
		Summary: summary,
		Data:    data,
		Next:    []NextAction{{Command: "cauce-cli alarmas edit --alarma-id " + id + " --dry-run --json", Reason: "Plan an edit (add only the flags that change)."}},
	}, nil
}

// repFromFlags builds a repetition from flags only (create, or edit with --freq).
func repFromFlags(f *parsedFlags) (*Repeticion, *cliError) {
	freq := f.str("freq")
	if freq == "" || freq == "once" {
		for _, n := range repFlagNames[1:] {
			if f.has(n) {
				return nil, errorf(CodeInvalidArgument, "--%s needs --freq daily, weekly, monthly or hourly", n).field(n)
			}
		}
		return nil, nil
	}
	r := &Repeticion{Freq: strings.ToUpper(freq)}
	mergeRep(r, f)
	return r, nil
}

// mergeRep applies the repetition flags over r; count and until replace each
// other, and so do byday and bymonthday.
func mergeRep(r *Repeticion, f *parsedFlags) {
	if f.has("interval") {
		r.Interval = intp(f.int("interval"))
	}
	if f.has("count") {
		r.Count = intp(f.int("count"))
		if !f.has("until") {
			r.Until = ""
		}
	}
	if f.has("until") {
		r.Until = strings.TrimSpace(f.str("until"))
		if !f.has("count") {
			r.Count = nil
		}
	}
	if f.has("byday") {
		r.Byday = parseByday(f.list("byday"))
		if r.Freq == "WEEKLY" {
			r.Byday = sortWeekdays(r.Byday)
		}
		if !f.has("bymonthday") {
			r.Bymonthday = nil
		}
	}
	if f.has("bymonthday") {
		r.Bymonthday = intp(f.int("bymonthday"))
		if !f.has("byday") {
			r.Byday = nil
		}
	}
}

func applyTextFlags(st *AlarmaState, f *parsedFlags) *cliError {
	if f.has("titulo") {
		st.Titulo = strings.TrimSpace(f.str("titulo"))
	}
	if f.has("tipo") {
		st.Tipo = f.str("tipo")
	}
	if f.has("hora") {
		h, ok := normalizeHora(f.str("hora"))
		if !ok {
			return (&cliError{Code: CodeInvalidArgument, Message: "--hora must be HH:MM (24 h)", Field: "hora"})
		}
		st.Hora = h
	}
	if f.has("fecha") {
		st.Fecha = strings.TrimSpace(f.str("fecha"))
	}
	if f.has("destinatario") {
		st.Destinatario = strings.TrimSpace(f.str("destinatario"))
	}
	if f.has("remedio") {
		st.Remedio = strings.TrimSpace(f.str("remedio"))
	}
	return nil
}

func freqLabel(r *Repeticion) string {
	if r == nil {
		return "once"
	}
	return strings.ToLower(r.Freq)
}

func runAlarmasCreate(c *cmdCtx) (*success, error) {
	_, loc, err := c.tz()
	if err != nil {
		return nil, err
	}
	st := AlarmaState{}
	if err := applyTextFlags(&st, c.flags); err != nil {
		return nil, err
	}
	rep, err := repFromFlags(c.flags)
	if err != nil {
		return nil, err
	}
	st.Repeticion = rep
	if err := validateState(st); err != nil {
		return nil, err
	}
	if err := validateSchedule(st, c.now(), loc); err != nil {
		return nil, err
	}
	id, uerr := uuid.NewV7()
	if uerr != nil {
		return nil, errorf(CodeInternal, "cannot generate a UUID v7")
	}
	st.AlarmaID = id.String()
	eid, _ := uuid.NewV7()
	p := &Plan{
		Command: "alarmas.create",
		Events:  []PlanEvent{{ID: eid.String(), Type: "alarma_creada", V: vAlarmaCreada, Payload: st.payload()}},
		Changes: []Change{{Kind: "create", Event: "alarma_creada", AlarmaID: st.AlarmaID}},
	}
	if c.flags.bool("dry-run") {
		return c.dryRun(p, fmt.Sprintf("Would create 1 %s alarm (%s).", st.Tipo, freqLabel(st.Repeticion)))
	}
	return c.send(p, fmt.Sprintf("Created 1 %s alarm (%s).", st.Tipo, freqLabel(st.Repeticion)))
}

// changeFlags are the edit flags that change the state.
var changeFlags = []string{"titulo", "tipo", "hora", "fecha", "destinatario", "remedio", "freq", "interval", "byday", "bymonthday", "count", "until"}

func runAlarmasEdit(c *cmdCtx) (*success, error) {
	id := c.flags.str("alarma-id")
	if err := rejectGcal(id); err != nil {
		return nil, err
	}
	var given []string
	for _, n := range changeFlags {
		if c.flags.has(n) {
			given = append(given, n)
		}
	}
	if len(given) == 0 {
		return nil, (&cliError{Code: CodeMissingRequiredFlag, Message: "nothing to change: pass at least one of --" + strings.Join(changeFlags, ", --"), Field: "titulo"}).
			fix("cauce-cli alarmas get --alarma-id " + id + " --json")
	}
	_, loc, err := c.tz()
	if err != nil {
		return nil, err
	}
	row, adh, err := c.findAlarma(id)
	if err != nil {
		return nil, err
	}
	before := currentState(*row, adh)
	var next AlarmaState
	if isScheduleLocked(*row) {
		for _, n := range given {
			if n != "titulo" && n != "remedio" && n != "destinatario" {
				e := &cliError{
					Code:    CodeRemedioScheduleLock,
					Message: "a remedio alarm is only corrected (--titulo, --remedio, --destinatario); its type and calendar cannot change. Create a new alarm and deactivate this one.",
					Field:   n,
					Allowed: []string{"titulo", "remedio", "destinatario"},
					Fix: &SuggestedFix{
						Command: "cauce-cli alarmas create --tipo remedio --titulo <titulo> --destinatario <destinatario> --remedio <remedio> --hora <HH:MM> --freq hourly --interval <h> --count <n> --dry-run --json",
						Then:    "cauce-cli alarmas deactivate --alarma-id " + id + " --dry-run --json",
					},
				}
				return nil, e
			}
		}
		var t, d, r *string
		if c.flags.has("titulo") {
			t = strp(c.flags.str("titulo"))
		}
		if c.flags.has("destinatario") {
			d = strp(c.flags.str("destinatario"))
		}
		if c.flags.has("remedio") {
			r = strp(c.flags.str("remedio"))
		}
		next = remedioCorrection(*row, *adh, t, d, r)
		if err := validateState(next); err != nil {
			return nil, err
		}
	} else {
		base, rerr := reanchor(*row, loc)
		if rerr != nil {
			return nil, errorf(CodeInternal, "the server returned an invalid proximo_disparo")
		}
		next = base
		if err := applyTextFlags(&next, c.flags); err != nil {
			return nil, err
		}
		if next.Tipo != "remedio" && !c.flags.has("destinatario") && !c.flags.has("remedio") {
			next.Destinatario, next.Remedio = "", ""
		}
		if c.flags.has("freq") {
			rep, err := repFromFlags(c.flags)
			if err != nil {
				return nil, err
			}
			next.Repeticion = rep
		} else if anyFlag(c.flags, repFlagNames[1:]) {
			if next.Repeticion == nil {
				return nil, errorf(CodeInvalidArgument, "this alarm rings once; pass --freq to give it a repetition").field("freq")
			}
			mergeRep(next.Repeticion, c.flags)
		}
		if err := validateState(next); err != nil {
			return nil, err
		}
		if err := validateSchedule(next, c.now(), loc); err != nil {
			return nil, err
		}
	}
	eid, _ := uuid.NewV7()
	p := &Plan{
		Command:      "alarmas.edit",
		Events:       []PlanEvent{{ID: eid.String(), Type: "alarma_modificada", V: vAlarmaModificada, Payload: next.payload()}},
		Changes:      []Change{{Kind: "modify", Event: "alarma_modificada", AlarmaID: id}},
		Precondition: &Precondition{AlarmaID: id, Before: fixedOf(before), After: fixedOf(next)},
	}
	if c.flags.bool("dry-run") {
		res, err := c.dryRun(p, fmt.Sprintf("Would modify 1 %s alarm (%d field(s) given).", next.Tipo, len(given)))
		if err != nil {
			return nil, err
		}
		res.Data.(map[string]any)["before"] = before
		res.Data.(map[string]any)["after"] = next
		return res, nil
	}
	return c.send(p, fmt.Sprintf("Modified 1 %s alarm.", next.Tipo))
}

func strp(s string) *string { return &s }

func anyFlag(f *parsedFlags, names []string) bool {
	for _, n := range names {
		if f.has(n) {
			return true
		}
	}
	return false
}

func runAlarmasDeactivate(c *cmdCtx) (*success, error) {
	id := c.flags.str("alarma-id")
	if err := rejectGcal(id); err != nil {
		return nil, err
	}
	c.input["yes"] = c.flags.bool("yes")
	apply := c.flags.bool("apply")
	if apply && !c.flags.bool("yes") {
		return nil, (&cliError{Code: CodeMissingRequiredFlag, Message: "deactivation cannot be undone: --apply needs --yes too (or preview with --dry-run)", Field: "yes"}).
			fix("cauce-cli alarmas deactivate --alarma-id " + id + " --dry-run --json")
	}
	row, _, err := c.findAlarma(id)
	if err != nil {
		return nil, err
	}
	payload, _ := json.Marshal(map[string]string{"alarma_id": id, "motivo": "usuario"})
	eid, _ := uuid.NewV7()
	p := &Plan{
		Command:      "alarmas.deactivate",
		Irreversible: true,
		Events:       []PlanEvent{{ID: eid.String(), Type: "alarma_desactivada", V: vAlarmaDesactivada, Payload: payload}},
		Changes:      []Change{{Kind: "deactivate", Event: "alarma_desactivada", AlarmaID: id}},
	}
	if c.flags.bool("dry-run") {
		return c.dryRun(p, fmt.Sprintf("Would deactivate 1 %s alarm.", row.Tipo))
	}
	return c.send(p, fmt.Sprintf("Deactivated 1 %s alarm.", row.Tipo))
}

// bulkError is one invalid item of an import.
type bulkError struct {
	Index   int    `json:"index"`
	Field   string `json:"field,omitempty"`
	Message string `json:"message"`
}

func runAlarmasImport(c *cmdCtx) (*success, error) {
	_, loc, err := c.tz()
	if err != nil {
		return nil, err
	}
	in := c.flags.str("input")
	var raw []byte
	if in == "-" {
		if c.flags.bool("no-input") && c.env.StdinIsTerminal() {
			return nil, (&cliError{Code: CodeInputRequired, Message: "--input - reads stdin, but stdin is a terminal and --no-input is set; pipe the file or pass --input <path>", Field: "input"})
		}
		raw, _ = io.ReadAll(io.LimitReader(c.env.Stdin, 16<<20))
	} else {
		path := absPath(in)
		c.input["input"] = path
		data, rerr := os.ReadFile(path)
		if rerr != nil {
			return nil, errorf(CodeFileNotFound, "cannot read the input file %s", path).field("input")
		}
		raw = data
	}
	states, errs := parseBulk(raw)
	if len(errs) == 0 {
		for i := range states {
			if e := validateSchedule(states[i], c.now(), loc); e != nil {
				errs = append(errs, bulkError{Index: i + 1, Field: e.Field, Message: e.Message})
			}
		}
	}
	if len(errs) > 0 {
		e := errorf(CodeInvalidArgument, "the import has %d invalid item(s); nothing was sent; see data.errors", len(errs)).field("input")
		e.Data = map[string]any{"errors": errs}
		return nil, e
	}
	p := &Plan{Command: "alarmas.import"}
	for i := range states {
		aid, _ := uuid.NewV7()
		eid, _ := uuid.NewV7()
		states[i].AlarmaID = aid.String()
		p.Events = append(p.Events, PlanEvent{ID: eid.String(), Type: "alarma_creada", V: vAlarmaCreada, Payload: states[i].payload()})
		p.Changes = append(p.Changes, Change{Kind: "create", Event: "alarma_creada", AlarmaID: states[i].AlarmaID})
	}
	if c.flags.bool("dry-run") {
		return c.dryRun(p, fmt.Sprintf("Would create %d alarm(s) in one push.", len(states)))
	}
	return c.send(p, fmt.Sprintf("Created %d alarm(s) in one push.", len(states)))
}

// parseBulk mirrors parseBulk/parseBulkEntry/parseRepeticion of
// web/src/features/alarmas/model.ts for the cauce.alarmas v1 format.
func parseBulk(raw []byte) ([]AlarmaState, []bulkError) {
	var doc map[string]json.RawMessage
	if err := json.Unmarshal(raw, &doc); err != nil {
		return nil, []bulkError{{Index: 0, Message: "the file is not a JSON object"}}
	}
	var formato string
	var version int
	_ = json.Unmarshal(doc["formato"], &formato)
	_ = json.Unmarshal(doc["version"], &version)
	if formato != "cauce.alarmas" {
		return nil, []bulkError{{Index: 0, Field: "formato", Message: "formato must be cauce.alarmas"}}
	}
	if version != 1 {
		return nil, []bulkError{{Index: 0, Field: "version", Message: "version must be 1"}}
	}
	var items []json.RawMessage
	if err := json.Unmarshal(doc["alarmas"], &items); err != nil || doc["alarmas"] == nil {
		return nil, []bulkError{{Index: 0, Field: "alarmas", Message: "alarmas must be a list"}}
	}
	if len(items) == 0 {
		return nil, []bulkError{{Index: 0, Field: "alarmas", Message: "alarmas is empty"}}
	}
	var out []AlarmaState
	var errs []bulkError
	for i, item := range items {
		st, e := parseBulkEntry(item)
		if e != nil {
			errs = append(errs, bulkError{Index: i + 1, Field: e.Field, Message: e.Message})
			continue
		}
		out = append(out, st)
	}
	return out, errs
}

func parseBulkEntry(raw json.RawMessage) (AlarmaState, *cliError) {
	var m map[string]json.RawMessage
	if err := json.Unmarshal(raw, &m); err != nil || m == nil {
		return AlarmaState{}, errorf(CodeInvalidArgument, "item is not an object")
	}
	var st AlarmaState
	str := func(key string) (string, bool) {
		v, ok := m[key]
		if !ok {
			return "", false
		}
		var s string
		if json.Unmarshal(v, &s) != nil {
			return "", false
		}
		return s, true
	}
	titulo, ok := str("titulo")
	if !ok || strings.TrimSpace(titulo) == "" || len([]rune(titulo)) > 120 {
		return st, errorf(CodeInvalidArgument, "titulo is invalid").field("titulo")
	}
	tipo, ok := str("tipo")
	if !ok || !contains(tipoEnum, tipo) {
		return st, errorf(CodeInvalidArgument, "tipo is invalid").field("tipo")
	}
	hora, ok := str("hora")
	if !ok || !horaRE.MatchString(hora) {
		return st, errorf(CodeInvalidArgument, "hora is invalid").field("hora")
	}
	repRaw, ok := m["repeticion"]
	if !ok {
		return st, errorf(CodeInvalidArgument, "repeticion is missing").field("repeticion")
	}
	rep, e := parseBulkRep(repRaw)
	if e != nil {
		return st, e
	}
	st = AlarmaState{Titulo: strings.TrimSpace(titulo), Tipo: tipo, Hora: hora, Repeticion: rep}
	if _, has := m["fecha"]; has {
		fecha, ok := str("fecha")
		if !ok || !isDate(fecha) {
			return st, errorf(CodeInvalidArgument, "fecha is invalid").field("fecha")
		}
		st.Fecha = fecha
	}
	if tipo == "remedio" {
		d, ok := str("destinatario")
		if !ok || len([]rune(d)) < 1 || len([]rune(d)) > 60 {
			return st, errorf(CodeInvalidArgument, "destinatario is missing").field("destinatario")
		}
		r, ok := str("remedio")
		if !ok || len([]rune(r)) < 1 || len([]rune(r)) > 120 {
			return st, errorf(CodeInvalidArgument, "remedio is missing").field("remedio")
		}
		st.Destinatario, st.Remedio = d, r
	}
	if err := validateState(st); err != nil {
		return st, err
	}
	return st, nil
}

func parseBulkRep(raw json.RawMessage) (*Repeticion, *cliError) {
	if strings.TrimSpace(string(raw)) == "null" {
		return nil, nil
	}
	var m map[string]json.RawMessage
	if err := json.Unmarshal(raw, &m); err != nil || m == nil {
		return nil, errorf(CodeInvalidArgument, "repeticion is invalid").field("repeticion")
	}
	for k := range m {
		if !contains([]string{"freq", "interval", "byday", "bymonthday", "count", "until"}, k) {
			return nil, errorf(CodeInvalidArgument, "repeticion has an unsupported field %s", k).field("repeticion")
		}
	}
	dec := json.NewDecoder(strings.NewReader(string(raw)))
	dec.DisallowUnknownFields()
	var r Repeticion
	if err := dec.Decode(&r); err != nil {
		return nil, errorf(CodeInvalidArgument, "repeticion has a field of the wrong type").field("repeticion")
	}
	if _, has := m["byday"]; has && len(r.Byday) == 0 {
		return nil, errorf(CodeInvalidArgument, "byday must have at least one day").field("byday")
	}
	if err := validateRepeticion(&r); err != nil {
		return nil, err
	}
	return &r, nil
}
