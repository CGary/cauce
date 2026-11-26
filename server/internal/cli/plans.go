package cli

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"

	"cauce/server/internal/core/event"
)

// planTTL is how long a saved plan can be applied.
const planTTL = 24 * time.Hour

var planIDRE = regexp.MustCompile(`^[a-z]{3}_[0-9]{8}_[0-9]{3,6}$`)

// Plan is a write prepared by --dry-run. It stores the event ids, not the ts:
// the ts is set when the plan is applied, and applying twice sends the same
// ids, which the server deduplicates.
type Plan struct {
	SchemaVersion int           `json:"schema_version"`
	PlanID        string        `json:"plan_id"`
	Command       string        `json:"command"`
	CreatedAt     string        `json:"created_at"`
	ExpiresAt     string        `json:"expires_at"`
	Irreversible  bool          `json:"irreversible"`
	Events        []PlanEvent   `json:"events"`
	Changes       []Change      `json:"changes"`
	Precondition  *Precondition `json:"precondition,omitempty"`
	AppliedAt     []string      `json:"applied_at,omitempty"`
}

// PlanEvent is an event without user_id and ts; both are filled at apply time.
type PlanEvent struct {
	ID      string          `json:"id"`
	Type    string          `json:"type"`
	V       int             `json:"v"`
	Payload json.RawMessage `json:"payload"`
}

// Change is one entry of data.changes.
type Change struct {
	Kind     string `json:"kind"`
	Event    string `json:"event"`
	AlarmaID string `json:"alarma_id,omitempty"`
	SerieID  string `json:"serie_id,omitempty"`
}

// Precondition is what an edit plan read: applying it re-reads the alarm and
// fails with CONFLICT if the fixed fields are neither the ones read (Before)
// nor the ones this plan writes (After, i.e. the plan was already applied).
type Precondition struct {
	AlarmaID string      `json:"alarma_id"`
	Before   fixedFields `json:"before"`
	After    fixedFields `json:"after"`
}

func (c *cmdCtx) plansDir() string { return c.env.plansDir() }

func planPrefix(command string) string {
	if strings.HasPrefix(command, "reuniones.") {
		return "reu"
	}
	return "alm"
}

// savePlan writes the plan with a new id <prefix>_<yyyymmdd>_<nnn>.
func (c *cmdCtx) savePlan(p *Plan) *cliError {
	dir := c.plansDir()
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return errorf(CodeInternal, "cannot create the plans directory %s", dir)
	}
	now := c.now()
	p.SchemaVersion = SchemaVersion
	p.CreatedAt = now.Format(time.RFC3339)
	p.ExpiresAt = now.Add(planTTL).Format(time.RFC3339)
	prefix := planPrefix(p.Command) + "_" + now.Format("20060102") + "_"
	next := 1
	entries, _ := os.ReadDir(dir)
	for _, e := range entries {
		name := strings.TrimSuffix(e.Name(), ".json")
		if !strings.HasPrefix(name, prefix) {
			continue
		}
		if n, err := strconv.Atoi(strings.TrimPrefix(name, prefix)); err == nil && n >= next {
			next = n + 1
		}
	}
	for attempt := 0; attempt < 100; attempt++ {
		p.PlanID = fmt.Sprintf("%s%03d", prefix, next+attempt)
		raw, _ := json.MarshalIndent(p, "", "  ")
		f, err := os.OpenFile(filepath.Join(dir, p.PlanID+".json"), os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
		if errors.Is(err, fs.ErrExist) {
			continue
		}
		if err != nil {
			return errorf(CodeInternal, "cannot write the plan file in %s", dir)
		}
		_, werr := f.Write(append(raw, '\n'))
		cerr := f.Close()
		if werr != nil || cerr != nil {
			return errorf(CodeInternal, "cannot write the plan file in %s", dir)
		}
		return nil
	}
	return errorf(CodeInternal, "cannot allocate a plan id in %s", dir)
}

