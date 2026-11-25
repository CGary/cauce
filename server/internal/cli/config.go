package cli

import (
	"encoding/json"
	"errors"
	"io/fs"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
	_ "time/tzdata" // a valid IANA zone must resolve even without system zoneinfo
)

// Config is ~/.config/cauce-cli/config.json. It holds no secret: the token
// lives in its own file (D21).
type Config struct {
	BaseURL string `json:"base_url"`
	UserID  string `json:"user_id"`
	TZ      string `json:"tz"`
}

func (e Env) home() string {
	if h := e.Getenv("HOME"); h != "" {
		return h
	}
	h, _ := os.UserHomeDir()
	return h
}

// configDir is $XDG_CONFIG_HOME/cauce-cli, or ~/.config/cauce-cli.
func (e Env) configDir() string {
	if x := e.Getenv("XDG_CONFIG_HOME"); x != "" && filepath.IsAbs(x) {
		return filepath.Join(x, "cauce-cli")
	}
	return filepath.Join(e.home(), ".config", "cauce-cli")
}

// plansDir is $XDG_STATE_HOME/cauce-cli/plans, or ~/.local/state/cauce-cli/plans.
func (e Env) plansDir() string {
	if x := e.Getenv("XDG_STATE_HOME"); x != "" && filepath.IsAbs(x) {
		return filepath.Join(x, "cauce-cli", "plans")
	}
	return filepath.Join(e.home(), ".local", "state", "cauce-cli", "plans")
}

func absPath(p string) string {
	if a, err := filepath.Abs(p); err == nil {
		return a
	}
	return p
}

func loadConfig(path string) (*Config, *cliError) {
	raw, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		return nil, (&cliError{
			Code:    CodeConfigNotFound,
			Message: "config file not found at " + path + `; create it with {"base_url": "...", "user_id": "...", "tz": "..."}`,
			Field:   "config",
		})
	}
	if err != nil {
		return nil, errorf(CodeConfigNotFound, "cannot read config file %s: %v", path, err).field("config")
	}
	var cfg Config
	dec := json.NewDecoder(strings.NewReader(string(raw)))
	if err := dec.Decode(&cfg); err != nil {
		return nil, errorf(CodeInvalidArgument, "config file %s is not valid JSON: %v", path, err).field("config")
	}
	cfg.BaseURL = strings.TrimRight(strings.TrimSpace(cfg.BaseURL), "/")
	cfg.UserID = strings.TrimSpace(cfg.UserID)
	cfg.TZ = strings.TrimSpace(cfg.TZ)
	u, err := url.Parse(cfg.BaseURL)
	if cfg.BaseURL == "" || err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return nil, errorf(CodeInvalidArgument, "config base_url must be an http(s) URL").field("config")
	}
	if cfg.UserID == "" {
		return nil, errorf(CodeInvalidArgument, "config user_id is required; it must match CAUCE_USER_ID on the server").field("config")
	}
	return &cfg, nil
}

// readToken reads the API token from its file. It refuses a file that other
// users can read and never includes the token in any error.
func readToken(path string) (string, *cliError) {
	info, err := os.Stat(path)
	if errors.Is(err, fs.ErrNotExist) {
		return "", errorf(CodePermissionDenied, "API token file not found at %s; write the token there with mode 600", path).field("token-file")
	}
	if err != nil {
		return "", errorf(CodePermissionDenied, "cannot read the API token file %s", path).field("token-file")
	}
	if info.IsDir() {
		return "", errorf(CodePermissionDenied, "API token path %s is a directory", path).field("token-file")
	}
	if info.Mode().Perm()&0o077 != 0 {
		return "", (&cliError{
			Code:    CodePermissionDenied,
			Message: "API token file " + path + " is readable by other users; refusing to use it",
			Field:   "token-file",
		}).fix("chmod 600 " + path)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return "", errorf(CodePermissionDenied, "cannot read the API token file %s", path).field("token-file")
	}
	token := strings.TrimSpace(string(raw))
	if token == "" || strings.ContainsAny(token, "\r\n") {
		return "", errorf(CodePermissionDenied, "API token file %s must hold exactly one token on one line", path).field("token-file")
	}
	return token, nil
}

// resolveTZ picks --tz, then config tz, then the system zone, then UTC.
func resolveTZ(flag string, cfg *Config, env Env) (string, *time.Location, *cliError) {
	name := flag
	if name == "" && cfg != nil {
		name = cfg.TZ
	}
	if name == "" {
		name = systemTZ(env)
	}
	loc, err := time.LoadLocation(name)
	if err != nil || name == "Local" {
		return "", nil, (&cliError{Code: CodeInvalidArgument, Message: "time zone must be an IANA name such as America/La_Paz", Field: "tz", Received: name})
	}
	return name, loc, nil
}

func systemTZ(env Env) string {
	if tz := strings.TrimPrefix(env.Getenv("TZ"), ":"); tz != "" {
		if _, err := time.LoadLocation(tz); err == nil {
			return tz
		}
	}
	if raw, err := os.ReadFile("/etc/timezone"); err == nil {
		if tz := strings.TrimSpace(string(raw)); tz != "" {
			if _, err := time.LoadLocation(tz); err == nil {
				return tz
			}
		}
	}
	if target, err := os.Readlink("/etc/localtime"); err == nil {
		if i := strings.Index(target, "zoneinfo/"); i >= 0 {
			tz := target[i+len("zoneinfo/"):]
			if _, err := time.LoadLocation(tz); err == nil {
				return tz
			}
		}
	}
	return "UTC"
}
