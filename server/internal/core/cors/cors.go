// Package cors adds the minimal CORS support the web console needs to call
// the API from the browser. One exact origin (CAUCE_CORS_ORIGIN); stdlib only.
package cors

import "net/http"

// Middleware wraps next with CORS handling for the single allowed origin.
// An empty origin disables CORS: requests pass through untouched, exactly as
// before. A matching preflight (OPTIONS with the allowed Origin and an
// Access-Control-Request-Method) is answered here with 204 and never reaches
// next, so the method-aware Go 1.22 mux does not answer it with 405.
func Middleware(origin string, next http.Handler) http.Handler {
	if origin == "" {
		return next
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Origin") != origin {
			next.ServeHTTP(w, r)
			return
		}
		h := w.Header()
		h.Set("Access-Control-Allow-Origin", origin)
		// The web fetches with credentials (Cloudflare Access cookie); browsers reject
		// a credentialed response without this. Safe: origin is one exact value.
		h.Set("Access-Control-Allow-Credentials", "true")
		h.Add("Vary", "Origin")
		h.Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		h.Set("Access-Control-Allow-Headers", "Content-Type")
		h.Set("Access-Control-Max-Age", "600")
		if r.Method == http.MethodOptions && r.Header.Get("Access-Control-Request-Method") != "" {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}
