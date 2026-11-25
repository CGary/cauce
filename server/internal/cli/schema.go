package cli

import (
	"fmt"
	"strings"
)

const jsonSchemaDialect = "https://json-schema.org/draft/2020-12/schema"

// indexSchema is `cauce-cli --schema`: one line per command.
func indexSchema(domain string) map[string]any {
	var cmds []map[string]any
	for _, c := range commands() {
		if domain != "" && c.Domain != domain {
			continue
		}
		cmds = append(cmds, map[string]any{
			"command":     c.name(),
			"usage":       "cauce-cli " + c.Domain + " " + c.Verb,
			"description": c.Short,
			"kind":        c.Kind,
		})
	}
	return map[string]any{
		"schema_version":  SchemaVersion,
		"cli":             "cauce-cli",
		"version":         Version,
		"universal_flags": []string{"--json", "--no-input", "--schema", "--config <path>", "--token-file <path>", "--debug"},
		"commands":        cmds,
	}
}

// commandSchema is `<command> --schema`: the input and output contracts as
// JSON Schema 2020-12.
func commandSchema(c *command) map[string]any {
	return map[string]any{
		"schema_version": SchemaVersion,
		"command":        c.name(),
		"usage":          "cauce-cli " + c.Domain + " " + c.Verb,
		"description":    c.Description,
		"kind":           c.Kind,
		"input":          inputSchema(c),
		"output":         outputSchema(c),
	}
}

func inputSchema(c *command) map[string]any {
	props := map[string]any{}
	var required []string
	for _, f := range c.allFlags() {
		p := map[string]any{"description": f.Desc}
		switch f.Kind {
		case kindBool:
			p["type"] = "boolean"
		case kindInt:
			p["type"] = "integer"
			if f.Min != nil {
				p["minimum"] = *f.Min
			}
			if f.Max != nil {
				p["maximum"] = *f.Max
			}
		case kindEnum:
			p["type"] = "string"
			p["enum"] = f.Enum
		case kindList:
			p["type"] = "string"
			p["description"] = f.Desc + " Comma-separated, no spaces."
			if len(f.Enum) > 0 {
				p["pattern"] = "^(" + strings.Join(f.Enum, "|") + ")(,(" + strings.Join(f.Enum, "|") + "))*$"
			}
		default:
			p["type"] = "string"
			if f.Pattern != "" {
				p["pattern"] = f.Pattern
			}
		}
		props[f.Name] = p
		if f.Required {
			required = append(required, f.Name)
		}
	}
	s := map[string]any{
		"$schema":              jsonSchemaDialect,
		"type":                 "object",
		"properties":           props,
		"additionalProperties": false,
	}
	if len(required) > 0 {
		s["required"] = required
	}
	var allOf []any
	if c.Kind == "write" {
		props["dry-run"] = map[string]any{"const": true, "description": "Build and validate the change, save it as a plan and send nothing."}
		props["apply"] = map[string]any{"const": true, "description": "Send the change to the server now."}
		allOf = append(allOf, map[string]any{"oneOf": []any{
			map[string]any{"required": []string{"dry-run"}, "not": map[string]any{"required": []string{"apply"}}},
			map[string]any{"required": []string{"apply"}, "not": map[string]any{"required": []string{"dry-run"}}},
		}})
	}
	if _, hasYes := props["yes"]; hasYes && c.name() == "alarmas.deactivate" {
		allOf = append(allOf, map[string]any{
			"if":   map[string]any{"required": []string{"apply"}},
			"then": map[string]any{"required": []string{"yes"}, "properties": map[string]any{"yes": map[string]any{"const": true}}},
		})
	}
	if len(c.Constraints) > 0 {
		allOf = append(allOf, c.Constraints)
	}
	if len(allOf) > 0 {
		s["allOf"] = allOf
	}
	return s
}

