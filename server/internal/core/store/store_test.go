package store

import (
	"context"
	"encoding/json"
	"path/filepath"
	"testing"
	"time"

	"cauce/server/internal/core/event"
)

func testStore(t *testing.T) *Store {
	t.Helper()
	st, err := Open(context.Background(), filepath.Join(t.TempDir(), "cauce.db"))
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { st.Close() })
	return st
}

func env(id, user, typ string) event.Envelope {
	return event.Envelope{
		ID:       id,
		UserID:   user,
		DeviceID: "test",
		Type:     typ,
		V:        1,
		TS:       "2026-10-22T10:00:00Z",
		Payload:  json.RawMessage(`{}`),
	}
}

func TestInsertBatchAndPull(t *testing.T) {
	ctx := context.Background()
	st := testStore(t)

	events := []event.Envelope{
		env("e1", "u1", "a"),
		env("e2", "u1", "b"),
		env("e3", "u2", "a"),
	}
	accepted, duplicates, err := st.InsertBatch(ctx, events, time.Date(2026, 10, 22, 12, 0, 0, 0, time.UTC))
	if err != nil {
		t.Fatalf("InsertBatch: %v", err)
	}
	if accepted != 3 || duplicates != 0 {
		t.Fatalf("accepted=%d duplicates=%d, want 3/0", accepted, duplicates)
	}

	accepted, duplicates, err = st.InsertBatch(ctx, []event.Envelope{env("e1", "u1", "a")}, time.Now())
	if err != nil {
		t.Fatalf("InsertBatch duplicate: %v", err)
	}
	if accepted != 0 || duplicates != 1 {
		t.Fatalf("accepted=%d duplicates=%d, want 0/1", accepted, duplicates)
	}

	page, err := st.Pull(ctx, "u1", 0, 500)
	if err != nil {
		t.Fatalf("Pull: %v", err)
	}
	if len(page) != 2 {
		t.Fatalf("pull len = %d, want 2 (only u1)", len(page))
	}
	if page[0].ID != "e1" || page[1].ID != "e2" {
		t.Fatalf("pull order = %s,%s", page[0].ID, page[1].ID)
	}
	if page[0].ServerSeq == nil || *page[0].ServerSeq >= *page[1].ServerSeq {
		t.Fatalf("server_seq not increasing: %+v", page)
	}

	next, err := st.Pull(ctx, "u1", *page[1].ServerSeq, 500)
	if err != nil {
		t.Fatalf("Pull after cursor: %v", err)
	}
	if len(next) != 0 {
		t.Fatalf("pull after cursor len = %d, want 0", len(next))
	}

	all, err := st.Events(ctx, "u1")
	if err != nil {
		t.Fatalf("Events: %v", err)
	}
	if len(all) != 2 {
		t.Fatalf("events len = %d, want 2", len(all))
	}
}

func TestPullLimit(t *testing.T) {
	ctx := context.Background()
	st := testStore(t)
	for _, id := range []string{"e1", "e2", "e3"} {
		if _, _, err := st.InsertBatch(ctx, []event.Envelope{env(id, "u1", "a")}, time.Now()); err != nil {
			t.Fatal(err)
		}
	}
	page, err := st.Pull(ctx, "u1", 0, 2)
	if err != nil {
		t.Fatal(err)
	}
	if len(page) != 2 {
		t.Fatalf("pull len = %d, want limit 2", len(page))
	}
}
