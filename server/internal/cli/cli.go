// Package cli implements cauce-cli, a command line client for agents (D21,
// docs/PLAN-CLI.md). It is one more client of the server, like the web: it
// reads projections with GET /api/projections/<feature>/<name> and writes
// events with POST /sync/push using device_id "cli". It belongs to no
// feature and imports the shared event validator to check events before
// sending them.
package cli

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"runtime/debug"
	"sort"
	"strings"
	"sync"
	"time"

	"cauce/server/internal/core/event"
)

// Version is the CLI version reported by --version.
const Version = "0.1.0"

// DeviceID is the envelope device_id of every event the CLI writes (D21).
const DeviceID = "cli"

// Env is everything the CLI takes from the process, injectable for tests.
type Env struct {
	Stdin      io.Reader
	Stdout     io.Writer
	Stderr     io.Writer
	Getenv     func(string) string
	Now        func() time.Time
	HTTPClient *http.Client
	// StdinIsTerminal reports whether stdin is an interactive terminal.
	StdinIsTerminal func() bool
}

// DefaultEnv is the real process environment.
func DefaultEnv() Env {
	return Env{
		Stdin:  os.Stdin,
		Stdout: os.Stdout,
		Stderr: os.Stderr,
		Getenv: os.Getenv,
		Now:    time.Now,
		StdinIsTerminal: func() bool {
			info, err := os.Stdin.Stat()
			return err == nil && info.Mode()&os.ModeCharDevice != 0
		},
	}
}

func (e Env) withDefaults() Env {
	d := DefaultEnv()
	if e.Stdin == nil {
		e.Stdin = d.Stdin
	}
	if e.Stdout == nil {
		e.Stdout = d.Stdout
	}
	if e.Stderr == nil {
		e.Stderr = d.Stderr
	}
	if e.Getenv == nil {
		e.Getenv = d.Getenv
	}
	if e.Now == nil {
		e.Now = d.Now
	}
	if e.HTTPClient == nil {
		e.HTTPClient = &http.Client{Timeout: 20 * time.Second}
	}
	if e.StdinIsTerminal == nil {
		e.StdinIsTerminal = d.StdinIsTerminal
	}
	return e
}

// success is what a command handler returns; Run wraps it in the envelope.
type success struct {
	Summary  string
	Data     any
	Warnings []string
	Next     []NextAction
}

type command struct {
	Domain, Verb string
	Kind         string // "read" or "write"
	Short        string // one line, for the index
	Description  string // 3-4 sentences, for --schema
	Flags        []flagSpec
	// Constraints are extra JSON Schema keywords for the input schema
	// (oneOf, dependentRequired, ...).
	Constraints map[string]any
	// DataRequired lists keys data always has on success (for --schema).
	DataRequired []string
	Run          func(*cmdCtx) (*success, error)
}

func (c *command) name() string { return c.Domain + "." + c.Verb }

func (c *command) allFlags() []flagSpec {
	out := append([]flagSpec{}, c.Flags...)
	if c.Kind == "write" {
		out = append(out, writeFlags...)
	}
	return append(out, universalFlags...)
}

// cmdCtx is one invocation of one command.
type cmdCtx struct {
	ctx   context.Context
	env   Env
	cmd   *command
	flags *parsedFlags
	input map[string]any

	cfg       *Config
	tok       string
	cl        *client
	validator *event.Validator
}

var (
	validatorOnce sync.Once
	validatorVal  *event.Validator
	validatorErr  error
)

func (c *cmdCtx) config() (*Config, *cliError) {
	if c.cfg != nil {
		return c.cfg, nil
	}
	path := c.flags.str("config")
	if path == "" {
		path = c.env.configDir() + "/config.json"
	}
	cfg, err := loadConfig(absPath(path))
	if err != nil {
		return nil, err
	}
	c.cfg = cfg
	return cfg, nil
}

func (c *cmdCtx) tokenPath() string {
	if p := c.flags.str("token-file"); p != "" {
		return absPath(p)
	}
	return c.env.configDir() + "/token"
}

func (c *cmdCtx) client() (*client, *cliError) {
	if c.cl != nil {
		return c.cl, nil
	}
	cfg, err := c.config()
	if err != nil {
		return nil, err
	}
	var debugW io.Writer
	if c.flags.bool("debug") {
		debugW = c.env.Stderr
	}
	c.cl = &client{
		baseURL: cfg.BaseURL,
		http:    c.env.HTTPClient,
		debug:   debugW,
		token: func() (string, *cliError) {
			if c.tok != "" {
				return c.tok, nil
			}
			t, err := readToken(c.tokenPath())
			if err != nil {
				return "", err
			}
			c.tok = t
			return t, nil
		},
	}
	return c.cl, nil
}