func outputSchema(c *command) map[string]any {
	dataSuccess := map[string]any{"type": "object"}
	if c.Kind == "write" {
		dataSuccess["required"] = []string{"dry_run", "changes"}
		dataSuccess["properties"] = map[string]any{
			"dry_run": map[string]any{"type": "boolean"},
			"plan_id": map[string]any{"type": "string", "pattern": planIDRE.String()},
			"changes": map[string]any{"type": "array", "items": map[string]any{
				"type": "object", "required": []string{"kind", "event"},
				"properties": map[string]any{
					"kind":  map[string]any{"enum": []string{"create", "modify", "deactivate"}},
					"event": map[string]any{"type": "string"},
				},
			}},
			"accepted":   map[string]any{"type": "integer", "minimum": 0},
			"duplicates": map[string]any{"type": "integer", "minimum": 0},
		}
	}
	return map[string]any{
		"$schema":  jsonSchemaDialect,
		"type":     "object",
		"required": []string{"schema_version", "ok", "command", "input", "warnings", "next_actions"},
		"properties": map[string]any{
			"schema_version": map[string]any{"const": SchemaVersion},
			"ok":             map[string]any{"type": "boolean"},
			"command":        map[string]any{"const": c.name()},
			"input":          map[string]any{"type": "object"},
			"summary":        map[string]any{"type": "string"},
			"data":           map[string]any{"type": "object"},
			"warnings":       map[string]any{"type": "array", "items": map[string]any{"type": "string"}},
			"next_actions": map[string]any{"type": "array", "maxItems": 3, "items": map[string]any{
				"type": "object", "required": []string{"command", "reason"}, "additionalProperties": false,
				"properties": map[string]any{
					"command": map[string]any{"type": "string", "pattern": "^cauce-cli "},
					"reason":  map[string]any{"type": "string"},
				},
			}},
			"error": map[string]any{
				"type": "object", "required": []string{"code", "message"},
				"properties": map[string]any{
					"code":    map[string]any{"enum": allErrorCodes()},
					"message": map[string]any{"type": "string"},
					"field":   map[string]any{"type": "string"},
					"allowed": map[string]any{"type": "array", "items": map[string]any{"type": "string"}},
				},
			},
			"retryable": map[string]any{"type": "boolean"},
			"suggested_fix": map[string]any{
				"type": "object", "required": []string{"command"},
				"properties": map[string]any{"command": map[string]any{"type": "string"}, "then": map[string]any{"type": "string"}},
			},
		},
		"if":   map[string]any{"properties": map[string]any{"ok": map[string]any{"const": true}}, "required": []string{"ok"}},
		"then": map[string]any{"required": []string{"summary", "data"}, "properties": map[string]any{"data": dataSuccess}, "not": map[string]any{"required": []string{"error"}}},
		"else": map[string]any{"required": []string{"error", "retryable"}},
	}
}

func topHelp(domain string) string {
	var b strings.Builder
	b.WriteString("cauce-cli: read and change cauce alarms, remedios and meeting avisos.\n\n")
	b.WriteString("Usage: cauce-cli <domain> <verb> [flags]   (agents: always --json --no-input)\n\n")
	for _, c := range commands() {
		if domain != "" && c.Domain != domain {
			continue
		}
		fmt.Fprintf(&b, "  %-22s %s\n", c.Domain+" "+c.Verb, c.Short)
	}
	b.WriteString("\nWrites need --dry-run (saves a plan) or --apply. Contracts: cauce-cli --schema, cauce-cli <domain> <verb> --schema.\n")
	return b.String()
}

func commandHelp(c *command) string {
	var b strings.Builder
	fmt.Fprintf(&b, "cauce-cli %s %s: %s\n\n", c.Domain, c.Verb, c.Short)
	for _, f := range c.allFlags() {
		name := "--" + f.Name
		if f.Kind != kindBool {
			name += " <value>"
		}
		req := ""
		if f.Required {
			req = " (required)"
		}
		fmt.Fprintf(&b, "  %-26s %s%s\n", name, f.Desc, req)
	}
	fmt.Fprintf(&b, "\nFull contract: cauce-cli %s %s --schema\n", c.Domain, c.Verb)
	return b.String()
}
