package feedback

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base32"
	"encoding/base64"
	"encoding/hex"
	"regexp"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/KabirSinghBhatia/BitChord/backend/presence"
)

// Text bounds, in characters.
const (
	minTitle   = 3
	maxTitle   = 140
	maxBody    = 8000
	maxComment = 1000
)

var (
	tokenPattern    = regexp.MustCompile(`^fbr_[A-Za-z0-9_-]{43}$`)
	publicIDPattern = regexp.MustCompile(`^fb_[a-z2-7]{16}$`)
	localePattern   = regexp.MustCompile(`^[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8}){0,3}$`)
	versionPattern  = regexp.MustCompile(`^[A-Za-z0-9._+-]{0,32}$`)
	shortPattern    = regexp.MustCompile(`^[\p{L}\p{N} ._,()/:+@x-]{0,64}$`)
)

var b32 = base32.StdEncoding.WithPadding(base32.NoPadding)

func randomBytes(n int) []byte {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err) // crypto/rand does not fail on supported platforms
	}
	return b
}

// newToken makes a reporter token: 256 random bits the client keeps and the server only hashes.
func newToken() string {
	return "fbr_" + base64.RawURLEncoding.EncodeToString(randomBytes(32))
}

func hashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

// newPublicID makes a ticket ID that cannot be guessed from another (80 random bits).
func newPublicID() string {
	return "fb_" + strings.ToLower(b32.EncodeToString(randomBytes(10)))
}

func validUUID(s string) bool {
	_, ok := presence.ParseID(s)
	return ok
}

// cleanText makes user text safe to keep: valid UTF-8, no NUL or other control characters
// except line breaks and tabs, line breaks normalised, trimmed. It is shown on the dashboard
// as text, never as HTML, so nothing else needs escaping here.
func cleanText(s string) string {
	s = strings.ToValidUTF8(s, "�")
	s = strings.ReplaceAll(s, "\r\n", "\n")
	s = strings.Map(func(r rune) rune {
		switch {
		case r == '\n' || r == '\t':
			return r
		case r == '\r':
			return '\n'
		case unicode.IsControl(r), r == ' ', r == ' ', r == '‮', r == '‭':
			// Line and paragraph separators break log viewers; the bidi overrides can make a
			// title read differently from what it is.
			return -1
		}
		return r
	}, s)
	return strings.TrimSpace(s)
}

// cleanLine is cleanText for a one-line field: line breaks become spaces.
func cleanLine(s string) string {
	return strings.Join(strings.Fields(cleanText(s)), " ")
}

func runeLen(s string) int { return utf8.RuneCountInString(s) }

// short keeps a short technical field (device model, OS version) only if it looks like one.
func short(s string) string {
	s = cleanLine(s)
	if !shortPattern.MatchString(s) {
		return ""
	}
	return s
}

func cleanDiagnostics(d *Diagnostics) Diagnostics {
	if d == nil {
		return Diagnostics{}
	}
	out := Diagnostics{
		DeviceModel: short(d.DeviceModel),
		OSVersion:   short(d.OSVersion),
		ABI:         short(d.ABI),
		Screen:      short(d.Screen),
	}
	switch d.TelemetryLevel {
	case "full", "minimal", "off":
		out.TelemetryLevel = d.TelemetryLevel
	}
	// The install ID links a report to that install's error reports, which only exist at Full.
	if out.TelemetryLevel == "full" && validUUID(d.InstallID) {
		out.InstallID = strings.ToLower(d.InstallID)
	}
	return out
}
