// Command cauce is the single Go binary: HTTP API, SQLite event log, blob
// presigning and background workers. Configuration comes only from the
// environment (see .env.example).
package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"cauce/server/internal/auth"
	"cauce/server/internal/blobs"
	"cauce/server/internal/config"
	"cauce/server/internal/core/event"
	"cauce/server/internal/core/projection"
	"cauce/server/internal/core/store"
	"cauce/server/internal/projections"
	syncapi "cauce/server/internal/sync"
	"cauce/server/internal/worker"
)

func main() {
	log.SetFlags(log.LstdFlags | log.LUTC)
	if err := run(); err != nil {
		log.Fatal(err)
	}
}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	cfg, err := config.Load()
	if err != nil {
		return err
	}
	validator, err := event.NewValidator()
	if err != nil {
		return err
	}
	st, err := store.Open(ctx, cfg.DBPath)
	if err != nil {
		return err
	}
	defer st.Close()

	// Feature packages register their projections here. This list stays empty
	// until /feature <id>; the conformance harness already validates fixtures.
	registry := projection.NewRegistry()

	pusher := &syncapi.Pusher{Store: st, Validator: validator}
	puller := &syncapi.Puller{Store: st}
	projectionHandler := &projections.Handler{Store: st, Registry: registry}
	blobHandler := &blobs.Handler{Presigner: blobs.New(cfg.R2)}

	mux := http.NewServeMux()
	mux.Handle("POST /sync/push", pusher.PushHandler())
	mux.Handle("GET /sync/pull", puller.PullHandler())
	mux.Handle("POST /blobs/presign", blobHandler)
	mux.Handle("GET /api/projections/{feature}/{name}", projectionHandler)
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"status":"ok"}`))
	})

	srv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	loopDone := make(chan struct{})
	go func() {
		defer close(loopDone)
		(&worker.Loop{Workers: nil, Interval: time.Minute}).Run(ctx)
	}()

	serveErr := make(chan error, 1)
	go func() {
		log.Printf("cauce listening on %s (db %s, user %s)", cfg.Addr, cfg.DBPath, auth.UserFromRequest(nil))
		serveErr <- srv.ListenAndServe()
	}()

	select {
	case err := <-serveErr:
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			return err
		}
		return nil
	case <-ctx.Done():
	}

	log.Print("shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	shutdownErr := srv.Shutdown(shutdownCtx)
	<-loopDone
	if shutdownErr != nil {
		return shutdownErr
	}
	return nil
}
