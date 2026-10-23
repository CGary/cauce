// Package store owns the SQLite event log: the single append-only table, its
// migrations and the two read patterns (pull by cursor, full scan for
// projections).
package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"cauce/server/internal/core/event"
	"cauce/server/internal/core/store/sqlcgen"
	"cauce/server/migrations"

	_ "modernc.org/sqlite"
)

// Store is safe for concurrent use; the underlying pool is capped at one
// connection because this server has a single writer.
type Store struct {
	db *sql.DB
	q  *sqlcgen.Queries
}

// Open opens (creating if needed) the SQLite database and applies pending
// migrations. Use ":memory:" only in short-lived tests.
func Open(ctx context.Context, path string) (*Store, error) {
	if !isMemory(path) {
		if dir := filepath.Dir(path); dir != "" && dir != "." {
			if err := os.MkdirAll(dir, 0o755); err != nil {
				return nil, fmt.Errorf("create database directory: %w", err)
			}
		}
	}

	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, fmt.Errorf("open sqlite: %w", err)
	}
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(1)

	for _, pragma := range []string{
		"PRAGMA journal_mode = WAL",
		"PRAGMA busy_timeout = 5000",
		"PRAGMA foreign_keys = ON",
	} {
		if _, err := db.ExecContext(ctx, pragma); err != nil {
			db.Close()
			return nil, fmt.Errorf("%s: %w", pragma, err)
		}
	}

	s := &Store{db: db, q: sqlcgen.New(db)}
	if err := s.migrate(ctx); err != nil {
		db.Close()
		return nil, err
	}
	return s, nil
}

func isMemory(path string) bool {
	return path == ":memory:" || strings.HasPrefix(path, "file::memory:") || strings.Contains(path, "mode=memory")
}

func (s *Store) migrate(ctx context.Context) error {
	if _, err := s.db.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (
		version TEXT PRIMARY KEY,
		applied_at TEXT NOT NULL
	)`); err != nil {
		return fmt.Errorf("create schema_migrations: %w", err)
	}

	entries, err := migrations.FS.ReadDir(".")
	if err != nil {
		return err
	}
	names := make([]string, 0, len(entries))
	for _, entry := range entries {
		if !entry.IsDir() && strings.HasSuffix(entry.Name(), ".sql") {
			names = append(names, entry.Name())
		}
	}
	sort.Strings(names)

	for _, name := range names {
		var applied int
		if err := s.db.QueryRowContext(ctx,
			`SELECT COUNT(*) FROM schema_migrations WHERE version = ?`, name).Scan(&applied); err != nil {
			return fmt.Errorf("check migration %s: %w", name, err)
		}
		if applied > 0 {
			continue
		}
		body, err := migrations.FS.ReadFile(name)
		if err != nil {
			return err
		}
		tx, err := s.db.BeginTx(ctx, nil)
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, string(body)); err != nil {
			tx.Rollback()
			return fmt.Errorf("apply migration %s: %w", name, err)
		}
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)`,
			name, time.Now().UTC().Format(time.RFC3339)); err != nil {
			tx.Rollback()
			return fmt.Errorf("record migration %s: %w", name, err)
		}
		if err := tx.Commit(); err != nil {
			return err
		}
	}
	return nil
}

// InsertBatch appends events in one transaction. Duplicate ids are ignored and
// counted; the returned accepted count is the number of new rows.
func (s *Store) InsertBatch(ctx context.Context, events []event.Envelope, receivedAt time.Time) (accepted, duplicates int, err error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, 0, err
	}
	defer tx.Rollback()

	q := s.q.WithTx(tx)
	received := receivedAt.UTC().Format(time.RFC3339)
	for _, ev := range events {
		payload := ev.Payload
		if len(payload) == 0 {
			payload = json.RawMessage(`{}`)
		}
		res, err := q.InsertEvent(ctx, sqlcgen.InsertEventParams{
			ID:         ev.ID,
			UserID:     ev.UserID,
			DeviceID:   ev.DeviceID,
			Type:       ev.Type,
			V:          int64(ev.V),
			Ts:         ev.TS,
			Payload:    string(payload),
			ReceivedAt: received,
		})
		if err != nil {
			return 0, 0, fmt.Errorf("insert event %q: %w", ev.ID, err)
		}
		n, err := res.RowsAffected()
		if err != nil {
			return 0, 0, err
		}
		if n == 0 {
			duplicates++
			continue
		}
		accepted++
	}
	if err := tx.Commit(); err != nil {
		return 0, 0, err
	}
	return accepted, duplicates, nil
}

// Pull returns up to limit events for userID with server_seq > since, ordered by
// server_seq. The device's own events are included on purpose.
func (s *Store) Pull(ctx context.Context, userID string, since int64, limit int) ([]event.Envelope, error) {
	rows, err := s.q.ListEventsSince(ctx, sqlcgen.ListEventsSinceParams{
		UserID:    userID,
		ServerSeq: since,
		Limit:     int64(limit),
	})
	if err != nil {
		return nil, err
	}
	return toEnvelopes(rows), nil
}

// Events returns every event for userID ordered by server_seq. Projections are
// pure functions over this slice.
func (s *Store) Events(ctx context.Context, userID string) ([]event.Envelope, error) {
	rows, err := s.q.ListEventsByUser(ctx, userID)
	if err != nil {
		return nil, err
	}
	return toEnvelopes(rows), nil
}

func (s *Store) Close() error { return s.db.Close() }

func toEnvelopes(rows []sqlcgen.Event) []event.Envelope {
	out := make([]event.Envelope, 0, len(rows))
	for _, row := range rows {
		seq := row.ServerSeq
		out = append(out, event.Envelope{
			ID:        row.ID,
			UserID:    row.UserID,
			DeviceID:  row.DeviceID,
			Type:      row.Type,
			V:         int(row.V),
			TS:        row.Ts,
			Payload:   json.RawMessage(row.Payload),
			ServerSeq: &seq,
		})
	}
	return out
}
