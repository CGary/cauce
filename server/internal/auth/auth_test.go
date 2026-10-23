package auth

import (
	"net/http/httptest"
	"testing"
)

func TestUserFromRequest(t *testing.T) {
	t.Setenv(EnvUserID, "  u1  ")
	if got := UserFromRequest(httptest.NewRequest("GET", "/", nil)); got != "u1" {
		t.Fatalf("user = %q", got)
	}
}
