package telemetry

import (
	"regexp"
	"strconv"
	"strings"
)

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

// NormalizeVersion standardizes app version identifiers across platforms.
// Specifically, desktop installer builds (which attach a build number like 1.10.38, 1.10.43, or 1.10)
// are mapped to their unified public release version (e.g. 1.10.0), ensuring telemetry metrics
// group identical multiplatform releases together.
func NormalizeVersion(s string) string {
	s = strings.TrimSpace(s)
	s = strings.TrimPrefix(s, "v")
	if s == "" {
		return ""
	}
	switch s {
	case "1.10.38", "1.10.43", "1.10":
		return "1.10.0"
	case "1.9.38":
		return "1.9.3"
	case "1.9.37":
		return "1.9.2"
	case "1.9.36":
		return "1.9.1"
	}
	// General rule for desktop builds: if in format X.Y.<buildCode> where X.Y is a known major.minor
	// and buildCode >= 20, map to X.Y.0
	clean := strings.Split(s, "-")[0]
	segments := strings.Split(clean, ".")
	if len(segments) == 2 {
		return segments[0] + "." + segments[1] + ".0"
	}
	if len(segments) == 3 {
		if build, err := strconv.Atoi(segments[2]); err == nil && build >= 20 {
			return segments[0] + "." + segments[1] + ".0"
		}
	}
	return s
}
