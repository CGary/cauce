package worker

import (
	"context"
	"errors"
	"io"
	"log"
	"sync/atomic"
	"testing"
	"time"
)

type fakeWorker struct {
	name  string
	calls *atomic.Int64
	err   error
	after func()
}

func (f fakeWorker) Name() string { return f.name }

func (f fakeWorker) Run(context.Context) error {
	n := f.calls.Add(1)
	if f.after != nil {
		f.after()
	}
	_ = n
	return f.err
}

func TestLoopRunsOnceThenTicks(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	var calls atomic.Int64
	w := fakeWorker{name: "test", calls: &calls, after: func() {
		if calls.Load() >= 2 {
			cancel()
		}
	}}

	done := make(chan struct{})
	go func() {
		defer close(done)
		(&Loop{Workers: []Worker{w}, Interval: time.Millisecond, Logger: log.New(io.Discard, "", 0)}).Run(ctx)
	}()

	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("loop did not stop after cancel")
	}
	if calls.Load() < 2 {
		t.Fatalf("worker calls = %d, want >= 2", calls.Load())
	}
}

func TestLoopKeepsGoingAfterWorkerError(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	var calls atomic.Int64
	w := fakeWorker{name: "failing", calls: &calls, err: errors.New("boom"), after: func() {
		if calls.Load() >= 2 {
			cancel()
		}
	}}
	done := make(chan struct{})
	go func() {
		defer close(done)
		(&Loop{Workers: []Worker{w}, Interval: time.Millisecond, Logger: log.New(io.Discard, "", 0)}).Run(ctx)
	}()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("loop did not stop after cancel")
	}
	if calls.Load() < 2 {
		t.Fatalf("worker calls = %d, want >= 2", calls.Load())
	}
}
