// Package sync implements the generic event sync: POST /sync/push,
// GET /sync/pull and GET /sync/devices. Push is idempotent by event id and
// validates the whole batch before writing anything. Push and pull also keep
// per-device bookkeeping (D22) so clients can tell when each device last
// synced and whether a change already reached the phone.
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
	// Now is the server clock; nil means time.Now.
	Now func() time.Time
}

// PushResult is the response body of a successful push.
type PushResult struct {
	Accepted   int `json:"accepted"`
	Duplicates int `json:"duplicates"`
	// ServerSeq is the highest server_seq of the user's log after the insert
	// (D22): every pushed event, new or duplicate, has server_seq <= it.
	ServerSeq int64 `json:"server_seq"`
}

func now(f func() time.Time) time.Time {
	if f == nil {
		return time.Now().UTC()
	}
	return f().UTC()
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

	out, err := p.Store.PushBatch(ctx, userID, events, now(p.Now))
	if err != nil {
		return PushResult{}, err
	}
	return PushResult{Accepted: out.Accepted, Duplicates: out.Duplicates, ServerSeq: out.MaxSeq}, nil
}

// PullResult is the response body of /sync/pull.
type PullResult struct {
	Events    []event.Envelope `json:"events"`
	NextSince int64            `json:"next_since"`
}

// Puller reads the log after a cursor.
type Puller struct {
	Store *store.Store
	// Now is the server clock; nil means time.Now.
	Now func() time.Time
}

// Pull returns up to MaxPull events for userID after since. next_since is the
// last returned server_seq (or since when the page is empty), so the client can
// advance its cursor without arithmetic. When deviceID is not empty the pull
// is recorded for that device (D22): last_pull_at = now and
// pulled_through_seq = next_since. Old clients send no device_id and nothing
// is recorded.
func (p *Puller) Pull(ctx context.Context, userID, deviceID string, since int64) (PullResult, error) {
	events, err := p.Store.Pull(ctx, userID, since, MaxPull)
	if err != nil {
		return PullResult{}, err
	}
	next := since
	if len(events) > 0 {
		next = *events[len(events)-1].ServerSeq
	}
	if deviceID != "" {
		if err := p.Store.RecordPull(ctx, userID, deviceID, now(p.Now), next); err != nil {
			return PullResult{}, err
		}
	}
	return PullResult{Events: events, NextSince: next}, nil
}

// DeviceStatus is one device in GET /sync/devices. Nil fields mean never.
type DeviceStatus struct {
	DeviceID         string  `json:"device_id"`
	LastPushAt       *string `json:"last_push_at"`
	LastPullAt       *string `json:"last_pull_at"`
	PulledThroughSeq *int64  `json:"pulled_through_seq"`
}

// DevicesResult is the response body of GET /sync/devices.
type DevicesResult struct {
	Now       string         `json:"now"`
	ServerSeq int64          `json:"server_seq"`
	Devices   []DeviceStatus `json:"devices"`
}

// Devices reports the sync bookkeeping of every device of one user.
type Devices struct {
	Store *store.Store
	// Now is the server clock; nil means time.Now.
	Now func() time.Time
}

// List returns userID's devices sorted by device_id plus the current
// server_seq. A change pushed with server_seq S has reached device D when
// D.pulled_through_seq >= S.
func (d *Devices) List(ctx context.Context, userID string) (DevicesResult, error) {
	seq, err := d.Store.MaxSeq(ctx, userID)
	if err != nil {
		return DevicesResult{}, err
	}
	rows, err := d.Store.Devices(ctx, userID)
	if err != nil {
		return DevicesResult{}, err
	}
	out := DevicesResult{Now: now(d.Now).Format(time.RFC3339), ServerSeq: seq, Devices: make([]DeviceStatus, 0, len(rows))}
	for _, r := range rows {
		out.Devices = append(out.Devices, DeviceStatus{
			DeviceID:         r.DeviceID,
			LastPushAt:       r.LastPushAt,
			LastPullAt:       r.LastPullAt,
			PulledThroughSeq: r.PulledThroughSeq,
		})
	}
	return out, nil
}
