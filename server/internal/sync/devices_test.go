package sync

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"cauce/server/internal/auth"
)

// Invented test token; never a real one.
const devicesTestToken = "test-token-0123456789abcdefghijklmnop"

var syncNow = time.Date(2026, 11, 26, 12, 0, 0, 0, time.UTC)

func fixedNow() time.Time { return syncNow }

// eventFrom is validEvent with another id and device_id.
func eventFrom(id, device string) string {
	s := strings.Replace(validEvent, `"id": "e1"`, `"id": "`+id+`"`, 1)
	return strings.Replace(s, `"device_id": "test"`, `"device_id": "`+device+`"`, 1)
}

// syncMux is the cmd/cauce wiring of the sync routes behind the token.
func syncMux(p *Pusher) http.Handler {
	mux := http.NewServeMux()
	mux.Handle("POST /sync/push", p.PushHandler())
	mux.Handle("GET /sync/pull", (&Puller{Store: p.Store, Now: fixedNow}).PullHandler())
	mux.Handle("GET /sync/devices", (&Devices{Store: p.Store, Now: fixedNow}).Handler())
	return auth.Require(auth.Config{Enabled: true, Token: devicesTestToken}, mux)
}

func call(t *testing.T, h http.Handler, method, target, body string, out any) int {
	t.Helper()
	var req *http.Request
	if body != "" {
		req = httptest.NewRequest(method, target, strings.NewReader(body))
	} else {
		req = httptest.NewRequest(method, target, nil)
	}
	req.Header.Set("Authorization", "Bearer "+devicesTestToken)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if out != nil && rec.Code == http.StatusOK {
		if err := json.Unmarshal(rec.Body.Bytes(), out); err != nil {
			t.Fatalf("%s %s: %v\n%s", method, target, err, rec.Body.String())
		}
	}
	return rec.Code
}

func devicesOf(t *testing.T, h http.Handler) DevicesResult {
	t.Helper()
	var res DevicesResult
	if code := call(t, h, http.MethodGet, "/sync/devices", "", &res); code != http.StatusOK {
		t.Fatalf("/sync/devices status = %d", code)
	}
	return res
}

func device(res DevicesResult, id string) *DeviceStatus {
	for i := range res.Devices {
		if res.Devices[i].DeviceID == id {
			return &res.Devices[i]
		}
	}
	return nil
}

func TestPushRecordsDevicesAndServerSeq(t *testing.T) {
	tests := []struct {
		name        string
		batches     [][]string
		wantSeq     []int64
		wantDevices []string
	}{
		{
			name:        "one device",
			batches:     [][]string{{eventFrom("e1", "RMX5070")}},
			wantSeq:     []int64{1},
			wantDevices: []string{"RMX5070"},
		},
		{
			name:        "distinct devices in one batch",
			batches:     [][]string{{eventFrom("e1", "web"), eventFrom("e2", "cli"), eventFrom("e3", "web")}},
			wantSeq:     []int64{3},
			wantDevices: []string{"cli", "web"},
		},
		{
			name:        "all duplicates still return server_seq",
			batches:     [][]string{{eventFrom("e1", "web"), eventFrom("e2", "web")}, {eventFrom("e1", "web")}},
			wantSeq:     []int64{2, 2},
			wantDevices: []string{"web"},
		},
		{
			name:        "server_seq covers older duplicates",
			batches:     [][]string{{eventFrom("e1", "web")}, {eventFrom("e2", "cli")}, {eventFrom("e1", "web")}},
			wantSeq:     []int64{1, 2, 2},
			wantDevices: []string{"cli", "web"},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p := newPusher(t)
			p.Now = fixedNow
			h := syncMux(p)
			for i, batch := range tt.batches {
				var res PushResult
				body := `{"events":[` + strings.Join(batch, ",") + `]}`
				if code := call(t, h, http.MethodPost, "/sync/push", body, &res); code != http.StatusOK {
					t.Fatalf("push %d status = %d", i, code)
				}
				if res.ServerSeq != tt.wantSeq[i] {
					t.Fatalf("push %d server_seq = %d, want %d", i, res.ServerSeq, tt.wantSeq[i])
				}
			}
			devs := devicesOf(t, h)
			if len(devs.Devices) != len(tt.wantDevices) {
				t.Fatalf("devices = %+v, want %v", devs.Devices, tt.wantDevices)
			}
			for i, want := range tt.wantDevices {
				d := devs.Devices[i]
				if d.DeviceID != want {
					t.Fatalf("devices[%d] = %q, want %q (sorted)", i, d.DeviceID, want)
				}
				if d.LastPushAt == nil || *d.LastPushAt != "2026-11-26T12:00:00Z" {
					t.Fatalf("%s last_push_at = %v", want, d.LastPushAt)
				}
				if d.LastPullAt != nil || d.PulledThroughSeq != nil {
					t.Fatalf("%s has pull data without pulling: %+v", want, d)
				}
			}
		})
	}
}

