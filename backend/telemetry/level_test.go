package telemetry

import "testing"

func TestParseLevelOnlyMinimalLowersIt(t *testing.T) {
	cases := map[string]Level{
		"":        Full,
		"full":    Full,
		"minimal": Minimal,
		"MINIMAL": Full,
		"off":     Full,
		"garbage": Full,
	}
	for in, want := range cases {
		if got := ParseLevel(in); got != want {
			t.Errorf("ParseLevel(%q) = %v, want %v", in, got, want)
		}
	}
}

func TestCleanErrorType(t *testing.T) {
	good := []string{"uncaught_crash", "playback.error", "io:timeout", "a-b"}
	for _, in := range good {
		if got := CleanErrorType(in); got != in {
			t.Errorf("CleanErrorType(%q) = %q, want it unchanged", in, got)
		}
	}
	bad := []string{
		"",
		"has space",
		"/home/maria/file.kt",
		"user@example.com",
		"a very long one with a message in it",
		"0123456789012345678901234567890123456789012345678901234567890123456789",
	}
	for _, in := range bad {
		if got := CleanErrorType(in); got != "other" {
			t.Errorf("CleanErrorType(%q) = %q, want other", in, got)
		}
	}
}

func TestFirstSightingCountsAnIDOncePerDay(t *testing.T) {
	s := &Store{}
	if !s.firstSightingToday("2026-11-18", "id-1") {
		t.Fatal("the first ping of the day should count")
	}
	if s.firstSightingToday("2026-11-18", "id-1") {
		t.Fatal("a second ping from the same ID the same day should not count")
	}
	if !s.firstSightingToday("2026-11-18", "id-2") {
		t.Fatal("another ID counts")
	}
	// The next day starts again, and what was remembered is gone.
	if !s.firstSightingToday("2026-11-19", "id-1") {
		t.Fatal("a new day counts again")
	}
	if len(s.minimalSeen) != 1 {
		t.Fatalf("yesterday's IDs should not be kept, have %d", len(s.minimalSeen))
	}
}
