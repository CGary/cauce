package sync

import (
	"encoding/json"
	"io"
	"net/http"
	"strconv"
	"unicode/utf8"

	"cauce/server/internal/auth"
)

const (
	maxPushBody = 16 << 20
	maxDeviceID = 64 // envelope.v1.json device_id maxLength
)

// PushHandler serves POST /sync/push.
func (p *Pusher) PushHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := auth.UserFromRequest(r)
		if userID == "" {
			writeError(w, http.StatusInternalServerError, "server user is not configured")
			return
		}
		body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxPushBody))
		if err != nil {
			writeError(w, http.StatusBadRequest, "cannot read body: "+err.Error())
			return
		}
		result, err := p.Push(r.Context(), userID, body)
		if err != nil {
			writeError(w, http.StatusBadRequest, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, result)
	}
}

// PullHandler serves GET /sync/pull?since=<server_seq>[&device_id=<id>].
// device_id is optional (D22); when present it must be 1..64 characters, the
// same rule as the envelope.
func (p *Puller) PullHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := auth.UserFromRequest(r)
		if userID == "" {
			writeError(w, http.StatusInternalServerError, "server user is not configured")
			return
		}
		since := int64(0)
		if raw := r.URL.Query().Get("since"); raw != "" {
			parsed, err := strconv.ParseInt(raw, 10, 64)
			if err != nil || parsed < 0 {
				writeError(w, http.StatusBadRequest, "since must be a non-negative integer")
				return
			}
			since = parsed
		}
		deviceID := ""
		if q := r.URL.Query(); q.Has("device_id") {
			deviceID = q.Get("device_id")
			if n := utf8.RuneCountInString(deviceID); n < 1 || n > maxDeviceID {
				writeError(w, http.StatusBadRequest, "device_id must be 1..64 characters")
				return
			}
		}
		result, err := p.Pull(r.Context(), userID, deviceID, since)
		if err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, result)
	}
}

// Handler serves GET /sync/devices.
func (d *Devices) Handler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := auth.UserFromRequest(r)
		if userID == "" {
			writeError(w, http.StatusInternalServerError, "server user is not configured")
			return
		}
		result, err := d.List(r.Context(), userID)
		if err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, result)
	}
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}
