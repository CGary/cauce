package cli

import (
	"strconv"
	"strings"
)

type flagKind int

const (
	kindBool flagKind = iota
	kindString
	kindInt
	kindEnum
	kindList
)

// flagSpec declares one long flag. The same declaration drives parsing,
// --help and the `input` JSON Schema of --schema.
type flagSpec struct {
	Name     string
	Kind     flagKind
	Enum     []string
	Desc     string
	Required bool
	Min, Max *int
	// Pattern is a regular expression the value must match (documentation
	// for --schema; the handler checks it with a precise error).
	Pattern string
}

func intp(n int) *int { return &n }

// universalFlags are accepted by every command.
var universalFlags = []flagSpec{
	{Name: "json", Kind: kindBool, Desc: "Print exactly one JSON object on stdout. Agents always pass it."},
	{Name: "no-input", Kind: kindBool, Desc: "Never prompt; anything that would need input fails with INPUT_REQUIRED. Agents always pass it."},
	{Name: "schema", Kind: kindBool, Desc: "Print this command's JSON Schema contract and exit."},
	{Name: "help", Kind: kindBool, Desc: "Print short human help and exit."},
	{Name: "config", Kind: kindString, Desc: "Path of the config file (default $XDG_CONFIG_HOME/cauce-cli/config.json or ~/.config/cauce-cli/config.json)."},
	{Name: "token-file", Kind: kindString, Desc: "Path of the API token file (default next to the config, named token). Must not be readable by other users."},
	{Name: "debug", Kind: kindBool, Desc: "Diagnostics on stderr. Never prints the token."},
}

// writeFlags are added to every write command.
var writeFlags = []flagSpec{
	{Name: "dry-run", Kind: kindBool, Desc: "Build and validate the change, save it as a plan and send nothing."},
	{Name: "apply", Kind: kindBool, Desc: "Send the change to the server now."},
}

// parsedFlags is the result of parsing one command line.
type parsedFlags struct {
	values map[string]any
	order  []string
}

func (p *parsedFlags) has(name string) bool { _, ok := p.values[name]; return ok }

func (p *parsedFlags) bool(name string) bool {
	v, _ := p.values[name].(bool)
	return v
}

func (p *parsedFlags) str(name string) string {
	v, _ := p.values[name].(string)
	return v
}

func (p *parsedFlags) int(name string) int {
	v, _ := p.values[name].(int)
	return v
}

func (p *parsedFlags) list(name string) []string {
	v, _ := p.values[name].([]string)
	return v
}

// parseFlags parses `--name value` and `--name=value` against specs. Values may
// start with "-" (e.g. `--bymonthday -1`) because the flag's kind is known.
func parseFlags(args []string, specs []flagSpec) (*parsedFlags, *cliError) {
	byName := make(map[string]flagSpec, len(specs))
	names := make([]string, 0, len(specs))
	for _, s := range specs {
		byName[s.Name] = s
		names = append(names, "--"+s.Name)
	}
	out := &parsedFlags{values: map[string]any{}}
	for i := 0; i < len(args); i++ {
		arg := args[i]
		if !strings.HasPrefix(arg, "--") || arg == "--" {
			return out, &cliError{Code: CodeInvalidArgument, Message: "unexpected argument; every value goes in a --flag", Received: arg}
		}
		name, value, hasValue := strings.Cut(arg[2:], "=")
		spec, ok := byName[name]
		if !ok {
			return out, &cliError{Code: CodeInvalidArgument, Message: "unknown flag --" + name, Field: name, Allowed: names}
		}
		if _, dup := out.values[name]; dup {
			return out, (&cliError{Code: CodeInvalidArgument, Message: "flag --" + name + " given twice"}).field(name)
		}
		if spec.Kind == kindBool {
			b := true
			if hasValue {
				parsed, err := strconv.ParseBool(value)
				if err != nil {
					return out, &cliError{Code: CodeInvalidArgument, Message: "--" + name + " is a boolean flag", Field: name, Received: value, Allowed: []string{"true", "false"}}
				}
				b = parsed
			}
			out.values[name] = b
			out.order = append(out.order, name)
			continue
		}
		if !hasValue {
			if i+1 >= len(args) {
				return out, (&cliError{Code: CodeInvalidArgument, Message: "flag --" + name + " needs a value"}).field(name)
			}
			i++
			value = args[i]
		}
		parsed, err := convertFlag(spec, value)
		if err != nil {
			return out, err
		}
		out.values[name] = parsed
		out.order = append(out.order, name)
	}
	return out, nil
}

func convertFlag(spec flagSpec, value string) (any, *cliError) {
	switch spec.Kind {
	case kindString:
		return value, nil
	case kindInt:
		n, err := strconv.Atoi(strings.TrimSpace(value))
		if err != nil {
			return nil, &cliError{Code: CodeInvalidArgument, Message: "--" + spec.Name + " must be an integer", Field: spec.Name, Received: value}
		}
		if spec.Min != nil && n < *spec.Min || spec.Max != nil && n > *spec.Max {
			return nil, &cliError{Code: CodeInvalidArgument, Message: "--" + spec.Name + " is out of range " + rangeText(spec), Field: spec.Name, Received: n}
		}
		return n, nil
	case kindEnum:
		v := strings.ToLower(strings.TrimSpace(value))
		for _, allowed := range spec.Enum {
			if v == allowed {
				return v, nil
			}
		}
		return nil, &cliError{Code: CodeInvalidEnum, Message: "--" + spec.Name + " must be one of: " + strings.Join(spec.Enum, ", "), Field: spec.Name, Received: value, Allowed: spec.Enum}
	case kindList:
		var items []string
		for _, part := range strings.Split(value, ",") {
			part = strings.TrimSpace(part)
			if part == "" {
				return nil, &cliError{Code: CodeInvalidArgument, Message: "--" + spec.Name + " is a comma-separated list without empty items", Field: spec.Name, Received: value}
			}
			items = append(items, part)
		}
		return items, nil
	}
	return value, nil
}

func rangeText(spec flagSpec) string {
	lo, hi := "", ""
	if spec.Min != nil {
		lo = strconv.Itoa(*spec.Min)
	}
	if spec.Max != nil {
		hi = strconv.Itoa(*spec.Max)
	}
	return lo + ".." + hi
}

// hasFlag reports whether a raw argument list carries --name (or --name=true),
// before the command is known. Used for --json on early errors.
func hasFlag(args []string, name string) bool {
	for _, a := range args {
		if a == "--"+name || a == "--"+name+"=true" {
			return true
		}
	}
	return false
}
