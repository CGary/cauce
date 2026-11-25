# Guide for Designing CLIs for AI Agents

> Consolidated edition (2026-11-24). Replaces the earlier drafts of
> 2026-07-02 and 2026-09-02 (EN and ES). Where those drafts
> disagreed, the conflict was resolved against the published standards listed
> in [Sources](#sources). Where this guide deliberately departs from a
> standard, [Deviations from Standards](#deviations-from-standards) says so and
> why.

## Objective

Design CLIs meant to be called by AI agents (Claude Code, Codex CLI, OpenCode,
Gemini CLI, internal agents) while minimizing two risks:

1. The agent calls the command incorrectly, passes ambiguous arguments, or
   misreads the output.
2. The agent wastes tokens on help text, logs, long outputs, or repeated
   documentation.

Core thesis:

> A CLI for agents is not a conversation. It is a local API: atomic commands,
> typed arguments, structured output, predictable errors, and minimal but
> precise documentation.

## Basis

- **Tool model (MCP 2025-11-25).** A tool is a name, a description, an
  `inputSchema`, and an optional `outputSchema`; results carry
  `structuredContent` and `isError`. Schemas default to JSON Schema 2020-12.
  Every definition, call, and result costs tokens.
- **CLI conventions (clig.dev).** Primary and machine-readable output on
  `stdout`, messaging on `stderr`; zero exit on success, non-zero on failure;
  `--json` for machines, `--plain` for line-oriented tools; prompts only when
  `stdin` is a TTY, never required, disabled by `--no-input`; additive changes
  and deprecation warnings instead of breaking ones.
- **Tool design for agents (Anthropic, "Writing effective tools for agents"
  and "Define tools").** Detailed descriptions of what a tool does, when to use
  it, and what it returns; unambiguous parameter names; high-signal responses
  with sensible size defaults; actionable errors instead of opaque codes or
  tracebacks; tools validated with evaluations.
- **Error model (Google AIP-193/194, RFC 9457).** Stable machine-readable
  reasons in UPPER_SNAKE_CASE, short actionable messages, details in
  structured fields, and an explicit rule for which errors are retryable.
- **Contracts (JSON Schema 2020-12).** Use standard keywords as-is rather than
  inventing a private schema dialect.

---

## Design Principles

### 1. Atomic commands, not "do everything" commands

Bad:

```bash
repo-cli manage --task "analyze and fix auth issues"
```

Good:

```bash
repo-cli auth audit --json
repo-cli auth fix --issue missing-token-refresh --dry-run --json
repo-cli auth fix --issue missing-token-refresh --apply --json
```

An agent is less likely to make mistakes when the command itself encodes the
intent. Never accept intent as free text: `--task`, `--action`, `--prompt`,
`--instruction`, `--mode anything`. These force the agent to write natural
language and increase ambiguity.

> If an argument value looks like a prompt, the CLI design is wrong.

This does not contradict the advice to consolidate related operations into
fewer tools (Anthropic, "Define tools"): `<domain> <verb>` *is* a consolidated
tool whose action is a closed enum. What is forbidden is an action expressed
as free text.

### 2. One subcommand, one stable intent

```bash
<cli> <domain> <verb> [object] [flags]
```

Examples:

```bash
repo-cli deps audit --json
repo-cli deps plan-update --package react --target latest-compatible --json
repo-cli migration plan --from redux-legacy --to redux-current --json
repo-cli issues list --milestone "Sprint W37" --state open --json
```

| Element | Rule |
|---|---|
| Domain | Noun: `deps`, `auth`, `db`, `issues`, `migration` |
| Verb | Concrete: `list`, `get`, `audit`, `plan`, `apply`, `validate`, `diff`, `show` |
| Object | Only when it disambiguates the target: `user`, `package`, `schema` |
| Flags | Data, scope, limits, output format — never the main intent |

Command names, verbs, and enum values are part of the public contract. Renames
are breaking changes: keep the old name as a deprecated alias and report it in
`warnings` (see JSON contract), not only on `stderr`.

### 3. Enums over free text

Bad:

```bash
repo-cli refactor --style "make it safer but not too invasive"
```

Good:

```bash
repo-cli refactor plan --strategy minimal --risk-level low --json
```

Use closed, lowercase, kebab-case enums wherever the agent could otherwise
invent values:

```bash
--strategy minimal|standard|aggressive
--risk-level low|medium|high
--state open|closed|merged|all
--scope file|module|package|repo
```

Avoid values that describe a feeling instead of a behavior:

```bash
--mode smart
--level normal
--type advanced
--quality best
```

`smart`, `normal`, `advanced`, and `best` may sound clear to humans, but they
define no verifiable behavior for an agent. If two values cannot be told apart
by a test, merge them.

### 4. Result-changing arguments are explicit, and echoed back

If an argument affects scope, target, or writes, do not infer it.

Bad:

```bash
repo-cli fix --json
```

Good:

```bash
repo-cli fix --target-file src/auth/session.ts --issue missing-refresh-token --dry-run --json
```

> By reading the command alone, the agent must know what will be touched, why,
> and at what risk.

Arguments that should usually be explicit:

```bash
--target-file  --scope  --cwd  --config  --from  --to
--state  --limit  --dry-run  --apply  --output
```

The CLI closes the loop: the JSON response echoes the **resolved** inputs
(defaults applied, paths normalized) under `input`, so the agent can verify
how the command was interpreted instead of assuming.

### 5. Read → plan → apply, with one convention for writes

The safest and cheapest flow for agents separates reading, planning, and
writing, so destructive actions cannot happen too early:

```bash
repo-cli deps audit --json                                   # read
repo-cli deps plan-update --package redux --target latest-compatible --json   # plan → plan_id
repo-cli deps apply-plan --plan-id depplan_123 --dry-run --json               # preview
repo-cli deps apply-plan --plan-id depplan_123 --apply --json                 # write
```

A `plan_id` also saves tokens: the agent passes a short handle instead of
copying long output from one command into the next.

Rules for any command that writes:

- `--dry-run` is the standard name (clig.dev lists `-n, --dry-run`; git and
  npm use the same boolean): describe the changes, make none.
- It requires exactly one of `--dry-run` or `--apply`. With neither, it fails
  with `MISSING_REQUIRED_FLAG` and does nothing. Refusing to act without an
  explicit flag is an established pattern: `git clean` refuses to delete
  unless given `-f` (`clean.requireForce` defaults to true). A write command
  must never be destructive by default (`repo-cli cleanup` with no flags →
  error, not cleanup).
- The go-ahead flag is named `--apply`, not clig.dev's `--force`, because
  `--force` conventionally means "override a safety check" (`git push
  --force`, `git worktree remove --force`). See
  [Deviations](#deviations-from-standards).
- `--yes` is only for operations the project classifies as irreversible
  (delete data, force-push, drop a table) and is required *in addition to*
  `--apply`, as `gh repo delete --yes` does. It is never a substitute for
  `--apply`. For the most severe cases, clig.dev recommends making the user
  type the target's name: `--confirm=<name>`.
- The response always includes `data.dry_run: true|false` and
  `data.changes[]`, so a preview cannot be mistaken for an application.
- `--dry-run` is a boolean. Do not add a parallel enum such as
  `--apply-mode dry-run|write|commit`. The only accepted exception is when
  the levels are genuinely different operations, as in kubectl's
  `--dry-run=none|client|server` (client print vs. server-side validation).

Plans are first-class:

- `plan_id` is opaque, unique, and safe as a filename (`mig_20260702_001`).
- Plans are persisted in a known, gitignored directory (e.g.
  `.repo-cli/plans/`), readable with `<domain> plan show --plan-id <id>`, and
  expire; applying a stale or missing plan returns `PLAN_NOT_FOUND` or
  `PLAN_EXPIRED`.
- Applying a plan re-validates its preconditions (files unchanged since
  planning) and fails with `CONFLICT` otherwise.

### 6. Agent output is JSON on `stdout`; everything else is `stderr`

```bash
repo-cli deps audit           # human
repo-cli deps audit --json    # agent
```

```
stdout  →  exactly one JSON object (with --json), even on error and on crash
stderr  →  logs, progress, human diagnostics
exit    →  see the exit-code table below
```

With `--json`: no banners, colors, emojis, spinners, progress bars, or pagers.
Regardless of `--json`, follow clig.dev:

- Disable color when the stream is not a TTY (check `stdout` and `stderr`
  separately), when `NO_COLOR` is set and non-empty, when `TERM=dumb`, or
  when `--no-color` is passed.
- No animations or pager when `stdout` is not a TTY.
- Prompt only when `stdin` is a TTY, and never under `--no-input`. If input
  is needed, fail with `INPUT_REQUIRED` and name the flag that supplies it
  ("fail and tell the user how to pass the information as a flag").
- `stderr` is for messaging, not a log file: no `ERR`/`WARN` level labels
  unless `--verbose`.

Human-oriented rendering (tables, colors, narrative) is optional and lives
behind the absence of `--json`. Never make the agent parse it.

---

## Argument Conventions

**Names.** Long, explicit, kebab-case: `--target-file`, `--source-branch`,
`--destination-branch`, `--risk-level`. No abbreviations for agents (`--tf`,
`--src`, `--dst`, `--lvl`, bare `--mode`); short aliases are fine for humans as
long as the long form exists. Short flags save typing for humans; long flags
reduce semantic mistakes for agents. clig.dev: "Have full-length versions of
all flags" and "Only use one-letter flags for commonly used flags". Name
parameters by what they hold: `--user-id`, not `--user` (Anthropic).

**Standard names.** When a standard name exists, use it (clig.dev):
`-h, --help` (only ever help), `-n, --dry-run`, `-o, --output`,
`-q, --quiet`, `-d, --debug`, `-a, --all`, `--json`, `--no-input`,
`--version`. Do not use `-v` for anything; it is ambiguous between verbose
and version.

**Booleans.** Only for genuinely binary behavior: `--json`, `--dry-run`,
`--apply`, `--no-input`, `--yes`. Do not encode a spectrum as competing
booleans (`--force`/`--safe`/`--risky` → `--risk-level low|medium|high`).

**No double negatives.** `--no-disable-cache` and `--skip-no-verify` are bad.
Use `--cache enabled|disabled` or `--verify true|false`; `--use-cache` and
`--skip-verify` are also acceptable.

**Values.** Accept both `--flag value` and `--flag=value`. Repeatable flags are
repeated (`--field iid --field title`), or comma-separated with no spaces
(`--fields iid,title`); pick one per CLI and document it. `--` ends option
parsing.

**Paths.** Explicit and resolvable: `--target-file src/auth/session.ts`, not
`--file session`. Relative paths resolve against `--cwd` (default: process
cwd). The CLI never depends on the process cwd for anything else and returns
both the input path and the absolute path:

```json
{
  "input": { "cwd": "/repo", "target_file": "src/auth/session.ts" },
  "data": { "target_file": "src/auth/session.ts", "absolute_path": "/repo/src/auth/session.ts" }
}
```

Paths must stay inside `--cwd` (or the repo root); anything else is
`INVALID_ARGUMENT`.

**Large inputs.** Never as an argument; see
[Large Inputs and Outputs](#large-inputs-and-outputs).

**Secrets never in flags or environment variables.** clig.dev: "Do not read
secrets directly from flags" (they leak into `ps` and shell history) and "Do
not read secrets from environment variables". Accept them through a file
(`--token-file <path>`), `stdin`, or a credential store.

**Never shell-interpret arguments.** Values may come from untrusted sources
(issue bodies, file contents the agent read). Pass them as argument lists to
subprocesses; never build a shell string.

---

## Universal Flags

Agent-critical (every CLI for agents should support these):

| Flag | Purpose |
|---|---|
| `--json` | Structured output; the agent always passes it |
| `--no-input` | Any prompt becomes `INPUT_REQUIRED`; the agent always passes it |
| `--dry-run` / `--apply` | Required, mutually exclusive, on every write command |
| `--yes` | Extra consent for irreversible operations only |
| `--cwd <path>` | Explicit working directory |
| `--config <path>` | Explicit configuration file |
| `--input <file>` / `--input -` | Large input from a file or `stdin` |
| `--limit <n>` / `--cursor <token>` | Output size and pagination |
| `--fields <list>` | Field projection on list/get commands |
| `--output <path>` | Write the full result to a file; `stdout` gets a summary |
| `--timeout <seconds>` | Hard limit; on expiry → `TIMEOUT` with side-effect status |
| `--schema` | Print the command's JSON Schema and exit |
| `--version` | Version (as JSON when combined with `--json`) |

Human-only (never needed in agent flow):

| Flag | Purpose |
|---|---|
| `--plain` | Simple text output for pipes |
| `--quiet` | Remove non-essential noise |
| `--verbose` | More functional detail, on `stderr` |
| `--debug` | Diagnostics and stack traces, on `stderr` |

`--verbose`/`--debug` output goes to `stderr` and must not change the JSON
shape on `stdout`.

---

## JSON Contract

### Success

```json
{
  "schema_version": 1,
  "ok": true,
  "command": "deps.audit",
  "input": { "cwd": "/repo", "scope": "repo", "limit": 50 },
  "summary": "Found 3 outdated dependencies and 1 risky major upgrade.",
  "data": { "outdated_count": 3, "risky_count": 1 },
  "warnings": [],
  "next_actions": [
    {
      "command": "repo-cli deps plan-update --package redux --target latest-compatible --json",
      "reason": "Plan a minimal Redux upgrade without replacing libraries."
    }
  ]
}
```

### Write command (preview or application)

```json
{
  "schema_version": 1,
  "ok": true,
  "command": "deps.apply-plan",
  "input": { "plan_id": "depplan_123", "dry_run": true },
  "summary": "Would modify 2 files.",
  "data": {
    "dry_run": true,
    "changes": [
      { "path": "package.json", "kind": "modify" },
      { "path": "package-lock.json", "kind": "modify" }
    ]
  },
  "warnings": [],
  "next_actions": [
    {
      "command": "repo-cli deps apply-plan --plan-id depplan_123 --apply --json",
      "reason": "Apply the previewed changes."
    }
  ]
}
```

### Error

```json
{
  "schema_version": 1,
  "ok": false,
  "command": "deps.plan-update",
  "input": { "package": "redux", "target": "modern" },
  "error": {
    "code": "INVALID_ENUM",
    "message": "--target must be one of: latest-compatible, latest, exact",
    "field": "target",
    "received": "modern",
    "allowed": ["latest-compatible", "latest", "exact"]
  },
  "retryable": false,
  "suggested_fix": {
    "command": "repo-cli deps plan-update --package redux --target latest-compatible --json"
  }
}
```

### Rules

1. `schema_version`, `ok`, and `command` always exist — on success, on error,
   and on unexpected crashes (wrap the entry point; an uncaught exception under
   `--json` still emits `{"ok": false, "error": {"code": "INTERNAL_ERROR"}}`).
   Bump `schema_version` only on breaking changes to the envelope or to `data`
   keys; agents and skills pin it.
2. `input` echoes the resolved arguments (defaults applied, paths absolute).
3. `summary` is one short line. `error.message` is one short line.
4. `data` holds the real result. No logs, no narrative, no stack traces
   (stack traces only under `--debug`, and only on `stderr`).
5. `warnings[]` carries anything the agent must know that is not an error:
   deprecated flags, skipped items, truncated lists. `stderr` alone is not
   enough — agents rarely read it.
6. `next_actions` has at most 3 entries, is generated by the CLI from its own
   state (never echoed from user or third-party content), and never contains
   `--apply` on irreversible commands or `--yes`. The agent decides; the CLI
   suggests.
7. `retryable: true` means "the same command, unchanged, may succeed if
   retried" (`NETWORK_ERROR`, `LOCKED`, and `TIMEOUT` when
   `data.side_effects` is `none`). Argument errors are never retryable — AIP-194:
   "Retrying a request with an invalid argument will never succeed" — and
   carry `suggested_fix` instead. Fixing the arguments and calling again is
   self-correction (MCP), not a retry. See the
   [error-code table](#error-codes).
8. Untrusted text (issue bodies, commit messages, file contents) is returned
   only inside `data`, as values, never merged into `summary`, `warnings`, or
   `next_actions`. This limits prompt injection through tool output.
9. Secrets (tokens, passwords, connection strings) are redacted in every
   output mode, including `--debug`.
10. Lists are sorted deterministically and truncated by `--limit` with
    `has_more` and `next_cursor`. Same inputs → same output; no timestamps or
    random ids unless they are the result.
11. Commands whose job is to check something (`validate`, `audit`, `diff`)
    return `ok: true` and exit `0` when the check *ran*; findings live in
    `data` (`passed: false`, `findings: [...]`). Reserve non-zero exit codes
    for the tool failing. Offer `--fail-on findings` for CI pipelines that
    want a non-zero exit.
12. `PARTIAL_SUCCESS` is an error (`ok: false`, exit `1`) whose `data` lists
    what succeeded and what did not, so the agent can resume instead of
    re-running everything.

### Exit codes

| Code | Meaning |
|---|---|
| `0` | Command ran; result in `data` |
| `1` | Command ran and failed (validation, conflict, partial success, internal) |
| `2` | Usage error: unknown command, invalid or missing arguments |
| `3` | Precondition not met: not found, locked, plan expired, input required |
| `4` | Timeout or network |

The JSON `error.code` is the fine-grained signal; the exit code is the coarse
one. Both are stable.

What is standard and what is convention:

- `0` = success, non-zero = failure is universal (POSIX, clig.dev). clig.dev
  only asks to "map the non-zero exit codes to the most important failure
  modes" and fixes no numbers.
- `2` for usage errors matches common practice (Python `argparse` exits with
  status 2 on parsing errors).
- `3` and `4` are this guide's convention. There is no portable standard for
  them.
- Never use `126`, `127`, or values above `128`: POSIX reserves them for
  "not executable", "not found", and termination by a signal, so callers
  would misread them.
- Do not adopt `sysexits.h` (64–78): FreeBSD's own man page marks it
  deprecated, "retained only for compatibility", and not portable.

---

## Error Handling for Agents

The worst error for an agent is:

```text
Something went wrong
```

MCP requires tool execution errors to "contain actionable feedback that
language models can use to self-correct and retry with adjusted parameters";
Anthropic asks for "specific and actionable improvements, rather than opaque
error codes or tracebacks". A good error says:

1. What failed.
2. Which field is wrong.
3. Which values are valid.
4. Whether the error is retryable.
5. When the fix is mechanical, the corrected command.

```json
{
  "schema_version": 1,
  "ok": false,
  "command": "inspect",
  "input": { "cwd": "/repo" },
  "error": {
    "code": "MISSING_REQUIRED_FLAG",
    "message": "Missing required flag: --target-file",
    "field": "target-file"
  },
  "retryable": false,
  "suggested_fix": {
    "command": "repo-cli inspect --target-file <path> --json"
  }
}
```

**Under `--json`, never print the usage/help page on error**: a typo must not
cost the agent a full help screen. Without `--json`, clig.dev's advice applies:
concise help (description, one or two examples, pointer to `--help`) when a
command is run without its required arguments.

### Error codes

`error.code` follows AIP-193's format for `reason`: UPPER_SNAKE_CASE matching
`[A-Z][A-Z0-9_]+[A-Z0-9]`, at most 63 characters, stable once published. Each
code maps to the closest canonical `google.rpc.Code`, and its retry behavior
follows AIP-194:

| `error.code` | Closest canonical code | Exit | `retryable` |
|---|---|---|---|
| `INVALID_ARGUMENT`, `MISSING_REQUIRED_FLAG`, `INVALID_ENUM` | `INVALID_ARGUMENT` | `2` | `false` |
| `UNSUPPORTED` | `UNIMPLEMENTED` | `2` | `false` |
| `VALIDATION_FAILED` | `FAILED_PRECONDITION` | `1` | `false` |
| `PERMISSION_DENIED` | `PERMISSION_DENIED` | `1` | `false` |
| `CONFLICT` | `ABORTED` | `1` | `false` (re-plan) |
| `PARTIAL_SUCCESS` | none (see below) | `1` | `false` (resume) |
| `INTERNAL_ERROR` | `INTERNAL` | `1` | `false` |
| `NOT_FOUND`, `FILE_NOT_FOUND`, `CONFIG_NOT_FOUND`, `PLAN_NOT_FOUND` | `NOT_FOUND` | `3` | `false` |
| `PLAN_EXPIRED`, `INPUT_REQUIRED` | `FAILED_PRECONDITION` | `3` | `false` |
| `LOCKED` | `ABORTED` | `3` | `true` |
| `TIMEOUT` | `DEADLINE_EXCEEDED` | `4` | `true` only if `data.side_effects` is `none` |
| `NETWORK_ERROR` | `UNAVAILABLE` | `4` | `true` |

AIP-194 marks the "not found / permission / precondition" family as "do not
retry until state changes": the agent must change something (create the file,
re-plan, ask a human) first. `PARTIAL_SUCCESS` has no canonical equivalent —
AIP-193 says APIs should not support partial errors — so it is a
CLI-specific code for batch commands; see
[Deviations](#deviations-from-standards).

---

## Output Size

Agents spend more tokens when they have to read large outputs. Every command
that can return significant content supports `--limit`, `--fields`,
`--cursor`, and `--output`. Defaults that keep tool results cheap:

- Default `--limit` between 20 and 50; `has_more` + `next_cursor` beyond that.
- `summary` ≤ 1 line; default JSON response ≤ ~2 KB; anything larger goes to
  `--output <file>` and the response returns `result_file` (absolute path) and
  the counts.
- `--help` at any level fits in one screen (~40 lines); details live in
  `--schema`.
- Hard ceiling for context: Claude Code restricts tool responses to 25,000
  tokens by default (Anthropic). Stay far below it; truncation that the agent
  cannot see is worse than a short response with `has_more`.
- Return high-signal fields: human-readable names next to ids, not bare
  UUIDs or internal fields such as `mime_type` (Anthropic).
- Optionally offer `--detail concise|detailed`, Anthropic's
  `response_format` pattern, when the same command has a cheap and a full
  answer. It controls verbosity, never the output format.

```bash
repo-cli issues list --milestone "Sprint W37" --state open --fields iid,title,url --limit 20 --json
```

```json
{
  "schema_version": 1,
  "ok": true,
  "command": "issues.list",
  "input": { "milestone": "Sprint W37", "state": "open", "fields": ["iid", "title", "url"], "limit": 20 },
  "summary": "20 of 37 open issues.",
  "data": {
    "items": [{ "iid": 123, "title": "Fix POS duplicate submit", "url": "https://lab.example.com/project/issues/123" }],
    "count": 20,
    "total": 37,
    "has_more": true,
    "next_cursor": "eyJwYWdlIjoyfQ"
  },
  "warnings": [],
  "next_actions": []
}
```

---

## Large Inputs and Outputs

Never pass long text as an argument.

Bad:

```bash
repo-cli analyze --content "very long text..."
```

Good — from a file, or from `stdin` with `-`:

```bash
repo-cli analyze --input report.md --json
cat report.md | repo-cli analyze --input - --json
```

For large results, write them to a file and keep `stdout` short:

```bash
repo-cli analyze --input report.md --output analysis.json --json
```

```json
{
  "schema_version": 1,
  "ok": true,
  "command": "analyze",
  "input": { "cwd": "/repo", "input": "/repo/report.md", "output": "/repo/analysis.json" },
  "summary": "Analysis written to /repo/analysis.json (14 findings).",
  "data": { "result_file": "/repo/analysis.json", "findings_count": 14 },
  "warnings": [],
  "next_actions": []
}
```

---

## Discoverability: `--schema` and Layered Help

Two levels, both JSON:

```bash
repo-cli --schema                      # command index: name, one-line description, read|plan|write
repo-cli deps plan-update --schema     # full contract for one command
```

This lets the agent inspect the contract without reading long documentation.
The per-command document wraps two standard JSON Schema 2020-12 schemas,
`input` and `output` — the same dialect MCP uses by default, so they map
directly to a tool's `inputSchema` and `outputSchema`. Express every rule
with standard keywords:

- "X is required when Y has value Z" → `if`/`then`. The JSON Schema docs
  note that "the `required` keyword is necessary in the `if` schemas";
  without it, an absent Y makes `if` pass.
- "X is required whenever Y is present" → `dependentRequired`.
- "Exactly one of A or B" → `oneOf` with one `required` per branch.
- Do not invent keywords such as `required_when`: JSON Schema has no such
  keyword, and validators silently treat unknown keywords as annotations
  (2020-12 core, §6.5), so the rule would never be enforced. The `x-` prefix
  is an OpenAPI convention, not a JSON Schema one.

Plan command:

```json
{
  "command": "deps.plan-update",
  "description": "Plans a dependency update without modifying files. Use it before any upgrade to get a plan_id and a risk level. Do not use it to apply changes; use deps apply-plan. Returns the plan_id, the affected files, and the risk level.",
  "kind": "plan",
  "input": {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "type": "object",
    "required": ["package", "target"],
    "properties": {
      "package": { "type": "string", "description": "Package name from package.json." },
      "target": { "type": "string", "enum": ["latest-compatible", "latest", "exact"] },
      "exact-version": { "type": "string" }
    },
    "if": { "properties": { "target": { "const": "exact" } }, "required": ["target"] },
    "then": { "required": ["exact-version"] },
    "additionalProperties": false
  },
  "output": {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "type": "object",
    "required": ["schema_version", "ok", "command", "summary", "data"]
  }
}
```

Write command — `--dry-run` and `--apply` are mutually exclusive and one is
mandatory:

```json
{
  "command": "deps.apply-plan",
  "kind": "write",
  "input": {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "type": "object",
    "required": ["plan-id"],
    "properties": {
      "plan-id": { "type": "string" },
      "dry-run": { "const": true },
      "apply": { "const": true }
    },
    "oneOf": [{ "required": ["dry-run"] }, { "required": ["apply"] }],
    "additionalProperties": false
  }
}
```

Human help stays layered and short; `--schema` is the bottom layer:

```bash
repo-cli --help
repo-cli deps --help
repo-cli deps audit --help
repo-cli deps audit --schema
```

---

## Concurrency and Idempotency

Several agents may run the CLI at once (parallel worktrees, CI jobs):

- Write commands take a lock scoped to what they modify (repo, plan, file
  set). A second caller gets `LOCKED` with the holder and `retryable: true`,
  never a corrupted result.
- Read and plan commands are idempotent and side-effect free. Apply commands
  are idempotent where possible: applying an already-applied plan returns
  `ok: true`, `data.changes: []`, and a warning, not a second application.
- `--timeout` expiry reports whether side effects happened
  (`data.side_effects: none|partial|complete`) before exiting with `TIMEOUT`.

---

## Documentation for Agents

### AGENTS.md (or equivalent)

Include a short section for the CLI:

```md
## Project CLI for agents

Use `repo-cli` instead of ad-hoc shell pipelines when a matching subcommand exists.

Always: `--json --no-input`. Writes: `--dry-run` first, then `--apply`.
Large results: `--output <file>`. Discover commands with `repo-cli --schema`.
Never parse human output; never rerun a failed command without reading `error.code`.

Common commands:
- `repo-cli deps audit --json`
- `repo-cli deps plan-update --package <name> --target latest-compatible --json`
- `repo-cli deps apply-plan --plan-id <id> --dry-run --json`
```

### How long a description should be

Two different descriptions, two different budgets:

- **Command index** (`repo-cli --schema`): one line per command, so the agent
  can scan the whole CLI cheaply.
- **Per-command description** (`<command> --schema`, MCP tool definition):
  Anthropic calls detailed descriptions "by far the most important factor in
  tool performance" and recommends "at least 3–4 sentences": what it does,
  when to use it, when not to, and what it returns. Detailed means precise,
  not padded.

### Skill

Skills load progressively (name and description first, full body on demand),
so the description carries the trigger and the boundary, with strong trigger
words near the beginning:

```md
---
name: repo-cli-usage
description: Use when you need to inspect, plan, validate, or apply repository changes through repo-cli. Do not use for generic shell commands when repo-cli has no matching subcommand.
---
```

Inside `SKILL.md`, do not paste the full CLI help. Only include:

1. When to use the CLI.
2. When not to use it.
3. Required agent flags.
4. 5–10 common commands.
5. The JSON envelope.
6. Error-handling rules.

Pin the `schema_version` the skill was written against.

---

## Anti-Patterns

### 1. One universal command

```bash
ai-helper run --task "..."
```

The agent must invent the intent, the parameters, and the format.

### 2. Long narrative output

Avoid:

```text
I scanned your project and noticed several interesting things...
```

Prefer:

```json
{ "schema_version": 1, "ok": true, "command": "scan", "summary": "Found 2 issues.", "data": { "issues": [] } }
```

### 3. Logs mixed with JSON

Avoid:

```text
Scanning files...
Almost done...
{"ok": true}
```

Prefer:

```text
stdout: {"ok": true, ...}
stderr: Scanning files...
```

### 4. Dangerous defaults

Avoid a write command that acts with no flags:

```bash
repo-cli cleanup
```

Require explicit intent; `--yes` only because deleting data is irreversible:

```bash
repo-cli cleanup --dry-run --json
repo-cli cleanup --apply --yes --json
```

### 5. Overly long help output

A huge `--help` page costs the agent tokens on every lookup. Use layered help
and `--schema` (see [Discoverability](#discoverability---schema-and-layered-help)).

### 6. Competing conventions

Two ways to say the same thing (`--apply` and `--apply-mode write`; `--force`
and `--risk-level high`) make the agent guess which one is canonical. One
convention per CLI.

---

## Contract Tests

The contract is only real if it is tested:

1. Every command's `--json` output validates against its own `--schema`, on
   success and on every error path.
2. Every write command fails with `MISSING_REQUIRED_FLAG` when called without
   `--dry-run`/`--apply`, and `--dry-run` produces no filesystem change.
3. Under `--no-input` with a non-TTY `stdin`, no command blocks; prompts become
   `INPUT_REQUIRED`.
4. An injected uncaught exception still yields a valid JSON error envelope.
5. Golden-file tests pin `--help` length and default response size.
6. Enum and command renames fail the test suite unless a deprecated alias and
   a `warnings` entry exist.

Contract tests prove the CLI keeps its promises; they do not prove an agent
uses it well. Anthropic recommends evaluations: realistic tasks that need
many tool calls, each "paired with a verifiable response or outcome", run on
held-out prompts. Track success rate, number of calls, tokens, and tool
errors; frequent redundant calls point to bad defaults in pagination or
limits.

---

## Command Design Checklist

Every new command answers these questions before implementation:

```md
Command:
- Name / domain / verb:
- Kind: read | plan | write
- Idempotent? Lock needed?

Agent safety:
- --json, --no-input supported
- --dry-run/--apply enforced (write), --yes required (irreversible)
- No prompts, colors, or pager on non-TTY
- Stable exit code and error.code

Arguments:
- Required / optional / enums / defaults
- Mutually exclusive / required combinations (expressed in --schema)
- Paths confined to --cwd
- Large inputs via --input <file> or stdin

Output:
- Success shape, error shape, input echo
- Default size, --limit default, pagination, --output strategy
- Untrusted text kept inside data; secrets redacted

Errors:
- Error codes it can return
- Which are retryable
- suggested_fix for argument errors

Docs:
- One-line index description
- 3–4 sentence --schema description: what, when to use, when not, returns
- 3 examples
- Contract tests and an agent eval task added
```

---

## Complete Example

```bash
repo-cli migration plan \
  --from redux-legacy --to redux-current \
  --scope package --target frontend-erp \
  --strategy minimal --json
```

```json
{
  "schema_version": 1,
  "ok": true,
  "command": "migration.plan",
  "input": {
    "cwd": "/repo",
    "from": "redux-legacy",
    "to": "redux-current",
    "scope": "package",
    "target": "frontend-erp",
    "strategy": "minimal"
  },
  "summary": "Generated a minimal Redux legacy upgrade plan for frontend-erp.",
  "data": {
    "plan_id": "mig_20260702_001",
    "risk_level": "medium",
    "estimated_files": 8,
    "requires_dependency_change": true,
    "requires_manual_review": true
  },
  "warnings": [],
  "next_actions": [
    {
      "command": "repo-cli migration plan show --plan-id mig_20260702_001 --json",
      "reason": "Inspect the full plan."
    },
    {
      "command": "repo-cli migration apply --plan-id mig_20260702_001 --dry-run --json",
      "reason": "Preview file changes without writing."
    }
  ]
}
```

---

## Final Rule

For an agent to use a CLI correctly, the CLI must minimize open-ended
decisions:

```text
Intent in the subcommand.
Data in explicit flags, echoed back as resolved input.
Variable values in enums.
Long text in files or stdin.
One JSON object on stdout; humans on stderr.
Errors that name the field, the valid values, and the fix.
Writes always split into --dry-run / --apply.
Contract in --schema, tested; docs compact and pinned to schema_version.
```

The goal is not for the CLI to explain more. The goal is for the CLI to make
explanation unnecessary.

---

## Exposing the CLI as an MCP Tool

The contract maps one-to-one, so wrapping a command is mechanical:

| CLI | MCP (2025-11-25) |
|---|---|
| `<command> --schema` → `input` | `inputSchema` |
| `<command> --schema` → `output` | `outputSchema` |
| `stdout` JSON object | `structuredContent`, plus the same JSON serialized in a `TextContent` block |
| `ok: false` (any `error.code`) | `isError: true` — a tool execution error, which the client passes to the model |
| Unknown command | JSON-RPC protocol error (e.g. `-32602`) |
| Command name `deps.plan-update` | Tool name: 1–128 characters from `A-Z a-z 0-9 _ - .` |

Argument errors are tool execution errors, not protocol errors: MCP reserves
protocol errors for unknown tools and malformed requests, and wants the
model to see validation errors so it can correct itself.

---

## Deviations from Standards

Deliberate choices where this guide goes beyond, or against, a published
convention:

| Choice | Standard | Why this guide differs |
|---|---|---|
| `--apply` is required to write | clig.dev: confirm interactively, or `-f/--force` when not interactive | `--force` usually means "override a safety check" (git); agents always run non-interactively, so the go-ahead must be explicit and unambiguous |
| `--yes` *plus* `--apply` for irreversible ops | gh uses `--yes` alone; clig.dev suggests `--confirm=<name>` for severe ops | Two independent flags make an accidental irreversible call less likely; `--confirm=<name>` is an acceptable stricter alternative |
| Exit codes `3` and `4` | Only `0`/non-zero is standard; `2` = usage is common practice | Lets shell callers branch on "fix precondition" vs "retry later" without parsing JSON |
| No help page on error under `--json` | clig.dev shows concise help when required args are missing | Help text is tokens the agent does not need; `suggested_fix` carries the fix |
| `PARTIAL_SUCCESS` | AIP-193: APIs should not support partial errors | Batch CLI commands do partially succeed; reporting what finished lets the agent resume |
| Untrusted text only inside `data` | Not covered by the sources read | Limits prompt injection through tool output; treat as a project rule |

---

## Sources

Read and checked on 2026-11-24:

- Command Line Interface Guidelines — https://clig.dev
- Model Context Protocol, Tools (2025-11-25) — https://modelcontextprotocol.io/specification/2025-11-25/server/tools
- Anthropic, Writing effective tools for agents — https://www.anthropic.com/engineering/writing-tools-for-agents
- Anthropic, Define tools — https://platform.claude.com/docs/en/agents-and-tools/tool-use/implement-tool-use
- JSON Schema, Conditional subschemas — https://json-schema.org/understanding-json-schema/reference/conditionals
- JSON Schema 2020-12 Core — https://json-schema.org/draft/2020-12/json-schema-core
- Google AIP-193, Errors — https://google.aip.dev/193
- Google AIP-194, Automatic retry configuration — https://google.aip.dev/194
- RFC 9457, Problem Details for HTTP APIs — https://www.rfc-editor.org/rfc/rfc9457.html
- FreeBSD sysexits(3) — https://man.freebsd.org/cgi/man.cgi?query=sysexits
- POSIX Shell Command Language, Exit Status for Commands — https://pubs.opengroup.org/onlinepubs/9799919799/utilities/V3_chap02.html
- Python argparse — https://docs.python.org/3/library/argparse.html
- git clean — https://git-scm.com/docs/git-clean
- gh repo delete — https://cli.github.com/manual/gh_repo_delete
- kubectl apply — https://kubernetes.io/docs/reference/kubectl/generated/kubectl_apply/
- npm install (`--dry-run`) — https://docs.npmjs.com/cli/v10/commands/npm-install
