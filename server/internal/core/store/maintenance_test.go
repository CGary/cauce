package store

import (
	"context"
	"database/sql"
	"path/filepath"
	"testing"
	"time"

	"cauce/server/internal/core/event"
	_ "modernc.org/sqlite"
)

func TestCheck(t *testing.T) {
	tests := []struct {
		name    string
		prepare func(t *testing.T, path string)
		want    int64
		wantErr bool
	}{
		{name: "valid empty database", prepare: func(t *testing.T, path string) {
			st, err := Open(context.Background(), path)
			if err != nil {
				t.Fatal(err)
			}
			defer st.Close()
		}, want: 0},
		{name: "missing events table", prepare: func(t *testing.T, path string) {
			db, err := sql.Open("sqlite", path)
			if err != nil {
				t.Fatal(err)
			}
			defer db.Close()
			if _, err := db.Exec("CREATE TABLE other (id INTEGER)"); err != nil {
				t.Fatal(err)
			}
		}, wantErr: true},
		{name: "counts events", prepare: func(t *testing.T, path string) {
			st, err := Open(context.Background(), path)
			if err != nil {
				t.Fatal(err)
			}
			defer st.Close()
			_, _, err = st.InsertBatch(context.Background(), []event.Envelope{{ID: "e1", UserID: "u", DeviceID: "d", Type: "x", V: 1, TS: "2026-01-01T00:00:00Z"}}, timeNow())
			if err != nil {
				t.Fatal(err)
			}
		}, want: 1},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "cauce.db")
			tt.prepare(t, path)
			got, err := Check(context.Background(), path)
			if (err != nil) != tt.wantErr {
				t.Fatalf("error = %v, wantErr %v", err, tt.wantErr)
			}
			if err == nil && got != tt.want {
				t.Fatalf("count = %d, want %d", got, tt.want)
			}
		})
	}
}

func TestSnapshot(t *testing.T) {
	tests := []struct {
		name        string
		destination string
		missing     bool
		wantErr     bool
	}{
		{name: "copies events", destination: "snapshot.db"},
		{name: "destination with space and quote", destination: `snap shot 'quoted'.db`},
		{name: "missing source", destination: "snapshot.db", missing: true, wantErr: true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			dir := t.TempDir()
			dbPath := filepath.Join(dir, "cauce.db")
			if !tt.missing {
				st, err := Open(context.Background(), dbPath)
				if err != nil {
					t.Fatal(err)
				}
				if _, _, err := st.InsertBatch(context.Background(), []event.Envelope{{ID: "e1", UserID: "u", DeviceID: "d", Type: "x", V: 1, TS: "2026-01-01T00:00:00Z"}}, timeNow()); err != nil {
					t.Fatal(err)
				}
				st.Close()
			}
			snapshot := filepath.Join(dir, tt.destination)
			err := Snapshot(context.Background(), dbPath, snapshot)
			if (err != nil) != tt.wantErr {
				t.Fatalf("error = %v, wantErr %v", err, tt.wantErr)
			}
			if tt.wantErr {
				return
			}
			if got, err := Check(context.Background(), snapshot); err != nil || got != 1 {
				t.Fatalf("snapshot check = %d, %v", got, err)
			}
		})
	}
}

// Kept local so the test does not depend on the wall clock for its assertion.
func timeNow() time.Time { return time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC) }
