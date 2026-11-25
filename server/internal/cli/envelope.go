package cli

import "fmt"

// SchemaVersion is the version of the JSON envelope and of every `data` shape.
// Bump it only on a breaking change; the skill pins it.
const SchemaVersion = 1

// Envelope is the one JSON object every command prints on stdout under --json,
// on success, on error and on a crash (docs/GUIA-CLI-AGENTES.md, "JSON Contract").
type Envelope struct {
	SchemaVersion int            `json:"schema_version"`
	OK            bool           `json:"ok"`
	Command       string         `json:"command"`
	Input         map[string]any `json:"input"`
	Summary       string         `json:"summary,omitempty"`
	Data          any            `json:"data,omitempty"`
	Warnings      []string       `json:"warnings"`
	NextActions   []NextAction   `json:"next_actions"`
	Error         *ErrorBody     `json:"error,omitempty"`
	Retryable     *bool          `json:"retryable,omitempty"`
	SuggestedFix  *SuggestedFix  `json:"suggested_fix,omitempty"`
}

// NextAction is a command the CLI suggests from its own state. It never
// carries user-written text, --yes, or --apply on an irreversible command.
type NextAction struct {
	Command string `json:"command"`
	Reason  string `json:"reason"`
}

// ErrorBody is the `error` object of a failed command.
type ErrorBody struct {
	Code     string   `json:"code"`
	Message  string   `json:"message"`
	Field    string   `json:"field,omitempty"`
	Received any      `json:"received,omitempty"`
	Allowed  []string `json:"allowed,omitempty"`
}

// SuggestedFix is the mechanical correction of an argument error.
type SuggestedFix struct {
	Command string `json:"command"`
	Then    string `json:"then,omitempty"`
}

// Error codes. Stable once published (AIP-193 format).
const (
	CodeInvalidArgument     = "INVALID_ARGUMENT"
	CodeMissingRequiredFlag = "MISSING_REQUIRED_FLAG"
	CodeInvalidEnum         = "INVALID_ENUM"
	CodeUnsupported         = "UNSUPPORTED"
	CodeValidationFailed    = "VALIDATION_FAILED"
	CodePermissionDenied    = "PERMISSION_DENIED"
	CodeRemedioScheduleLock = "REMEDIO_SCHEDULE_LOCKED"
	CodeConflict            = "CONFLICT"
	CodeInternal            = "INTERNAL_ERROR"
	CodeNotFound            = "NOT_FOUND"
	CodeFileNotFound        = "FILE_NOT_FOUND"
	CodeConfigNotFound      = "CONFIG_NOT_FOUND"
	CodePlanNotFound        = "PLAN_NOT_FOUND"
	CodePlanExpired         = "PLAN_EXPIRED"
	CodeInputRequired       = "INPUT_REQUIRED"
	CodeNetworkError        = "NETWORK_ERROR"
)

// Exit codes (docs/GUIA-CLI-AGENTES.md, "Exit codes").
const (
	exitOK           = 0
	exitFail         = 1
	exitUs           = 2
	exitPrecondition = 3
	exitNetwork      = 4
)

// errorCodes maps every code to its exit status and retry behaviour.
var errorCodes = map[string]struct {
	exit      int
	retryable bool
}{
	CodeInvalidArgument:     {exitUs, false},
	CodeMissingRequiredFlag: {exitUs, false},
	CodeInvalidEnum:         {exitUs, false},
	CodeUnsupported:         {exitUs, false},
	CodeValidationFailed:    {exitFail, false},
	CodePermissionDenied:    {exitFail, false},
	CodeRemedioScheduleLock: {exitFail, false},
	CodeConflict:            {exitFail, false},
	CodeInternal:            {exitFail, false},
	CodeNotFound:            {exitPrecondition, false},
	CodeFileNotFound:        {exitPrecondition, false},
	CodeConfigNotFound:      {exitPrecondition, false},
	CodePlanNotFound:        {exitPrecondition, false},
	CodePlanExpired:         {exitPrecondition, false},
	CodeInputRequired:       {exitPrecondition, false},
	CodeNetworkError:        {exitNetwork, true},
}

// allErrorCodes lists the codes in a stable order for --schema.
func allErrorCodes() []string {
	return []string{
		CodeInvalidArgument, CodeMissingRequiredFlag, CodeInvalidEnum, CodeUnsupported,
		CodeValidationFailed, CodePermissionDenied, CodeRemedioScheduleLock, CodeConflict,
		CodeInternal, CodeNotFound, CodeFileNotFound, CodeConfigNotFound, CodePlanNotFound,
		CodePlanExpired, CodeInputRequired, CodeNetworkError,
	}
}

// cliError is a failure that becomes an error envelope.
type cliError struct {
	Code     string
	Message  string
	Field    string
	Received any
	Allowed  []string
	Fix      *SuggestedFix
	Data     any
}

func (e *cliError) Error() string { return e.Code + ": " + e.Message }

func errorf(code, format string, args ...any) *cliError {
	return &cliError{Code: code, Message: fmt.Sprintf(format, args...)}
}

func (e *cliError) field(name string) *cliError { e.Field = name; return e }

func (e *cliError) fix(command string) *cliError { e.Fix = &SuggestedFix{Command: command}; return e }

func exitCodeFor(code string) int {
	if c, ok := errorCodes[code]; ok {
		return c.exit
	}
	return exitFail
}

func errorEnvelope(command string, input map[string]any, e *cliError) *Envelope {
	if _, known := errorCodes[e.Code]; !known {
		e = &cliError{Code: CodeInternal, Message: e.Message}
	}
	retryable := errorCodes[e.Code].retryable
	if input == nil {
		input = map[string]any{}
	}
	return &Envelope{
		SchemaVersion: SchemaVersion,
		OK:            false,
		Command:       command,
		Input:         input,
		Data:          e.Data,
		Warnings:      []string{},
		NextActions:   []NextAction{},
		Error: &ErrorBody{
			Code:     e.Code,
			Message:  e.Message,
			Field:    e.Field,
			Received: e.Received,
			Allowed:  e.Allowed,
		},
		Retryable:    &retryable,
		SuggestedFix: e.Fix,
	}
}
