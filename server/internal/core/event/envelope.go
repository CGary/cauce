// Package event defines the event envelope shared by the server, the app and
// the web console. The JSON Schemas live in the embedded schema/ directory and
// are kept byte-identical to the repository's top-level schema/ tree by
// `go generate` (see gen/syncschema) and by TestEmbeddedSchemaInSync.
package event

import "encoding/json"

//go:generate go run ./gen/syncschema

// Envelope is the immutable event frame. Payload is validated separately
// against the schema for (Type, V).
type Envelope struct {
	ID        string          `json:"id"`
	UserID    string          `json:"user_id"`
	DeviceID  string          `json:"device_id"`
	Type      string          `json:"type"`
	V         int             `json:"v"`
	TS        string          `json:"ts"`
	Payload   json.RawMessage `json:"payload"`
	ServerSeq *int64          `json:"server_seq,omitempty"`
}

// Clone returns a shallow copy without the server-assigned cursor, which is the
// shape projections and fixtures receive.
func (e Envelope) Clone() Envelope {
	e.ServerSeq = nil
	return e
}
