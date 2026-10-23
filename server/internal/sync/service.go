// Package sync implements the generic event sync: POST /sync/push and
// GET /sync/pull. Push is idempotent by event id and validates the whole batch
// before writing anything.
package sync

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"cauce/server/internal/core/event"
	"cauce/server/internal/core/store"
)

// MaxPull is the maximum page size of /sync/pull.
const MaxPull = 500

// Pusher appends validated events to the log.
type Pusher struct {
	Store     *store.Store
	Validator *event.Validator
}

// PushResult is the response body of a successful push.
type PushResult struct {
	Accepted   int `json:"accepted"`
	Duplicates int `json:"duplicates"`
}

type pushRequest struct {
	Events []json.RawMessage `json:"events"`
}

// Push validates every event against the embedded contract and then inserts the
// batch atomically. Any invalid event rejects the whole batch so the device can
// fix it and retry; duplicates are skipped and counted.
func (p *Pusher) Push(ctx context.Context, userID string, body []byte) (PushResult, error) {
	var req pushRequest
	if err := json.Unmarshal(body, &req); err != nil {
		return PushResult{}, fmt.Errorf("invalid request body: %w", err)
	}

	events := make([]event.Envelope, 0, len(req.Events))
	for i, raw := range req.Events {
		ev, err := p.Validator.ValidateEnvelope(raw)
		if err != nil {
			return PushResult{}, fmt.Errorf("events[%d]: %w", i, err)
		}
		if ev.UserID != userID {
			return PushResult{}, fmt.Errorf("events[%d]: event %q has user_id %q, request user is %q", i, ev.ID, ev.UserID, userID)
		}
		events = append(events, *ev)
	}

	accepted, duplicates, err := p.Store.InsertBatch(ctx, events, time.Now().UTC())
	if err != nil {
		return PushResult{}, err
	}
	return PushResult{Accepted: accepted, Duplicates: duplicates}, nil
}

// PullResult is the response body of /sync/pull.
type PullResult struct {
	Events    []event.Envelope `json:"events"`
	NextSince int64            `json:"next_since"`
}

// Puller reads the log after a cursor.
type Puller struct {
	Store *store.Store
}

// Pull returns up to MaxPull events for userID after since. next_since is the
// last returned server_seq (or since when the page is empty), so the client can
// advance its cursor without arithmetic.
func (p *Puller) Pull(ctx context.Context, userID string, since int64) (PullResult, error) {
	events, err := p.Store.Pull(ctx, userID, since, MaxPull)
	if err != nil {
		return PullResult{}, err
	}
	next := since
	if len(events) > 0 {
		next = *events[len(events)-1].ServerSeq
	}
	return PullResult{Events: events, NextSince: next}, nil
}
