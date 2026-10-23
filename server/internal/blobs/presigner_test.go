package blobs

import (
	"context"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"cauce/server/internal/config"
)

func testPresigner() *Presigner {
	return New(config.R2{
		AccountID:       "account123",
		AccessKeyID:     "key",
		SecretAccessKey: "secret",
		Bucket:          "cauce",
	})
}

func TestPresignPut(t *testing.T) {
	p := testPresigner()
	raw, err := p.PresignPut(context.Background(), "u1", "u1/2026/10/22/nota.m4a", "audio/mp4")
	if err != nil {
		t.Fatalf("PresignPut: %v", err)
	}
	parsed, err := url.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	if parsed.Host != "account123.r2.cloudflarestorage.com" {
		t.Fatalf("host = %q", parsed.Host)
	}
	if !strings.Contains(parsed.Path, "/cauce/u1/2026/10/22/nota.m4a") {
		t.Fatalf("path = %q", parsed.Path)
	}
	if got := parsed.Query().Get("X-Amz-Expires"); got != "900" {
		t.Fatalf("X-Amz-Expires = %q", got)
	}
	if parsed.Query().Get("X-Amz-Signature") == "" {
		t.Fatal("missing signature")
	}
}

func TestPresignKeyPrefix(t *testing.T) {
	p := testPresigner()
	tests := []struct {
		name string
		key  string
		ok   bool
	}{
		{name: "valid", key: "u1/a.m4a", ok: true},
		{name: "nested", key: "u1/2026/10/22/a.m4a", ok: true},
		{name: "other user", key: "u2/a.m4a"},
		{name: "bare prefix", key: "u1/"},
		{name: "traversal", key: "u1/../u2/a.m4a"},
		{name: "double slash", key: "u1//a.m4a"},
		{name: "absolute", key: "/u1/a.m4a"},
		{name: "backslash", key: `u1\a.m4a`},
		{name: "empty", key: ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := p.PresignPut(context.Background(), "u1", tt.key, "audio/mp4")
			if tt.ok && err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if !tt.ok && err == nil {
				t.Fatal("want error")
			}
		})
	}
}

func TestHandlerUnconfigured(t *testing.T) {
	t.Setenv("CAUCE_USER_ID", "u1")
	rec := httptest.NewRecorder()
	(&Handler{}).ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/blobs/presign", strings.NewReader(`{}`)))
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503", rec.Code)
	}
}

func TestHandlerPresign(t *testing.T) {
	t.Setenv("CAUCE_USER_ID", "u1")
	body := `{"key":"u1/note.m4a","content_type":"audio/mp4"}`
	rec := httptest.NewRecorder()
	(&Handler{Presigner: testPresigner()}).ServeHTTP(rec,
		httptest.NewRequest(http.MethodPost, "/blobs/presign", strings.NewReader(body)))
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d: %s", rec.Code, rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), `"expires_in":900`) {
		t.Fatalf("body = %s", rec.Body.String())
	}

	rec = httptest.NewRecorder()
	body = `{"key":"u2/note.m4a","content_type":"audio/mp4"}`
	(&Handler{Presigner: testPresigner()}).ServeHTTP(rec,
		httptest.NewRequest(http.MethodPost, "/blobs/presign", strings.NewReader(body)))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("foreign key status = %d, want 400", rec.Code)
	}
}
