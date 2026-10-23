// Package worker runs periodic jobs as goroutines. A worker does one pass;
// this package owns the ticker loop.
package worker

import (
	"context"
	"log"
	"sync"
	"time"
)

// Worker is one job. Run executes a single pass and returns; the loop calls it
// again after Interval.
type Worker interface {
	Name() string
	Run(ctx context.Context) error
}

// Loop runs every worker on its own goroutine until ctx is cancelled.
type Loop struct {
	Workers  []Worker
	Interval time.Duration
	Logger   *log.Logger
}

// Run blocks until ctx is done.
func (l *Loop) Run(ctx context.Context) {
	interval := l.Interval
	if interval <= 0 {
		interval = time.Minute
	}
	var wg sync.WaitGroup
	for _, w := range l.Workers {
		w := w
		wg.Add(1)
		go func() {
			defer wg.Done()
			l.runWorker(ctx, w, interval)
		}()
	}
	wg.Wait()
}

func (l *Loop) runWorker(ctx context.Context, w Worker, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		if err := w.Run(ctx); err != nil && ctx.Err() == nil {
			l.logf("worker %s: %v", w.Name(), err)
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func (l *Loop) logf(format string, args ...any) {
	if l.Logger != nil {
		l.Logger.Printf(format, args...)
		return
	}
	log.Printf(format, args...)
}