func (c *cmdCtx) eventValidator() (*event.Validator, *cliError) {
	validatorOnce.Do(func() { validatorVal, validatorErr = event.NewValidator() })
	if validatorErr != nil {
		return nil, errorf(CodeInternal, "cannot compile the embedded event schemas")
	}
	return validatorVal, nil
}

// tz resolves the zone (--tz, config, system) and echoes it in input.
func (c *cmdCtx) tz() (string, *time.Location, *cliError) {
	cfg, err := c.config()
	if err != nil {
		return "", nil, err
	}
	name, loc, err := resolveTZ(c.flags.str("tz"), cfg, c.env)
	if err != nil {
		return "", nil, err
	}
	c.input["tz"] = name
	return name, loc, nil
}

func (c *cmdCtx) now() time.Time { return c.env.Now().UTC() }

// Run executes one command line and returns the process exit code.
func Run(args []string, env Env) (code int) {
	env = env.withDefaults()
	jsonOut := hasFlag(args, "json")
	debugOn := hasFlag(args, "debug")
	name := "cauce-cli"
	var input map[string]any
	defer func() {
		if p := recover(); p != nil {
			if debugOn {
				fmt.Fprintf(env.Stderr, "panic: %v\n%s", p, debug.Stack())
			}
			writeEnvelope(env, jsonOut, errorEnvelope(name, input, errorf(CodeInternal, "unexpected internal error")))
			code = exitFail
		}
	}()

	var positional []string
	rest := args
	for len(rest) > 0 && len(positional) < 2 && !strings.HasPrefix(rest[0], "-") {
		positional = append(positional, rest[0])
		rest = rest[1:]
	}

	if len(positional) < 2 {
		return runTopLevel(env, jsonOut, positional, rest)
	}
	cmd := findCommand(positional[0], positional[1])
	if cmd == nil {
		e := (&cliError{Code: CodeInvalidArgument, Message: "unknown command", Received: strings.Join(positional, " "), Allowed: commandNames()}).fix("cauce-cli --schema")
		writeEnvelope(env, jsonOut, errorEnvelope(name, nil, e))
		return exitUs
	}
	name = cmd.name()

	flags, perr := parseFlags(rest, cmd.allFlags())
	input = echoInput(cmd, flags)
	if perr != nil {
		if perr.Fix == nil {
			perr.fix(fmt.Sprintf("cauce-cli %s %s --schema", cmd.Domain, cmd.Verb))
		}
		writeEnvelope(env, jsonOut, errorEnvelope(name, input, perr))
		return exitCodeFor(perr.Code)
	}
	if flags.bool("schema") || (flags.bool("help") && jsonOut) {
		writeJSON(env.Stdout, commandSchema(cmd), !jsonOut)
		return exitOK
	}
	if flags.bool("help") {
		fmt.Fprint(env.Stdout, commandHelp(cmd))
		return exitOK
	}
	for _, f := range cmd.Flags {
		if f.Required && !flags.has(f.Name) {
			e := (&cliError{Code: CodeMissingRequiredFlag, Message: "missing required flag --" + f.Name, Field: f.Name}).
				fix(fmt.Sprintf("cauce-cli %s %s --schema", cmd.Domain, cmd.Verb))
			writeEnvelope(env, jsonOut, errorEnvelope(name, input, e))
			return exitUs
		}
	}
	if cmd.Kind == "write" {
		dry, apply := flags.bool("dry-run"), flags.bool("apply")
		if dry == apply {
			msg := "pass exactly one of --dry-run or --apply"
			if !dry {
				msg = "write command: pass --dry-run to preview or --apply to send"
			}
			e := &cliError{Code: CodeMissingRequiredFlag, Message: msg, Field: "dry-run"}
			if dry {
				e.Code = CodeInvalidArgument
			}
			writeEnvelope(env, jsonOut, errorEnvelope(name, input, e))
			return exitUs
		}
		input["dry_run"] = dry
	}

	cctx := &cmdCtx{ctx: context.Background(), env: env, cmd: cmd, flags: flags, input: input}
	res, err := cmd.Run(cctx)
	if err != nil {
		ce, ok := err.(*cliError)
		if !ok {
			ce = errorf(CodeInternal, "%v", err)
		}
		writeEnvelope(env, jsonOut, errorEnvelope(name, input, ce))
		return exitCodeFor(ce.Code)
	}
	env2 := &Envelope{
		SchemaVersion: SchemaVersion,
		OK:            true,
		Command:       name,
		Input:         input,
		Summary:       res.Summary,
		Data:          res.Data,
		Warnings:      nonNil(res.Warnings),
		NextActions:   res.Next,
	}
	if env2.NextActions == nil {
		env2.NextActions = []NextAction{}
	}
	if len(env2.NextActions) > 3 {
		env2.NextActions = env2.NextActions[:3]
	}
	if env2.Data == nil {
		env2.Data = map[string]any{}
	}
	writeEnvelope(env, jsonOut, env2)
	return exitOK
}

