package cors

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestMiddleware(t *testing.T) {
	const origin = "https://example.pages.dev"

	tests := []struct {
		name         string
		configured   string
		method       string
		reqOrigin    string
		reqACRM      string
		wantStatus   int
		wantHeaders  bool
		wantInnerHit bool
	}{
		{
			name:         "matching origin GET gets CORS headers",
			configured:   origin,
			method:       http.MethodGet,
			reqOrigin:    origin,
			wantStatus:   http.StatusAccepted,
			wantHeaders:  true,
			wantInnerHit: true,
		},
		{
			name:        "preflight is answered 204 without reaching the inner handler",
			configured:  origin,
			method:      http.MethodOptions,
			reqOrigin:   origin,
			reqACRM:     http.MethodPost,
			wantStatus:  http.StatusNoContent,
			wantHeaders: true,
		},
		{
			name:         "other origin passes through untouched",
			configured:   origin,
			method:       http.MethodGet,
			reqOrigin:    "https://evil.example",
			wantStatus:   http.StatusAccepted,
			wantInnerHit: true,
		},
		{
			name:         "absent origin passes through untouched",
			configured:   origin,
			method:       http.MethodGet,
			wantStatus:   http.StatusAccepted,
			wantInnerHit: true,
		},
		{
			name:         "disabled adds no headers",
			configured:   "",
			method:       http.MethodGet,
			reqOrigin:    origin,
			wantStatus:   http.StatusAccepted,
			wantInnerHit: true,
		},
		{
			name:         "disabled preflight reaches the inner handler as today",
			configured:   "",
			method:       http.MethodOptions,
			reqOrigin:    origin,
			reqACRM:      http.MethodPost,
			wantStatus:   http.StatusAccepted,
			wantInnerHit: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			innerHit := false
			inner := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				innerHit = true
				w.WriteHeader(http.StatusAccepted)
			})

			rec := httptest.NewRecorder()
			Middleware(tt.configured, inner).ServeHTTP(rec, func() *http.Request {
				r := httptest.NewRequest(tt.method, "/sync/push", nil)
				if tt.reqOrigin != "" {
					r.Header.Set("Origin", tt.reqOrigin)
				}
				if tt.reqACRM != "" {
					r.Header.Set("Access-Control-Request-Method", tt.reqACRM)
				}
				return r
			}())

			if rec.Code != tt.wantStatus {
				t.Fatalf("status = %d, want %d", rec.Code, tt.wantStatus)
			}
			if innerHit != tt.wantInnerHit {
				t.Fatalf("inner handler called = %v, want %v", innerHit, tt.wantInnerHit)
			}

			h := rec.Header()
			if got := h.Get("Access-Control-Allow-Origin"); got != tt.configured && (tt.wantHeaders || got != "") {
				t.Fatalf("Access-Control-Allow-Origin = %q", got)
			}
			if !tt.wantHeaders {
				if h.Get("Access-Control-Allow-Origin") != "" || h.Get("Vary") != "" {
					t.Fatalf("unexpected CORS headers: %v", h)
				}
				return
			}
			if got := h.Get("Vary"); got != "Origin" {
				t.Fatalf("Vary = %q, want %q", got, "Origin")
			}
			if got := h.Get("Access-Control-Allow-Methods"); got != "GET, POST, OPTIONS" {
				t.Fatalf("Access-Control-Allow-Methods = %q", got)
			}
			if got := h.Get("Access-Control-Allow-Headers"); got != "Content-Type" {
				t.Fatalf("Access-Control-Allow-Headers = %q", got)
			}
			if got := h.Get("Access-Control-Max-Age"); got != "600" {
				t.Fatalf("Access-Control-Max-Age = %q", got)
			}
		})
	}
}
