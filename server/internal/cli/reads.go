package cli

import (
	"encoding/json"
	"fmt"
	"net/url"
	"strings"

	"github.com/google/uuid"
)

func serverCommands() []*command {
	return []*command{{
		Domain: "server", Verb: "health", Kind: "read",
		Short:       "Is the server up, and does the API token work?",
		Description: "Checks that the server answers GET /healthz and that the API token is accepted by reading one projection. Use it first when any other command fails with NETWORK_ERROR or PERMISSION_DENIED. It changes nothing. Returns base_url, healthz and auth; fails with NETWORK_ERROR (retryable) or PERMISSION_DENIED.",
		Flags:       []flagSpec{tzFlag()},
		Run:         runServerHealth,
	}}
}

func runServerHealth(c *cmdCtx) (*success, error) {
	cfg, err := c.config()
	if err != nil {
		return nil, err
	}
	cl, err := c.client()
	if err != nil {
		return nil, err
	}
	c.input["base_url"] = cfg.BaseURL
	if err := cl.healthz(c.ctx); err != nil {
		return nil, err
	}
	if _, err := c.fetchActivas(); err != nil {
		return nil, err
	}
	return &success{
		Summary: "Server is up and the API token is accepted.",
		Data:    map[string]any{"base_url": cfg.BaseURL, "healthz": "ok", "auth": "ok", "user_id": cfg.UserID},
	}, nil
}

func readCommands() []*command {
	return []*command{
		{
			Domain: "adherencia", Verb: "list", Kind: "read",
			Short:       "Per remedio: expected, given and ignored doses (P2).",
			Description: "Lists, for every remedio alarm (deactivated ones included), the doses expected so far, the doses given and the ones ignored (projection adherencia_tratamiento). Use it to answer how a treatment is going or how many doses someone missed. --destinatario filters by exact name on the client. Returns items with alarma_id, destinatario, remedio, dosis_esperadas, dosis_dadas, ignoradas and ultima_dosis.",
			Flags: []flagSpec{tzFlag(),
				{Name: "destinatario", Kind: kindString, Desc: "Only rows of this destinatario (exact match)."},
				limitFlag()},
			Run: runAdherenciaList,
		},
		{
			Domain: "dosis", Verb: "history", Kind: "read",
			Short:       "Doses given, newest first (P3).",
			Description: "Lists the doses given, newest first, one per scheduled fire (projection historial_dosis). Use it to see when a medicine was last given. --destinatario filters on the server. Returns items with ts, destinatario and remedio.",
			Flags: []flagSpec{
				{Name: "destinatario", Kind: kindString, Desc: "Only doses of this destinatario."},
				limitFlag()},
			Run: runDosisHistory,
		},
	}
}

func limitOf(c *cmdCtx) int {
	limit := 50
	if c.flags.has("limit") {
		limit = c.flags.int("limit")
	}
	c.input["limit"] = limit
	return limit
}

func page[T any](items []T, limit int) ([]T, map[string]any, []string) {
	total := len(items)
	shown := items
	if len(shown) > limit {
		shown = shown[:limit]
	}
	if shown == nil {
		shown = []T{}
	}
	data := map[string]any{"items": shown, "count": len(shown), "total": total, "has_more": total > len(shown)}
	var warnings []string
	if total > len(shown) {
		warnings = append(warnings, fmt.Sprintf("Showing %d of %d; raise --limit for more.", len(shown), total))
	}
	return shown, data, warnings
}

func runAdherenciaList(c *cmdCtx) (*success, error) {
	limit := limitOf(c)
	rows, err := c.fetchAdherencia()
	if err != nil {
		return nil, err
	}
	if c.flags.has("destinatario") {
		want := strings.TrimSpace(c.flags.str("destinatario"))
		var kept []adherenciaRow
		for _, r := range rows {
			if r.Destinatario == want {
				kept = append(kept, r)
			}
		}
		rows = kept
	}
	shown, data, warnings := page(rows, limit)
	esperadas, dadas, ignoradas := 0, 0, 0
	for _, r := range rows {
		esperadas += r.DosisEsperadas
		dadas += r.DosisDadas
		ignoradas += r.Ignoradas
	}
	data["totals"] = map[string]int{"dosis_esperadas": esperadas, "dosis_dadas": dadas, "ignoradas": ignoradas}
	_ = shown
	return &success{
		Summary:  fmt.Sprintf("%d remedio alarm(s): %d of %d expected doses given, %d ignored.", len(rows), dadas, esperadas, ignoradas),
		Data:     data,
		Warnings: warnings,
	}, nil
}