func TestPullDeviceID(t *testing.T) {
	tests := []struct {
		name       string
		query      string
		wantStatus int
		wantDevice string // "" means nothing recorded
		wantSeq    int64
	}{
		{"without device_id (old phone)", "since=0", http.StatusOK, "", 0},
		{"with device_id", "since=0&device_id=RMX5070", http.StatusOK, "RMX5070", 2},
		{"with device_id and cursor at the end", "since=2&device_id=RMX5070", http.StatusOK, "RMX5070", 2},
		{"device_id of 64 characters", "since=0&device_id=" + strings.Repeat("d", 64), http.StatusOK, strings.Repeat("d", 64), 2},
		{"empty device_id", "since=0&device_id=", http.StatusBadRequest, "", 0},
		{"device_id too long", "since=0&device_id=" + strings.Repeat("d", 65), http.StatusBadRequest, "", 0},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p := newPusher(t)
			h := syncMux(p)
			body := `{"events":[` + eventFrom("e1", "web") + `,` + eventFrom("e2", "web") + `]}`
			if code := call(t, h, http.MethodPost, "/sync/push", body, nil); code != http.StatusOK {
				t.Fatalf("seed push status = %d", code)
			}
			var pulled PullResult
			code := call(t, h, http.MethodGet, "/sync/pull?"+tt.query, "", &pulled)
			if code != tt.wantStatus {
				t.Fatalf("pull status = %d, want %d", code, tt.wantStatus)
			}
			devs := devicesOf(t, h)
			if devs.ServerSeq != 2 || devs.Now != "2026-11-26T12:00:00Z" {
				t.Fatalf("devices header = %+v", devs)
			}
			// Only "web" (from the push) unless the pull recorded a device.
			want := 1
			if tt.wantDevice != "" {
				want = 2
			}
			if len(devs.Devices) != want {
				t.Fatalf("devices = %+v", devs.Devices)
			}
			if tt.wantDevice == "" {
				return
			}
			d := device(devs, tt.wantDevice)
			if d == nil || d.LastPullAt == nil || *d.LastPullAt != "2026-11-26T12:00:00Z" {
				t.Fatalf("device %q = %+v", tt.wantDevice, d)
			}
			if d.PulledThroughSeq == nil || *d.PulledThroughSeq != tt.wantSeq || *d.PulledThroughSeq != pulled.NextSince {
				t.Fatalf("pulled_through_seq = %v, want %d = next_since %d", d.PulledThroughSeq, tt.wantSeq, pulled.NextSince)
			}
			if d.LastPushAt != nil {
				t.Fatalf("a pull set last_push_at: %+v", d)
			}
		})
	}
}

func TestPullKeepsPushTime(t *testing.T) {
	p := newPusher(t)
	p.Now = func() time.Time { return syncNow.Add(-time.Hour) }
	h := syncMux(p)
	if code := call(t, h, http.MethodPost, "/sync/push", `{"events":[`+eventFrom("e1", "RMX5070")+`]}`, nil); code != http.StatusOK {
		t.Fatalf("push status = %d", code)
	}
	if code := call(t, h, http.MethodGet, "/sync/pull?since=0&device_id=RMX5070", "", nil); code != http.StatusOK {
		t.Fatalf("pull status = %d", code)
	}
	d := device(devicesOf(t, h), "RMX5070")
	if d == nil || d.LastPushAt == nil || *d.LastPushAt != "2026-11-26T11:00:00Z" || d.LastPullAt == nil || *d.LastPullAt != "2026-11-26T12:00:00Z" {
		t.Fatalf("device = %+v", d)
	}
}