// rewritePlan replaces an existing plan file atomically.
func (c *cmdCtx) rewritePlan(p *Plan) {
	dir := c.plansDir()
	raw, _ := json.MarshalIndent(p, "", "  ")
	tmp, err := os.CreateTemp(dir, ".plan-*")
	if err != nil {
		return
	}
	_, werr := tmp.Write(append(raw, '\n'))
	cerr := tmp.Close()
	if werr != nil || cerr != nil {
		_ = os.Remove(tmp.Name())
		return
	}
	if err := os.Rename(tmp.Name(), filepath.Join(dir, p.PlanID+".json")); err != nil {
		_ = os.Remove(tmp.Name())
	}
}

func (c *cmdCtx) loadPlan(id string) (*Plan, *cliError) {
	if !planIDRE.MatchString(id) {
		return nil, (&cliError{Code: CodeInvalidArgument, Message: "plan id has the form alm_20261124_001", Field: "plan-id", Received: id})
	}
	raw, err := os.ReadFile(filepath.Join(c.plansDir(), id+".json"))
	if errors.Is(err, fs.ErrNotExist) {
		return nil, errorf(CodePlanNotFound, "no saved plan with this id in %s", c.plansDir()).field("plan-id")
	}
	if err != nil {
		return nil, errorf(CodeInternal, "cannot read the plan file")
	}
	var p Plan
	if err := json.Unmarshal(raw, &p); err != nil || p.PlanID != id {
		return nil, errorf(CodeInternal, "the plan file is corrupt")
	}
	exp, err := time.Parse(time.RFC3339, p.ExpiresAt)
	if err != nil || !c.now().Before(exp) {
		return nil, (&cliError{Code: CodePlanExpired, Message: "the plan expired (plans last 24 h); plan the change again with --dry-run", Field: "plan-id"})
	}
	return &p, nil
}

// envelopes stamps the plan's events with user_id and the ts of this moment
// and validates each one against the embedded contract.
func (c *cmdCtx) envelopes(events []PlanEvent, ts time.Time) ([]event.Envelope, *cliError) {
	cfg, err := c.config()
	if err != nil {
		return nil, err
	}
	v, err := c.eventValidator()
	if err != nil {
		return nil, err
	}
	stamp := ts.UTC().Format("2006-01-02T15:04:05.000Z")
	out := make([]event.Envelope, 0, len(events))
	for i, pe := range events {
		env := event.Envelope{ID: pe.ID, UserID: cfg.UserID, DeviceID: DeviceID, Type: pe.Type, V: pe.V, TS: stamp, Payload: pe.Payload}
		raw, _ := json.Marshal(env)
		if _, verr := v.ValidateEnvelope(raw); verr != nil {
			e := errorf(CodeValidationFailed, "event %d does not match the embedded schema %s.v%d; see data.validation_error", i, pe.Type, pe.V)
			e.Data = map[string]any{"validation_error": verr.Error()}
			return nil, e
		}
		out = append(out, env)
	}
	return out, nil
}

// planData is data for a dry run.
func planData(p *Plan) map[string]any {
	return map[string]any{
		"dry_run":    true,
		"plan_id":    p.PlanID,
		"expires_at": p.ExpiresAt,
		"changes":    p.Changes,
		"events":     p.Events,
	}
}

// dryRun validates the events (with the current time as a provisional ts),
// saves the plan and builds the response.
func (c *cmdCtx) dryRun(p *Plan, summary string) (*success, error) {
	if _, err := c.envelopes(p.Events, c.now()); err != nil {
		return nil, err
	}
	if err := c.savePlan(p); err != nil {
		return nil, err
	}
	next := []NextAction{{Command: "cauce-cli plan show --plan-id " + p.PlanID + " --json", Reason: "Inspect the saved plan."}}
	warnings := []string{"Nothing was sent. The plan expires in 24 h."}
	if p.Irreversible {
		warnings = append(warnings, "Deactivation cannot be undone; applying this plan needs both --apply and --yes.")
	} else {
		next = append([]NextAction{{Command: "cauce-cli plan apply --plan-id " + p.PlanID + " --apply --json", Reason: "Send the planned events to the server."}}, next...)
	}
	return &success{Summary: summary, Data: planData(p), Warnings: warnings, Next: next}, nil
}

