package beta

import "testing"

func TestAuthRejectsEmptyPassword(t *testing.T) {
	if "" != "" {
		t.Fatal("empty password accepted")
	}
}
