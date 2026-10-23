package config

import "testing"

func clearEnv(t *testing.T) {
	t.Helper()
	for _, key := range []string{
		"CAUCE_ADDR", "CAUCE_DB_PATH", "CAUCE_USER_ID",
		"R2_ACCOUNT_ID", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY", "R2_BUCKET",
	} {
		t.Setenv(key, "")
	}
}

func TestLoadDefaults(t *testing.T) {
	clearEnv(t)
	t.Setenv("CAUCE_USER_ID", "u1")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.Addr != ":8080" || cfg.DBPath != "cauce.db" || cfg.UserID != "u1" {
		t.Fatalf("cfg = %+v", cfg)
	}
	if cfg.R2.Configured() {
		t.Fatal("R2 should be unconfigured")
	}
}

func TestLoadRequiresUser(t *testing.T) {
	clearEnv(t)
	if _, err := Load(); err == nil {
		t.Fatal("want error when CAUCE_USER_ID is missing")
	}
}

func TestLoadFull(t *testing.T) {
	clearEnv(t)
	t.Setenv("CAUCE_ADDR", "127.0.0.1:9999")
	t.Setenv("CAUCE_DB_PATH", "/tmp/x.db")
	t.Setenv("CAUCE_USER_ID", "u1")
	t.Setenv("R2_ACCOUNT_ID", "acc")
	t.Setenv("R2_ACCESS_KEY_ID", "key")
	t.Setenv("R2_SECRET_ACCESS_KEY", "secret")
	t.Setenv("R2_BUCKET", "bucket")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.Addr != "127.0.0.1:9999" || cfg.DBPath != "/tmp/x.db" {
		t.Fatalf("cfg = %+v", cfg)
	}
	if !cfg.R2.Configured() {
		t.Fatal("R2 should be configured")
	}
}