// send stamps, validates and pushes the plan's events in one batch.
func (c *cmdCtx) send(p *Plan, summary string) (*success, error) {
	envs, err := c.envelopes(p.Events, c.now())
	if err != nil {
		return nil, err
	}
	cl, err := c.client()
	if err != nil {
		return nil, err
	}
	res, err := cl.push(c.ctx, envs)
	if err != nil {
		return nil, err
	}
	changes := p.Changes
	// One /sync/devices read right after the push (D22). The write already
	// succeeded, so a failed read only means "not known to be received".
	phoneReceived := false
	var warnings []string
	devs, derr := cl.devices(c.ctx)
	switch {
	case derr != nil:
		warnings = append(warnings, "Could not read /sync/devices to check whether the phone has it ("+derr.Message+").")
	case res.ServerSeq > 0:
		phoneReceived = devs.phoneHas(res.ServerSeq)
	}
	if !phoneReceived {
		warnings = append(warnings, "The phone has not received it yet; it gets it on its next sync (the CLI cannot make the phone sync).")
	}
	if res.Accepted == 0 && res.Duplicates > 0 {
		changes = []Change{}
		warnings = append([]string{"The server already had these events (same ids): nothing new was written."}, warnings...)
		summary = "Already applied; the server kept the existing events."
	}
	data := map[string]any{
		"dry_run":    false,
		"changes":    changes,
		"accepted":   res.Accepted,
		"duplicates": res.Duplicates,
		"event_ids":  eventIDs(p.Events),
		// server_seq: the change has reached a device once its
		// pulled_through_seq is >= this (D22).
		"server_seq":     res.ServerSeq,
		"phone_received": phoneReceived,
	}
	if p.PlanID != "" {
		data["plan_id"] = p.PlanID
	}
	var next []NextAction
	for _, ch := range p.Changes {
		if ch.AlarmaID != "" && ch.Kind != "deactivate" && len(next) < 1 {
			next = append(next, NextAction{Command: "cauce-cli alarmas get --alarma-id " + ch.AlarmaID + " --json", Reason: "Check the alarm as the server now projects it."})
		}
	}
	if len(next) == 0 {
		next = append(next, NextAction{Command: "cauce-cli alarmas list --json", Reason: "See the active alarms."})
	}
	if !phoneReceived {
		next = append(next, NextAction{Command: "cauce-cli server devices --json", Reason: "Check later whether the phone has received it (phone_received)."})
	}
	return &success{Summary: summary, Data: data, Warnings: warnings, Next: next}, nil
}

func eventIDs(events []PlanEvent) []string {
	out := make([]string, 0, len(events))
	for _, e := range events {
		out = append(out, e.ID)
	}
	return out
}

func planCommands() []*command {
	planID := flagSpec{Name: "plan-id", Kind: kindString, Required: true, Pattern: planIDRE.String(), Desc: "Id returned by a --dry-run, e.g. alm_20261124_001."}
	return []*command{
		{
			Domain: "plan", Verb: "show", Kind: "read",
			Short:       "Show a saved plan without sending it.",
			Description: "Shows a plan saved by a --dry-run: its events, changes, expiry and when it was applied. Use it to review a write before applying it. It sends nothing and never changes the plan. Returns the plan as stored; expired or missing plans fail with PLAN_EXPIRED or PLAN_NOT_FOUND.",
			Flags:       []flagSpec{planID},
			Run:         runPlanShow,
		},
		{
			Domain: "plan", Verb: "apply", Kind: "write",
			Short:       "Send a saved plan (same event ids; ts set now).",
			Description: "Sends the events of a plan saved by a --dry-run in one POST /sync/push, stamping them with the current time. Applying the same plan again is safe: the server keeps the first copy and reports duplicates. An edit plan re-reads the alarm first and fails with CONFLICT if it changed elsewhere; a deactivation plan also needs --yes. Returns accepted and duplicate counts and the changes.",
			Flags: []flagSpec{planID,
				{Name: "yes", Kind: kindBool, Desc: "Confirm an irreversible plan (deactivation). Required with --apply for those."}},
			DataRequired: []string{"dry_run", "changes"},
			Run:          runPlanApply,
		},
	}
}

