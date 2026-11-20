// Package auth holds the server's two security seams (D9, D20):
//
//   - Require: one shared bearer token (CAUCE_API_TOKEN) in front of every
//     route except GET /healthz. The phone sends it directly; the web's Pages
//     Function adds it after verifying the Cloudflare Access login at the edge.
//   - UserFromRequest: the single user identity. Passing the token proves the
//     caller is one of the owner's clients; the identity is CAUCE_USER_ID. This
//     function is the only place that changes if a second user ever exists.
package auth

import (
	"crypto/subtle"
	"fmt"
	"net/http"
	"os"
	"strings"
)

const (
	// EnvUserID is the environment variable holding the configured user id.
	EnvUserID = "CAUCE_USER_ID"
	// EnvAPIToken holds the shared bearer token every client must send.
	EnvAPIToken = "CAUCE_API_TOKEN"
	// EnvAuthMode set to "off" disables the token check (local dev, isolated tests).
	EnvAuthMode = "CAUCE_AUTH"
	// MinTokenLength is the shortest token the server accepts at startup.
	MinTokenLength = 32
)

// UserFromRequest returns the authenticated user id. It deliberately ignores
// request headers and bodies: callers must never learn identity from payloads.
func UserFromRequest(r *http.Request) string {
	_ = r
	return strings.TrimSpace(os.Getenv(EnvUserID))
}

// Config is the token check as resolved at startup.
type Config struct {
	// Enabled is false only when CAUCE_AUTH=off was set explicitly.
	Enabled bool
	// Token is the expected bearer token; empty when Enabled is false.
	Token string
}

// Describe is the one startup log line. It never contains the token.
func (c Config) Describe() string {
	if !c.Enabled {
		return "auth: DISABLED (CAUCE_AUTH=off); every route is open, use only for local dev or isolated tests"
	}
	return "auth: bearer token required on every route except GET /healthz"
}

// ConfigFromEnv reads CAUCE_API_TOKEN and CAUCE_AUTH and fails closed: the
// server must refuse to start without a token unless CAUCE_AUTH=off is set.
func ConfigFromEnv() (Config, error) {
	switch mode := strings.TrimSpace(os.Getenv(EnvAuthMode)); mode {
	case "off":
		return Config{Enabled: false}, nil
	case "", "on":
	default:
		return Config{}, fmt.Errorf("%s=%q is not valid; use \"off\" to disable auth or leave it empty", EnvAuthMode, mode)
	}
	token := strings.TrimSpace(os.Getenv(EnvAPIToken))
	if token == "" {
		return Config{}, fmt.Errorf("%s is empty: set it (at least %d characters) or set %s=off for local development", EnvAPIToken, MinTokenLength, EnvAuthMode)
	}
	if len(token) < MinTokenLength {
		return Config{}, fmt.Errorf("%s is too short: it needs at least %d characters", EnvAPIToken, MinTokenLength)
	}
	return Config{Enabled: true, Token: token}, nil
}

// Require wraps next with the bearer check. GET (and HEAD) /healthz stay open
// so health probes need no secret. A failed check answers 401 with a small JSON
// body and WWW-Authenticate: Bearer, and never reaches next.
func Require(cfg Config, next http.Handler) http.Handler {
	if !cfg.Enabled {
		return next
	}
	want := []byte(cfg.Token)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/healthz" && (r.Method == http.MethodGet || r.Method == http.MethodHead) {
			next.ServeHTTP(w, r)
			return
		}
		got, ok := bearer(r.Header.Get("Authorization"))
		if !ok || subtle.ConstantTimeCompare([]byte(got), want) != 1 {
			w.Header().Set("WWW-Authenticate", `Bearer realm="cauce"`)
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"error":"falta el token de la API o no es válido"}`))
			return
		}
		next.ServeHTTP(w, r)
	})
}

// bearer extracts the token from an "Authorization: Bearer <token>" value.
// The scheme is case-insensitive (RFC 7235); the token is not.
func bearer(header string) (string, bool) {
	scheme, token, found := strings.Cut(strings.TrimSpace(header), " ")
	if !found || !strings.EqualFold(scheme, "Bearer") {
		return "", false
	}
	token = strings.TrimSpace(token)
	return token, token != ""
}
