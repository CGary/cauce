// Command syncschema copies the published contract from the repository's
// schema/ tree into the event package so //go:embed can include it. go:embed
// cannot reference parent directories or follow symlinks, hence the copy.
//
// Run it from this package with `go generate ./...` in server/.
package main

import (
	"fmt"
	"os"
	"path/filepath"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "syncschema:", err)
		os.Exit(1)
	}
}

func run() error {
	root, err := repoRoot()
	if err != nil {
		return err
	}
	src := filepath.Join(root, "schema")
	dst := "schema"

	if err := os.RemoveAll(dst); err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Join(dst, "events"), 0o755); err != nil {
		return err
	}
	if err := copyFile(filepath.Join(src, "envelope.v1.json"), filepath.Join(dst, "envelope.v1.json")); err != nil {
		return err
	}
	entries, err := os.ReadDir(filepath.Join(src, "events"))
	if err != nil {
		return err
	}
	for _, entry := range entries {
		if entry.IsDir() || filepath.Ext(entry.Name()) != ".json" {
			continue
		}
		if err := copyFile(filepath.Join(src, "events", entry.Name()), filepath.Join(dst, "events", entry.Name())); err != nil {
			return err
		}
	}
	return nil
}

func repoRoot() (string, error) {
	dir, err := os.Getwd()
	if err != nil {
		return "", err
	}
	for {
		if _, err := os.Stat(filepath.Join(dir, "schema", "envelope.v1.json")); err == nil {
			return dir, nil
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return "", fmt.Errorf("repository root (schema/envelope.v1.json) not found from %s", dir)
		}
		dir = parent
	}
}

func copyFile(src, dst string) error {
	data, err := os.ReadFile(src)
	if err != nil {
		return err
	}
	return os.WriteFile(dst, data, 0o644)
}
