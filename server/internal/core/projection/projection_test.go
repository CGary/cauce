package projection

import (
	"encoding/json"
	"net/url"
	"testing"
	"time"

	"cauce/server/internal/core/event"
)

func TestParamsNow(t *testing.T) {
	fallback := time.Date(2026, 10, 22, 13, 0, 0, 0, time.UTC)
	tests := []struct {
		name    string
		params  Params
		want    time.Time
		wantErr bool
	}{
		{name: "missing uses fallback", params: Params{}, want: fallback},
		{name: "explicit RFC3339", params: Params{"now": json.RawMessage(`"2026-10-20T10:00:00Z"`)}, want: time.Date(2026, 10, 20, 10, 0, 0, 0, time.UTC)},
		{name: "bad format", params: Params{"now": json.RawMessage(`"ayer"`)}, wantErr: true},
		{name: "not a string", params: Params{"now": json.RawMessage(`5`)}, wantErr: true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := tt.params.Now(fallback)
			if tt.wantErr {
				if err == nil {
					t.Fatal("want error, got nil")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if !got.Equal(tt.want) {
				t.Fatalf("now = %s, want %s", got, tt.want)
			}
		})
	}
}

func TestParamsMissingRequiredNow(t *testing.T) {
	if _, err := (Params{}).Now(time.Time{}); err == nil {
		t.Fatal("want error for missing now without fallback")
	}
}

func TestParamsFromValues(t *testing.T) {
	params := ParamsFromValues(url.Values{
		"tz":           {"America/La_Paz"},
		"destinatario": {"Juan"},
		"ignored":      {},
	})
	var tz string
	if _, err := params.Decode("tz", &tz); err != nil || tz != "America/La_Paz" {
		t.Fatalf("tz = %q, err = %v", tz, err)
	}
	if _, ok := params["ignored"]; ok {
		t.Fatal("empty query value should be skipped")
	}
}

func TestRegistry(t *testing.T) {
	reg := NewRegistry()
	reg.MustRegister(Func{ProjectionKey: "demo.one", RunFunc: func([]event.Envelope, Params) (any, error) { return nil, nil }})
	if _, ok := reg.Get("demo.one"); !ok {
		t.Fatal("registered projection not found")
	}
	if !reg.HasFeature("demo") || reg.HasFeature("otro") {
		t.Fatal("HasFeature wrong")
	}
	if err := reg.Register(Func{ProjectionKey: "demo.one"}); err == nil {
		t.Fatal("want duplicate error")
	}
	for _, key := range []string{"", "demo", "demo.", ".one", "Demo.one", "demo.one.two"} {
		if err := reg.Register(Func{ProjectionKey: key}); err == nil {
			t.Errorf("key %q should be rejected", key)
		}
	}
}