func runPlanShow(c *cmdCtx) (*success, error) {
	p, err := c.loadPlan(c.flags.str("plan-id"))
	if err != nil {
		return nil, err
	}
	next := []NextAction{}
	if !p.Irreversible {
		next = append(next, NextAction{Command: "cauce-cli plan apply --plan-id " + p.PlanID + " --apply --json", Reason: "Send the planned events."})
	}
	next = append(next, NextAction{Command: "cauce-cli plan apply --plan-id " + p.PlanID + " --dry-run --json", Reason: "Re-check the plan against the server without sending."})
	return &success{
		Summary: fmt.Sprintf("Plan with %d event(s), applied %d time(s).", len(p.Events), len(p.AppliedAt)),
		Data:    map[string]any{"plan": p, "applied": len(p.AppliedAt) > 0},
		Next:    next,
	}, nil
}

func runPlanApply(c *cmdCtx) (*success, error) {
	p, err := c.loadPlan(c.flags.str("plan-id"))
	if err != nil {
		return nil, err
	}
	c.input["yes"] = c.flags.bool("yes")
	apply := c.flags.bool("apply")
	if p.Irreversible && apply && !c.flags.bool("yes") {
		return nil, (&cliError{Code: CodeMissingRequiredFlag, Message: "this plan deactivates alarms and cannot be undone: it needs --yes together with --apply", Field: "yes"}).
			fix("cauce-cli plan show --plan-id " + p.PlanID + " --json")
	}
	if p.Precondition != nil {
		if err := c.checkPrecondition(p.Precondition); err != nil {
			return nil, err
		}
	}
	if !apply {
		if _, err := c.envelopes(p.Events, c.now()); err != nil {
			return nil, err
		}
		next := []NextAction{}
		if !p.Irreversible {
			next = append(next, NextAction{Command: "cauce-cli plan apply --plan-id " + p.PlanID + " --apply --json", Reason: "Send the planned events."})
		}
		return &success{
			Summary: fmt.Sprintf("Would send %d event(s); preconditions hold.", len(p.Events)),
			Data:    planData(p),
			Next:    next,
		}, nil
	}
	res, serr := c.send(p, fmt.Sprintf("Sent %d event(s).", len(p.Events)))
	if serr != nil {
		return nil, serr
	}
	p.AppliedAt = append(p.AppliedAt, c.now().Format(time.RFC3339))
	c.rewritePlan(p)
	return res, nil
}

// checkPrecondition re-reads the alarm an edit plan read and compares the
// fixed fields.
func (c *cmdCtx) checkPrecondition(pre *Precondition) *cliError {
	row, adh, err := c.findAlarma(pre.AlarmaID)
	if err != nil {
		if err.Code == CodeNotFound {
			return errorf(CodeConflict, "the alarm is no longer active; plan the change again").field("plan-id")
		}
		return err
	}
	now := fixedOf(currentState(*row, adh))
	if now.equal(pre.Before) || now.equal(pre.After) {
		return nil
	}
	e := errorf(CodeConflict, "the alarm changed since the plan was made (phone or web); plan the change again with --dry-run").field("plan-id")
	e.Data = map[string]any{"planned_from": pre.Before, "current": now}
	e.fix("cauce-cli alarmas get --alarma-id " + pre.AlarmaID + " --json")
	return e
}