func nonNil(s []string) []string {
	if s == nil {
		return []string{}
	}
	return s
}

// echoInput is the resolved input: every flag given except the output and
// transport switches. Handlers add resolved defaults (tz, limit) later.
func echoInput(cmd *command, flags *parsedFlags) map[string]any {
	skip := map[string]bool{"json": true, "no-input": true, "schema": true, "help": true, "debug": true, "dry-run": true, "apply": true, "token-file": true}
	in := map[string]any{}
	if flags == nil {
		return in
	}
	for _, name := range flags.order {
		if skip[name] {
			continue
		}
		key := strings.ReplaceAll(name, "-", "_")
		if name == "config" {
			in[key] = absPath(flags.str(name))
			continue
		}
		in[key] = flags.values[name]
	}
	return in
}

func runTopLevel(env Env, jsonOut bool, positional, rest []string) int {
	if len(positional) == 0 && hasFlag(rest, "version") {
		if jsonOut {
			writeEnvelope(env, true, &Envelope{SchemaVersion: SchemaVersion, OK: true, Command: "version", Input: map[string]any{},
				Summary: "cauce-cli " + Version, Data: map[string]any{"version": Version, "schema_version": SchemaVersion},
				Warnings: []string{}, NextActions: []NextAction{}})
		} else {
			fmt.Fprintln(env.Stdout, "cauce-cli "+Version)
		}
		return exitOK
	}
	domain := ""
	if len(positional) == 1 {
		domain = positional[0]
		if !knownDomain(domain) {
			e := (&cliError{Code: CodeInvalidArgument, Message: "unknown command domain", Received: domain, Allowed: commandNames()}).fix("cauce-cli --schema")
			writeEnvelope(env, jsonOut, errorEnvelope("cauce-cli", nil, e))
			return exitUs
		}
	}
	if hasFlag(rest, "schema") || (hasFlag(rest, "help") && jsonOut) {
		writeJSON(env.Stdout, indexSchema(domain), !jsonOut)
		return exitOK
	}
	if hasFlag(rest, "help") || (!jsonOut && len(rest) == 0) {
		fmt.Fprint(env.Stdout, topHelp(domain))
		return exitOK
	}
	msg := "missing command: use cauce-cli <domain> <verb> [flags]"
	e := (&cliError{Code: CodeInvalidArgument, Message: msg, Allowed: commandNames()}).fix("cauce-cli --schema")
	writeEnvelope(env, jsonOut, errorEnvelope("cauce-cli", nil, e))
	return exitUs
}

func writeEnvelope(env Env, jsonOut bool, e *Envelope) {
	writeJSON(env.Stdout, e, !jsonOut)
}

func writeJSON(w io.Writer, v any, indent bool) {
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	if indent {
		enc.SetIndent("", "  ")
	}
	_ = enc.Encode(v)
}

func findCommand(domain, verb string) *command {
	for _, c := range commands() {
		if c.Domain == domain && c.Verb == verb {
			return c
		}
	}
	return nil
}

func knownDomain(domain string) bool {
	for _, c := range commands() {
		if c.Domain == domain {
			return true
		}
	}
	return false
}

func commandNames() []string {
	var out []string
	for _, c := range commands() {
		out = append(out, c.Domain+" "+c.Verb)
	}
	sort.Strings(out)
	return out
}

var (
	commandsOnce sync.Once
	commandList  []*command
	// extraCommands lets tests register a command (e.g. one that panics).
	extraCommands []*command
)

func commands() []*command {
	commandsOnce.Do(func() { commandList = buildCommands() })
	return append(append([]*command{}, commandList...), extraCommands...)
}

func buildCommands() []*command {
	var out []*command
	out = append(out, serverCommands()...)
	out = append(out, alarmasCommands()...)
	out = append(out, readCommands()...)
	out = append(out, reunionesCommands()...)
	out = append(out, planCommands()...)
	return out
}