type dosisRow struct {
	TS           string `json:"ts"`
	Destinatario string `json:"destinatario"`
	Remedio      string `json:"remedio"`
}

func runDosisHistory(c *cmdCtx) (*success, error) {
	limit := limitOf(c)
	cl, err := c.client()
	if err != nil {
		return nil, err
	}
	params := url.Values{}
	if c.flags.has("destinatario") {
		params.Set("destinatario", strings.TrimSpace(c.flags.str("destinatario")))
	}
	var rows []dosisRow
	if err := cl.projection(c.ctx, "alarmas", "historial_dosis", params, &rows); err != nil {
		return nil, err
	}
	_, data, warnings := page(rows, limit)
	return &success{Summary: fmt.Sprintf("%d dose(s) given.", len(rows)), Data: data, Warnings: warnings}, nil
}

// reunionRow is one row of calendario_google.reuniones_proximas.
type reunionRow struct {
	ReunionID string          `json:"reunion_id"`
	SerieID   string          `json:"serie_id"`
	Cuenta    string          `json:"cuenta"`
	Titulo    string          `json:"titulo"`
	Inicio    string          `json:"inicio"`
	Fin       string          `json:"fin"`
	TodoElDia bool            `json:"todo_el_dia"`
	Aviso     json.RawMessage `json:"aviso"`
	AvisoEn   *string         `json:"aviso_en"`
}

func reunionesCommands() []*command {
	return []*command{
		{
			Domain: "reuniones", Verb: "list", Kind: "read",
			Short:       "Upcoming Google meetings with their aviso (calendario_google P1).",
			Description: "Lists the upcoming Google Calendar meetings the phone synced, with the aviso in force and when it rings (projection reuniones_proximas). Use it to find the serie_id needed by reuniones aviso. Meetings are read-only: cauce never writes to Google. Returns items with reunion_id, serie_id, cuenta, titulo, inicio, fin, todo_el_dia, aviso and aviso_en.",
			Flags:       []flagSpec{tzFlag(), limitFlag()},
			Run:         runReunionesList,
		},
		{
			Domain: "reuniones", Verb: "aviso", Kind: "write",
			Short:       "Set the aviso of a meeting series (aviso_reunion_editado v1).",
			Description: "Sets the aviso of a whole Google meeting series with aviso_reunion_editado v1: --minutos-antes N (0..40320) for timed meetings, --hora HH:MM for all-day meetings, or --apagado to silence it. The edit wins over the Google reminder for this and later occurrences. Use --dry-run to save a plan, then plan apply. Returns the planned or sent change.",
			Flags: []flagSpec{
				{Name: "serie-id", Kind: kindString, Required: true, Desc: "serie_id from reuniones list."},
				{Name: "minutos-antes", Kind: kindInt, Min: intp(0), Max: intp(maxMinutosAntes), Desc: "Minutes before the start (timed meetings)."},
				{Name: "hora", Kind: kindString, Pattern: `^[0-9]{1,2}:[0-5][0-9]$`, Desc: "Local time of the aviso (all-day meetings), HH:MM."},
				{Name: "apagado", Kind: kindBool, Desc: "Turn the aviso off for the series."},
				tzFlag(),
			},
			Constraints: map[string]any{"oneOf": []any{
				map[string]any{"required": []string{"minutos-antes"}, "not": map[string]any{"anyOf": []any{map[string]any{"required": []string{"hora"}}, map[string]any{"required": []string{"apagado"}}}}},
				map[string]any{"required": []string{"hora"}, "not": map[string]any{"anyOf": []any{map[string]any{"required": []string{"minutos-antes"}}, map[string]any{"required": []string{"apagado"}}}}},
				map[string]any{"required": []string{"apagado"}, "not": map[string]any{"anyOf": []any{map[string]any{"required": []string{"minutos-antes"}}, map[string]any{"required": []string{"hora"}}}}},
			}},
			Run: runReunionesAviso,
		},
	}
}

