// Package store owns the SQLite event log: the single append-only table, its
// migrations and the two read patterns (pull by cursor, full scan for
// projections). It also keeps device_sync, the per-device sync bookkeeping
// of D22, which is not an event.
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

	accepted, duplicates, err = insertEvents(ctx, s.q.WithTx(tx), events, receivedAt)
	if err != nil {
		return 0, 0, err
	}
	if err := tx.Commit(); err != nil {
		return 0, 0, err
	}
	return accepted, duplicates, nil
}

// PushOutcome is what PushBatch reports.
type PushOutcome struct {
	Accepted   int
	Duplicates int
	// MaxSeq is the highest server_seq of userID's log after the insert, so
	// every pushed event (new or duplicate) has server_seq <= MaxSeq.
	MaxSeq int64
}

// PushBatch is InsertBatch plus the D22 bookkeeping in the same transaction:
// it records last_push_at = at for every distinct device_id in the batch and
// reads the user's highest server_seq after the insert.
func (s *Store) PushBatch(ctx context.Context, userID string, events []event.Envelope, at time.Time) (PushOutcome, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return PushOutcome{}, err
	}
	defer tx.Rollback()

	q := s.q.WithTx(tx)
	accepted, duplicates, err := insertEvents(ctx, q, events, at)
	if err != nil {
		return PushOutcome{}, err
	}
	stamp := formatTime(at)
	seen := map[string]bool{}
	for _, ev := range events {
		if seen[ev.DeviceID] {
			continue
		}
		seen[ev.DeviceID] = true
		if err := q.RecordDevicePush(ctx, sqlcgen.RecordDevicePushParams{
			UserID:     userID,
			DeviceID:   ev.DeviceID,
			LastPushAt: sql.NullString{String: stamp, Valid: true},
		}); err != nil {
			return PushOutcome{}, fmt.Errorf("record push of %q: %w", ev.DeviceID, err)
		}
	}
	maxSeq, err := q.MaxServerSeq(ctx, userID)
	if err != nil {
		return PushOutcome{}, err
	}
	if err := tx.Commit(); err != nil {
		return PushOutcome{}, err
	}
	return PushOutcome{Accepted: accepted, Duplicates: duplicates, MaxSeq: maxSeq}, nil
}

func insertEvents(ctx context.Context, q *sqlcgen.Queries, events []event.Envelope, receivedAt time.Time) (accepted, duplicates int, err error) {
	received := formatTime(receivedAt)
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
	return accepted, duplicates, nil
}

func formatTime(t time.Time) string { return t.UTC().Format(time.RFC3339) }

// RecordPull stores that deviceID pulled at `at` and now holds every event up
// to pulledThrough (the next_since it was given). D22 bookkeeping only.
func (s *Store) RecordPull(ctx context.Context, userID, deviceID string, at time.Time, pulledThrough int64) error {
	return s.q.RecordDevicePull(ctx, sqlcgen.RecordDevicePullParams{
		UserID:           userID,
		DeviceID:         deviceID,
		LastPullAt:       sql.NullString{String: formatTime(at), Valid: true},
		PulledThroughSeq: sql.NullInt64{Int64: pulledThrough, Valid: true},
	})
}

// MaxSeq is the highest server_seq of userID's log (0 when empty).
func (s *Store) MaxSeq(ctx context.Context, userID string) (int64, error) {
	return s.q.MaxServerSeq(ctx, userID)
}

// DeviceSync is one row of the D22 bookkeeping. Nil means never.
type DeviceSync struct {
	DeviceID         string
	LastPushAt       *string
	LastPullAt       *string
	PulledThroughSeq *int64
}

// Devices returns userID's device bookkeeping sorted by device_id.
func (s *Store) Devices(ctx context.Context, userID string) ([]DeviceSync, error) {
	rows, err := s.q.ListDeviceSync(ctx, userID)
	if err != nil {
		return nil, err
	}
	out := make([]DeviceSync, 0, len(rows))
	for _, row := range rows {
		d := DeviceSync{DeviceID: row.DeviceID}
		if row.LastPushAt.Valid {
			v := row.LastPushAt.String
			d.LastPushAt = &v
		}
		if row.LastPullAt.Valid {
			v := row.LastPullAt.String
			d.LastPullAt = &v
		}
		if row.PulledThroughSeq.Valid {
			v := row.PulledThroughSeq.Int64
			d.PulledThroughSeq = &v
		}
		out = append(out, d)
	}
	return out, nil
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
