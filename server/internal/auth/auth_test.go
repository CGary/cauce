package auth

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

const testToken = "test-token-0123456789abcdefghijklmnop" // invented, 37 chars

func TestUserFromRequest(t *testing.T) {
	t.Setenv(EnvUserID, "  u1  ")
	if got := UserFromRequest(httptest.NewRequest("GET", "/", nil)); got != "u1" {
		t.Fatalf("user = %q", got)
	}
}

func TestRequire(t *testing.T) {
	inner := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	h := Require(Config{Enabled: true, Token: testToken}, inner)

	tests := []struct {
		name   string
		method string
		path   string
		header string
		want   int
	}{
		{"missing header", "GET", "/sync/pull?since=0", "", http.StatusUnauthorized},
		{"wrong scheme", "GET", "/sync/pull?since=0", "Basic " + testToken, http.StatusUnauthorized},
		{"scheme without token", "GET", "/sync/pull?since=0", "Bearer ", http.StatusUnauthorized},
		{"wrong token", "POST", "/sync/push", "Bearer " + testToken + "x", http.StatusUnauthorized},
		{"token prefix", "POST", "/sync/push", "Bearer " + testToken[:20], http.StatusUnauthorized},
		{"right token", "GET", "/api/projections/alarmas/alarmas_activas", "Bearer " + testToken, http.StatusOK},
		{"scheme is case-insensitive", "GET", "/sync/pull", "bearer " + testToken, http.StatusOK},
		{"healthz open", "GET", "/healthz", "", http.StatusOK},
		{"healthz HEAD open", "HEAD", "/healthz", "", http.StatusOK},
		{"healthz POST needs token", "POST", "/healthz", "", http.StatusUnauthorized},
		{"unknown route needs token", "GET", "/nope", "", http.StatusUnauthorized},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := httptest.NewRequest(tt.method, tt.path, nil)
			if tt.header != "" {
				req.Header.Set("Authorization", tt.header)
			}
			rec := httptest.NewRecorder()
			h.ServeHTTP(rec, req)
			if rec.Code != tt.want {
				t.Fatalf("status = %d, want %d", rec.Code, tt.want)
			}
			if tt.want == http.StatusUnauthorized {
				if got := rec.Header().Get("WWW-Authenticate"); !strings.HasPrefix(got, "Bearer") {
					t.Fatalf("WWW-Authenticate = %q", got)
				}
				if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
					t.Fatalf("Content-Type = %q", ct)
				}
				if !strings.Contains(rec.Body.String(), `"error"`) {
					t.Fatalf("body = %q", rec.Body.String())
				}
			}
		})
	}
}

func TestRequireDisabledPassesThrough(t *testing.T) {
	h := Require(Config{Enabled: false}, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusTeapot)
	}))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest("GET", "/sync/pull", nil))
	if rec.Code != http.StatusTeapot {
		t.Fatalf("status = %d", rec.Code)
	}
}

func TestConfigFromEnv(t *testing.T) {
	tests := []struct {
		name        string
		token       string
		mode        string
		wantErr     string
		wantEnabled bool
	}{
		{name: "startup refused without token", wantErr: EnvAPIToken + " is empty"},
		{name: "startup refused with blank token", token: "   ", wantErr: EnvAPIToken + " is empty"},
		{name: "startup refused with short token", token: strings.Repeat("a", MinTokenLength-1), wantErr: "too short"},
		{name: "startup refused with unknown mode", token: testToken, mode: "maybe", wantErr: "not valid"},
		{name: "minimum length accepted", token: strings.Repeat("a", MinTokenLength), wantEnabled: true},
		{name: "token enables auth", token: testToken, wantEnabled: true},
		{name: "explicit on", token: testToken, mode: "on", wantEnabled: true},
		{name: "off without token", mode: "off"},
		{name: "off wins over token", token: testToken, mode: "off"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Setenv(EnvAPIToken, tt.token)
			t.Setenv(EnvAuthMode, tt.mode)
			cfg, err := ConfigFromEnv()
			if tt.wantErr != "" {
				if err == nil || !strings.Contains(err.Error(), tt.wantErr) {
					t.Fatalf("err = %v, want %q", err, tt.wantErr)
				}
				if strings.TrimSpace(tt.token) != "" && strings.Contains(err.Error(), strings.TrimSpace(tt.token)) {
					t.Fatalf("error leaks the token: %v", err)
				}
				return
			}
			if err != nil {
				t.Fatalf("err = %v", err)
			}
			if cfg.Enabled != tt.wantEnabled {
				t.Fatalf("enabled = %v, want %v", cfg.Enabled, tt.wantEnabled)
			}
			if strings.Contains(cfg.Describe(), testToken) {
				t.Fatalf("Describe leaks the token")
			}
		})
	}
}