func (c *cmdCtx) fetchReuniones() ([]reunionRow, *cliError) {
	tz, _, err := c.tz()
	if err != nil {
		return nil, err
	}
	cl, err := c.client()
	if err != nil {
		return nil, err
	}
	var rows []reunionRow
	if err := cl.projection(c.ctx, "calendario_google", "reuniones_proximas", url.Values{"tz": {tz}}, &rows); err != nil {
		return nil, err
	}
	return rows, nil
}

func runReunionesList(c *cmdCtx) (*success, error) {
	limit := limitOf(c)
	rows, err := c.fetchReuniones()
	if err != nil {
		return nil, err
	}
	_, data, warnings := page(rows, limit)
	return &success{Summary: fmt.Sprintf("%d upcoming meeting(s).", len(rows)), Data: data, Warnings: warnings}, nil
}

func runReunionesAviso(c *cmdCtx) (*success, error) {
	serie := strings.TrimSpace(c.flags.str("serie-id"))
	n := 0
	for _, f := range []string{"minutos-antes", "hora", "apagado"} {
		if c.flags.has(f) && (f != "apagado" || c.flags.bool(f)) {
			n++
		}
	}
	if n != 1 {
		return nil, (&cliError{Code: CodeMissingRequiredFlag, Message: "pass exactly one of --minutos-antes, --hora or --apagado", Field: "minutos-antes", Allowed: []string{"minutos-antes", "hora", "apagado"}}).
			fix("cauce-cli reuniones aviso --schema")
	}
	payload := map[string]any{"serie_id": serie}
	mode := ""
	switch {
	case c.flags.has("minutos-antes"):
		payload["minutos_antes"] = c.flags.int("minutos-antes")
		mode = "minutos_antes"
	case c.flags.has("hora"):
		h, ok := normalizeHora(c.flags.str("hora"))
		if !ok {
			return nil, (&cliError{Code: CodeInvalidArgument, Message: "--hora must be HH:MM (24 h)", Field: "hora"})
		}
		payload["hora"] = h
		mode = "hora"
	default:
		payload["apagado"] = true
		mode = "apagado"
	}
	rows, err := c.fetchReuniones()
	if err != nil {
		return nil, err
	}
	var series []reunionRow
	for _, r := range rows {
		if r.SerieID == serie {
			series = append(series, r)
		}
	}
	if len(series) == 0 {
		return nil, (&cliError{Code: CodeNotFound, Message: "no upcoming meeting has this serie_id", Field: "serie-id"}).fix("cauce-cli reuniones list --json")
	}
	var warnings []string
	allDay := series[0].TodoElDia
	if mode == "minutos_antes" && allDay {
		warnings = append(warnings, "This is an all-day meeting: --hora is the usual aviso; minutos_antes counts from midnight UTC of its day.")
	}
	if mode == "hora" && !allDay {
		warnings = append(warnings, "This meeting has a start time: --minutos-antes is the usual aviso; --hora rings at that local time on its day.")
	}
	raw, _ := json.Marshal(payload)
	eid, _ := uuid.NewV7()
	p := &Plan{
		Command: "reuniones.aviso",
		Events:  []PlanEvent{{ID: eid.String(), Type: "aviso_reunion_editado", V: vAvisoReunion, Payload: raw}},
		Changes: []Change{{Kind: "modify", Event: "aviso_reunion_editado", SerieID: serie}},
	}
	var res *success
	var serr error
	if c.flags.bool("dry-run") {
		res, serr = c.dryRun(p, fmt.Sprintf("Would set the aviso (%s) of 1 meeting series (%d upcoming occurrence(s)).", mode, len(series)))
	} else {
		res, serr = c.send(p, fmt.Sprintf("Set the aviso (%s) of 1 meeting series.", mode))
		if serr == nil {
			res.Next = []NextAction{{Command: "cauce-cli reuniones list --json", Reason: "Check the new aviso_en."}}
		}
	}
	if serr != nil {
		return nil, serr
	}
	res.Warnings = append(warnings, res.Warnings...)
	return res, nil
}
