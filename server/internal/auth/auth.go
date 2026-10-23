// Package auth resolves the single user identity. Phase 2 trusts the network
// behind Cloudflare Tunnel + Access and reads CAUCE_USER_ID; this function is
// the only place that changes if a second user ever exists.
package auth

import (
	"net/http"
	"os"
	"strings"
)

// EnvUserID is the environment variable holding the configured user id.
const EnvUserID = "CAUCE_USER_ID"

// UserFromRequest returns the authenticated user id. It deliberately ignores
// request headers and bodies: callers must never learn identity from payloads.
func UserFromRequest(r *http.Request) string {
	_ = r
	return strings.TrimSpace(os.Getenv(EnvUserID))
}
