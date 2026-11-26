package cli

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"

	"cauce/server/internal/core/event"
)

// client talks to the cauce server like the web does: projections to read,
// POST /sync/push to write. The token is loaded lazily and never logged.
type client struct {
	baseURL string
	http    *http.Client
	token   func() (string, *cliError)
	debug   io.Writer
}

type pushResult struct {
	Accepted   int `json:"accepted"`
	Duplicates int `json:"duplicates"`
	// ServerSeq is 0 when the server predates D22.
	ServerSeq int64 `json:"server_seq"`
}

// deviceRow is one device of GET /sync/devices (D22).
type deviceRow struct {
	DeviceID         string  `json:"device_id"`
	LastPushAt       *string `json:"last_push_at"`
	LastPullAt       *string `json:"last_pull_at"`
	PulledThroughSeq *int64  `json:"pulled_through_seq"`
}

type devicesResult struct {
	Now       string      `json:"now"`
	ServerSeq int64       `json:"server_seq"`
	Devices   []deviceRow `json:"devices"`
}

// isPhone: every device that is not the web or the CLI is a phone (D22).
func isPhone(deviceID string) bool { return deviceID != "web" && deviceID != DeviceID }

// phoneHas reports whether some phone has pulled through seq.
func (d devicesResult) phoneHas(seq int64) bool {
	for _, dev := range d.Devices {
		if isPhone(dev.DeviceID) && dev.PulledThroughSeq != nil && *dev.PulledThroughSeq >= seq {
			return true
		}
	}
	return false
}

func (c *client) logf(format string, args ...any) {
	if c.debug != nil {
		fmt.Fprintf(c.debug, "cauce-cli: "+format+"\n", args...)
	}
}

// healthz calls GET /healthz, which needs no token.
func (c *client) healthz(ctx context.Context) *cliError {
	_, err := c.do(ctx, http.MethodGet, c.baseURL+"/healthz", nil, false)
	return err
}

// projection runs GET /api/projections/<feature>/<name> and decodes the result.
func (c *client) projection(ctx context.Context, feature, name string, params url.Values, out any) *cliError {
	target, err := url.JoinPath(c.baseURL, "api", "projections", feature, name)
	if err != nil {
		return errorf(CodeInvalidArgument, "config base_url is not a valid URL").field("config")
	}
	if len(params) > 0 {
		target += "?" + params.Encode()
	}
	body, cerr := c.do(ctx, http.MethodGet, target, nil, true)
	if cerr != nil {
		return cerr
	}
	if err := json.Unmarshal(body, out); err != nil {
		return errorf(CodeInternal, "projection %s.%s returned an unexpected body", feature, name)
	}
	return nil
}

// push sends the batch in one POST /sync/push. The server rejects the whole
// batch if any event is invalid and skips ids it already has.
func (c *client) push(ctx context.Context, events []event.Envelope) (pushResult, *cliError) {
	payload, err := json.Marshal(map[string]any{"events": events})
	if err != nil {
		return pushResult{}, errorf(CodeInternal, "cannot encode events")
	}
	body, cerr := c.do(ctx, http.MethodPost, c.baseURL+"/sync/push", payload, true)
	if cerr != nil {
		return pushResult{}, cerr
	}
	var res pushResult
	if err := json.Unmarshal(body, &res); err != nil {
		return pushResult{}, errorf(CodeInternal, "/sync/push returned an unexpected body")
	}
	return res, nil
}

// devices runs GET /sync/devices.
func (c *client) devices(ctx context.Context) (devicesResult, *cliError) {
	body, cerr := c.do(ctx, http.MethodGet, c.baseURL+"/sync/devices", nil, true)
	if cerr != nil {
		return devicesResult{}, cerr
	}
	var res devicesResult
	if err := json.Unmarshal(body, &res); err != nil {
		return devicesResult{}, errorf(CodeInternal, "/sync/devices returned an unexpected body")
	}
	if res.Devices == nil {
		res.Devices = []deviceRow{}
	}
	return res, nil
}

func (c *client) do(ctx context.Context, method, target string, body []byte, auth bool) ([]byte, *cliError) {
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, target, reader)
	if err != nil {
		return nil, errorf(CodeInvalidArgument, "config base_url is not a valid URL").field("config")
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("Accept", "application/json")
	if auth {
		token, cerr := c.token()
		if cerr != nil {
			return nil, cerr
		}
		req.Header.Set("Authorization", "Bearer "+token)
	}
	// Only method and URL are logged; headers (the token) never are.
	c.logf("%s %s", method, req.URL.Redacted())
	resp, err := c.http.Do(req)
	if err != nil {
		c.logf("request failed: %v", err)
		return nil, errorf(CodeNetworkError, "cannot reach the server at %s", c.baseURL)
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 32<<20))
	if err != nil {
		return nil, errorf(CodeNetworkError, "connection to %s dropped while reading the response", c.baseURL)
	}
	c.logf("status %d", resp.StatusCode)
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		return data, nil
	}
	serverMsg := serverError(data)
	switch {
	case resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden:
		return nil, errorf(CodePermissionDenied, "the server rejected the API token (HTTP %d); check the token file", resp.StatusCode).field("token-file")
	case resp.StatusCode == http.StatusBadRequest:
		e := errorf(CodeValidationFailed, "the server rejected the request (HTTP 400); see data.server_error")
		e.Data = map[string]any{"server_error": serverMsg}
		return nil, e
	case resp.StatusCode == http.StatusNotFound:
		e := errorf(CodeUnsupported, "the server does not have this endpoint (HTTP 404); is it an older version?")
		e.Data = map[string]any{"server_error": serverMsg}
		return nil, e
	case resp.StatusCode >= 500:
		return nil, errorf(CodeNetworkError, "the server failed (HTTP %d)", resp.StatusCode)
	default:
		e := errorf(CodeInternal, "unexpected HTTP %d from the server", resp.StatusCode)
		e.Data = map[string]any{"server_error": serverMsg}
		return nil, e
	}
}

func serverError(body []byte) string {
	var m struct {
		Error string `json:"error"`
	}
	if json.Unmarshal(body, &m) == nil && m.Error != "" {
		return m.Error
	}
	if len(body) > 500 {
		body = body[:500]
	}
	return string(body)
}
