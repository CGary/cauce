package projections

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"cauce/server/internal/core/event"
	"cauce/server/internal/core/projection"
	"cauce/server/internal/core/store"
)

func newHandler(t *testing.T, proj projection.Projection, now time.Time) *Handler {
	t.Helper()
	t.Setenv("CAUCE_USER_ID", "u1")
	st, err := store.Open(context.Background(), filepath.Join(t.TempDir(), "cauce.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	if proj != nil {
		reg := projection.NewRegistry()
		reg.MustRegister(proj)
		return &Handler{Store: st, Registry: reg, Now: func() time.Time { return now }}
	}
	return &Handler{Store: st, Registry: projection.NewRegistry(), Now: func() time.Time { return now }}
}

func serve(t *testing.T, h *Handler, target string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, target, nil)
	req.SetPathValue("feature", "demo")
	req.SetPathValue("name", "echo")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func TestInjectsServerClockWhenNowMissing(t *testing.T) {
	fixed := time.Date(2026, 10, 22, 13, 0, 0, 0, time.UTC)
	var seen string
	proj := projection.Func{
		ProjectionKey: "demo.echo",
		RunFunc: func(events []event.Envelope, params projection.Params) (any, error) {
			seen, _, _ = params.String("now")
			return map[string]string{"now": seen}, nil
		},
	}
	h := newHandler(t, proj, fixed)

	rec := serve(t, h, "/api/projections/demo/echo?tz=America/La_Paz")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d: %s", rec.Code, rec.Body.String())
	}
	if seen != "2026-10-22T13:00:00Z" {
		t.Fatalf("projection received now = %q", seen)
	}
}

func TestExplicitNowWins(t *testing.T) {
	var seen string
	proj := projection.Func{
		ProjectionKey: "demo.echo",
		RunFunc: func(events []event.Envelope, params projection.Params) (any, error) {
			seen, _, _ = params.String("now")
			return map[string]string{"now": seen}, nil
		},
	}
	h := newHandler(t, proj, time.Now())

	rec := serve(t, h, "/api/projections/demo/echo?now=2026-10-20T10:00:00Z")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d: %s", rec.Code, rec.Body.String())
	}
	if seen != "2026-10-20T10:00:00Z" {
		t.Fatalf("projection received now = %q", seen)
	}
}

func TestUnknownProjection(t *testing.T) {
	h := newHandler(t, nil, time.Now())
	rec := serve(t, h, "/api/projections/demo/echo")
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rec.Code)
	}
}

func TestProjectionErrorIsBadRequest(t *testing.T) {
	proj := projection.Func{
		ProjectionKey: "demo.echo",
		RunFunc: func([]event.Envelope, projection.Params) (any, error) {
			return nil, errors.New("bad param")
		},
	}
	h := newHandler(t, proj, time.Now())
	rec := serve(t, h, "/api/projections/demo/echo")
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
	var body map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body["error"] != "bad param" {
		t.Fatalf("error = %q", body["error"])
	}
}
