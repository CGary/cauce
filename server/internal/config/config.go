// Package config loads server configuration from the environment. There is no
// config file: one binary, a handful of variables, documented in .env.example.
package config

import (
	"fmt"
	"os"
	"strings"
)

// Config is the complete runtime configuration.
type Config struct {
	Addr       string
	DBPath     string
	UserID     string
	CORSOrigin string
	R2         R2
}

// R2 holds the Cloudflare R2 (S3-compatible) credentials. All four fields are
// required for /blobs/presign to work; without them the endpoint returns 503.
type R2 struct {
	AccountID       string
	AccessKeyID     string
	SecretAccessKey string
	Bucket          string
}

// Configured reports whether every R2 variable is present.
func (r R2) Configured() bool {
	return r.AccountID != "" && r.AccessKeyID != "" && r.SecretAccessKey != "" && r.Bucket != ""
}

// Load reads and validates the environment.
func Load() (Config, error) {
	cfg := Config{
		Addr:       strings.TrimSpace(os.Getenv("CAUCE_ADDR")),
		DBPath:     strings.TrimSpace(os.Getenv("CAUCE_DB_PATH")),
		UserID:     strings.TrimSpace(os.Getenv("CAUCE_USER_ID")),
		CORSOrigin: strings.TrimSpace(os.Getenv("CAUCE_CORS_ORIGIN")),
		R2: R2{
			AccountID:       strings.TrimSpace(os.Getenv("R2_ACCOUNT_ID")),
			AccessKeyID:     strings.TrimSpace(os.Getenv("R2_ACCESS_KEY_ID")),
			SecretAccessKey: strings.TrimSpace(os.Getenv("R2_SECRET_ACCESS_KEY")),
			Bucket:          strings.TrimSpace(os.Getenv("R2_BUCKET")),
		},
	}
	if cfg.Addr == "" {
		cfg.Addr = ":8080"
	}
	if cfg.DBPath == "" {
		cfg.DBPath = "cauce.db"
	}
	if cfg.UserID == "" {
		return Config{}, fmt.Errorf("CAUCE_USER_ID is required")
	}
	return cfg, nil
}
