// Package projections exposes read models to the web console over HTTP. It
// never duplicates projection logic: it runs the same registry the conformance
// tests exercise.
package projections

import (
	"encoding/json"
	"net/http"
	"time"

	"cauce/server/internal/auth"
	"cauce/server/internal/core/event"
	"cauce/server/internal/core/projection"
	"cauce/server/internal/core/store"
)

// Handler serves GET /api/projections/{feature}/{name}.
type Handler struct {
	Store    *store.Store
	Registry *projection.Registry
	// Now is the server clock used when the client omits params.now. Injecting
	// it here keeps the projection function pure and the web cache key stable.
	Now func() time.Time
}

// ServeHTTP runs one projection over the user's event log.
func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	userID := auth.UserFromRequest(r)
	if userID == "" {
		writeError(w, http.StatusInternalServerError, "server user is not configured")
		return
	}

	key := r.PathValue("feature") + "." + r.PathValue("name")
	proj, ok := h.Registry.Get(key)
	if !ok {
		writeError(w, http.StatusNotFound, "unknown projection "+key)
		return
	}

	params := projection.ParamsFromValues(r.URL.Query())
	if _, present := params["now"]; !present {
		now := time.Now
		if h.Now != nil {
			now = h.Now
		}
		raw, err := json.Marshal(now().UTC().Format(time.RFC3339))
		if err == nil {
			params["now"] = raw
		}
	}

	events, err := h.Store.Events(r.Context(), userID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	clean := make([]event.Envelope, len(events))
	for i, ev := range events {
		clean[i] = ev.Clone()
	}

	result, err := proj.Run(clean, params)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, result)
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}
