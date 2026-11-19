package telemetry

import "regexp"

// Level is how much a client agreed to tell the server. Clients that chose
// "off" never connect, so the server only ever sees the other two. A client
// that says nothing (every release before the levels existed) is Full.
type Level int

const (
	// Full is the stable install ID with sessions, and error reports with
	// their details and device information.
	Full Level = iota
	// Minimal is a headcount and nothing that can be followed: the client
	// sends an ID that changes every UTC day, and errors are counted by type.
	Minimal
)

// ParseLevel reads the "level" a client sends. Only the exact word "minimal"
// lowers it; anything else, including nothing, is Full.
func ParseLevel(s string) Level {
	if s == "minimal" {
		return Minimal
	}
	return Full
}

var errorTypePattern = regexp.MustCompile(`^[A-Za-z0-9_.:-]{1,64}$`)

// CleanErrorType keeps an error type that looks like a type and turns
// anything else into "other". A minimal report is counted by its type, so
// this is the one free-text field that reaches the database from it, and it
// must not be a way to smuggle a message, a path or an ID in.
func CleanErrorType(s string) string {
	if errorTypePattern.MatchString(s) {
		return s
	}
	return "other"
}
