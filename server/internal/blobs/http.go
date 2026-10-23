package blobs

import (
	"encoding/json"
	"net/http"

	"cauce/server/internal/auth"
)

type presignRequest struct {
	Key         string `json:"key"`
	ContentType string `json:"content_type"`
}

type presignResponse struct {
	URL       string `json:"url"`
	ExpiresIn int    `json:"expires_in"`
}

// Handler serves POST /blobs/presign.
type Handler struct {
	Presigner *Presigner
}

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if h.Presigner == nil {
		writeError(w, http.StatusServiceUnavailable, "blob storage is not configured")
		return
	}
	userID := auth.UserFromRequest(r)
	if userID == "" {
		writeError(w, http.StatusInternalServerError, "server user is not configured")
		return
	}
	var req presignRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body: "+err.Error())
		return
	}
	url, err := h.Presigner.PresignPut(r.Context(), userID, req.Key, req.ContentType)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, presignResponse{URL: url, ExpiresIn: int(PresignExpiry.Seconds())})
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}