func TestDevicesShapeAndAuth(t *testing.T) {
	p := newPusher(t)
	h := syncMux(p)

	tests := []struct {
		name   string
		header string
		want   int
	}{
		{"no token", "", http.StatusUnauthorized},
		{"wrong token", "Bearer " + devicesTestToken + "x", http.StatusUnauthorized},
		{"token", "Bearer " + devicesTestToken, http.StatusOK},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodGet, "/sync/devices", nil)
			if tt.header != "" {
				req.Header.Set("Authorization", tt.header)
			}
			rec := httptest.NewRecorder()
			h.ServeHTTP(rec, req)
			if rec.Code != tt.want {
				t.Fatalf("status = %d, want %d", rec.Code, tt.want)
			}
			if tt.want != http.StatusOK {
				return
			}
			// Empty log: devices is [] (not null) and server_seq is 0.
			var raw map[string]json.RawMessage
			if err := json.Unmarshal(rec.Body.Bytes(), &raw); err != nil {
				t.Fatal(err)
			}
			if string(raw["devices"]) != "[]" || string(raw["server_seq"]) != "0" || string(raw["now"]) != `"2026-11-26T12:00:00Z"` {
				t.Fatalf("body = %s", rec.Body.String())
			}
		})
	}

	// Nulls are explicit in the JSON.
	if code := call(t, h, http.MethodPost, "/sync/push", `{"events":[`+eventFrom("e1", "web")+`]}`, nil); code != http.StatusOK {
		t.Fatalf("push status = %d", code)
	}
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/sync/devices", nil)
	req.Header.Set("Authorization", "Bearer "+devicesTestToken)
	h.ServeHTTP(rec, req)
	var body struct {
		Devices []map[string]json.RawMessage `json:"devices"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(body.Devices) != 1 {
		t.Fatalf("body = %s", rec.Body.String())
	}
	for _, key := range []string{"device_id", "last_push_at", "last_pull_at", "pulled_through_seq"} {
		if _, ok := body.Devices[0][key]; !ok {
			t.Fatalf("device row lacks %q: %s", key, rec.Body.String())
		}
	}
	if string(body.Devices[0]["last_pull_at"]) != "null" || string(body.Devices[0]["pulled_through_seq"]) != "null" {
		t.Fatalf("never-pulled device = %s", rec.Body.String())
	}
}

func TestDevicesUserIsolation(t *testing.T) {
	p := newPusher(t) // request user is u1
	h := syncMux(p)
	ctx := context.Background()

	foreign := strings.Replace(eventFrom("x1", "OTHERPHONE"), `"user_id": "u1"`, `"user_id": "u2"`, 1)
	if _, err := p.Push(ctx, "u2", []byte(`{"events":[`+foreign+`]}`)); err != nil {
		t.Fatal(err)
	}
	if err := p.Store.RecordPull(ctx, "u2", "OTHERPHONE", syncNow, 1); err != nil {
		t.Fatal(err)
	}
	if code := call(t, h, http.MethodPost, "/sync/push", `{"events":[`+eventFrom("e1", "web")+`]}`, nil); code != http.StatusOK {
		t.Fatalf("push status = %d", code)
	}

	devs := devicesOf(t, h)
	if len(devs.Devices) != 1 || devs.Devices[0].DeviceID != "web" {
		t.Fatalf("u1 sees %+v", devs.Devices)
	}
	// server_seq is per user: u2's event (seq 1) is not counted for u1, whose
	// only event is seq 2; the max is still 2 because seqs are global.
	if devs.ServerSeq != 2 {
		t.Fatalf("server_seq = %d", devs.ServerSeq)
	}
	u2, err := (&Devices{Store: p.Store, Now: fixedNow}).List(ctx, "u2")
	if err != nil {
		t.Fatal(err)
	}
	if u2.ServerSeq != 1 || len(u2.Devices) != 1 || u2.Devices[0].DeviceID != "OTHERPHONE" {
		t.Fatalf("u2 = %+v", u2)
	}
}
