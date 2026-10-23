package sync

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"cauce/server/internal/core/event"
	"cauce/server/internal/core/store"
)

const validEvent = `{
	"id": "e1",
	"user_id": "u1",
	"device_id": "test",
	"type": "alarma_creada",
	"v": 2,
	"ts": "2026-10-20T10:00:00Z",
	"payload": {
		"alarma_id": "a1",
		"titulo": "Vitaminas",
		"tipo": "otro",
		"hora": "08:00",
		"repeticion": {"freq": "DAILY"}
	}
}`

func newPusher(t *testing.T) *Pusher {
	t.Helper()
	t.Setenv("CAUCE_USER_ID", "u1")
	st, err := store.Open(context.Background(), filepath.Join(t.TempDir(), "cauce.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	validator, err := event.NewValidator()
	if err != nil {
		t.Fatal(err)
	}
	return &Pusher{Store: st, Validator: validator}
}

func TestPushPullRoundTrip(t *testing.T) {
	pusher := newPusher(t)
	puller := &Puller{Store: pusher.Store}

	push := httptest.NewRequest(http.MethodPost, "/sync/push", strings.NewReader(`{"events":[`+validEvent+`]}`))
	rec := httptest.NewRecorder()
	pusher.PushHandler()(rec, push)
	if rec.Code != http.StatusOK {
		t.Fatalf("push status = %d: %s", rec.Code, rec.Body.String())
	}
	var pushed PushResult
	if err := json.Unmarshal(rec.Body.Bytes(), &pushed); err != nil {
		t.Fatal(err)
	}
	if pushed.Accepted != 1 || pushed.Duplicates != 0 {
		t.Fatalf("push result = %+v", pushed)
	}

	rec = httptest.NewRecorder()
	push = httptest.NewRequest(http.MethodPost, "/sync/push", strings.NewReader(`{"events":[`+validEvent+`]}`))
	pusher.PushHandler()(rec, push)
	if err := json.Unmarshal(rec.Body.Bytes(), &pushed); err != nil {
		t.Fatal(err)
	}
	if pushed.Accepted != 0 || pushed.Duplicates != 1 {
		t.Fatalf("duplicate push result = %+v", pushed)
	}

	rec = httptest.NewRecorder()
	pull := httptest.NewRequest(http.MethodGet, "/sync/pull?since=0", nil)
	puller.PullHandler()(rec, pull)
	if rec.Code != http.StatusOK {
		t.Fatalf("pull status = %d: %s", rec.Code, rec.Body.String())
	}
	var pulled PullResult
	if err := json.Unmarshal(rec.Body.Bytes(), &pulled); err != nil {
		t.Fatal(err)
	}
	if len(pulled.Events) != 1 {
		t.Fatalf("pull events = %d", len(pulled.Events))
	}
	if pulled.Events[0].ID != "e1" || pulled.Events[0].ServerSeq == nil {
		t.Fatalf("pull event = %+v", pulled.Events[0])
	}
	if pulled.NextSince != *pulled.Events[0].ServerSeq {
		t.Fatalf("next_since = %d, want %d", pulled.NextSince, *pulled.Events[0].ServerSeq)
	}

	rec = httptest.NewRecorder()
	pull = httptest.NewRequest(http.MethodGet, fmt.Sprintf("/sync/pull?since=%d", pulled.NextSince), nil)
	puller.PullHandler()(rec, pull)
	if err := json.Unmarshal(rec.Body.Bytes(), &pulled); err != nil {
		t.Fatal(err)
	}
	if len(pulled.Events) != 0 || pulled.NextSince == 0 {
		t.Fatalf("second pull = %+v", pulled)
	}
}

func TestPushRejectsWholeBatch(t *testing.T) {
	pusher := newPusher(t)
	bad := strings.Replace(validEvent, `"hora": "08:00"`, `"hora": "8:00"`, 1)
	body := `{"events":[` + validEvent + `,` + bad + `]}`

	rec := httptest.NewRecorder()
	pusher.PushHandler()(rec, httptest.NewRequest(http.MethodPost, "/sync/push", strings.NewReader(body)))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400: %s", rec.Code, rec.Body.String())
	}
	events, err := pusher.Store.Events(context.Background(), "u1")
	if err != nil {
		t.Fatal(err)
	}
	if len(events) != 0 {
		t.Fatalf("stored %d events from a rejected batch", len(events))
	}
}

func TestPushRejectsForeignUser(t *testing.T) {
	pusher := newPusher(t)
	foreign := strings.Replace(validEvent, `"user_id": "u1"`, `"user_id": "u2"`, 1)
	rec := httptest.NewRecorder()
	pusher.PushHandler()(rec, httptest.NewRequest(http.MethodPost, "/sync/push", strings.NewReader(`{"events":[`+foreign+`]}`)))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
}

func TestPullRejectsBadSince(t *testing.T) {
	pusher := newPusher(t)
	puller := &Puller{Store: pusher.Store}
	for _, since := range []string{"abc", "-1", "1.5"} {
		rec := httptest.NewRecorder()
		puller.PullHandler()(rec, httptest.NewRequest(http.MethodGet, "/sync/pull?since="+since, nil))
		if rec.Code != http.StatusBadRequest {
			t.Errorf("since=%q status = %d, want 400", since, rec.Code)
		}
	}
}
