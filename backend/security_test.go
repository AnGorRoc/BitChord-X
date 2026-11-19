package main

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/KabirSinghBhatia/BitChord/backend/config"
	"github.com/KabirSinghBhatia/BitChord/backend/party"
	"github.com/KabirSinghBhatia/BitChord/backend/protocol"
)

func TestClientIPTrustedProxy(t *testing.T) {
	origTrust := config.TrustProxy
	origHeader := config.ClientIPHeader
	config.TrustProxy = true
	config.ClientIPHeader = "CF-Connecting-IP"
	defer func() {
		config.TrustProxy = origTrust
		config.ClientIPHeader = origHeader
	}()

	// From Cloudflare edge IP: trusts CF-Connecting-IP
	reqCF := httptest.NewRequest(http.MethodGet, "/api/time", nil)
	reqCF.RemoteAddr = "104.16.1.1:44321"
	reqCF.Header.Set("CF-Connecting-IP", "203.0.113.50")
	if got := clientIP(reqCF); got != "203.0.113.50" {
		t.Fatalf("expected 203.0.113.50 from Cloudflare edge, got %q", got)
	}

	// From local cloudflared tunnel (127.0.0.1): trusts CF-Connecting-IP
	reqLocal := httptest.NewRequest(http.MethodGet, "/api/time", nil)
	reqLocal.RemoteAddr = "127.0.0.1:50000"
	reqLocal.Header.Set("CF-Connecting-IP", "203.0.113.51")
	if got := clientIP(reqLocal); got != "203.0.113.51" {
		t.Fatalf("expected 203.0.113.51 from loopback tunnel, got %q", got)
	}

	// From untrusted public IP: ignores spoofed CF-Connecting-IP
	reqSpoof := httptest.NewRequest(http.MethodGet, "/api/time", nil)
	reqSpoof.RemoteAddr = "198.51.100.77:12345"
	reqSpoof.Header.Set("CF-Connecting-IP", "1.2.3.4")
	if got := clientIP(reqSpoof); got != "198.51.100.77" {
		t.Fatalf("expected remote IP 198.51.100.77 for untrusted caller, got %q", got)
	}

	// Malformed header from trusted proxy: falls back to RemoteAddr
	reqBadHeader := httptest.NewRequest(http.MethodGet, "/api/time", nil)
	reqBadHeader.RemoteAddr = "127.0.0.1:50000"
	reqBadHeader.Header.Set("CF-Connecting-IP", "not-an-ip")
	if got := clientIP(reqBadHeader); got != "127.0.0.1" {
		t.Fatalf("expected fallback 127.0.0.1 for invalid header, got %q", got)
	}
}

func TestIsTailscaleOrLoopback(t *testing.T) {
	if !config.IsTailscaleOrLoopback("100.101.102.103:54321") {
		t.Fatal("expected Tailscale CGNAT IP to be allowed")
	}
	if !config.IsTailscaleOrLoopback("127.0.0.1:8001") {
		t.Fatal("expected loopback IP to be allowed")
	}
	if config.IsTailscaleOrLoopback("8.8.8.8:8001") {
		t.Fatal("expected public IP to be rejected")
	}
}

func TestSecurityHeadersMiddleware(t *testing.T) {
	handler := securityHeadersMiddleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	req := httptest.NewRequest(http.MethodGet, "/healthz", nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Header().Get("X-Content-Type-Options") != "nosniff" {
		t.Fatalf("missing X-Content-Type-Options header")
	}
	if rec.Header().Get("X-Frame-Options") != "DENY" {
		t.Fatalf("missing X-Frame-Options header")
	}
}

func TestTrackFromWireSanitization(t *testing.T) {
	tr := party.TrackFromWire(map[string]interface{}{
		"videoId":      "dQw4w9WgXcQ\n\u202E",
		"title":        "Song\x00Title\u202E",
		"artist":       "Artist\tName",
		"thumbnailUrl": "javascript:alert(1)",
		"durationMs":   float64(999999999999),
	})
	if tr == nil {
		t.Fatal("expected valid track")
	}
	if tr.VideoId != "dQw4w9WgXcQ" {
		t.Fatalf("unexpected videoId: %q", tr.VideoId)
	}
	if tr.Title != "SongTitle" {
		t.Fatalf("unexpected title: %q", tr.Title)
	}
	if tr.ThumbnailUrl != nil {
		t.Fatalf("expected javascript: thumbnailUrl to be dropped, got %q", *tr.ThumbnailUrl)
	}
	if tr.DurationMs == nil || *tr.DurationMs != 24*60*60*1000 {
		t.Fatalf("expected clamped durationMs, got %v", tr.DurationMs)
	}
}

func TestJoinRequestSanitization(t *testing.T) {
	req := protocol.JoinRequest{
		UserId:      "user123\x00",
		DeviceId:    "dev123",
		DisplayName: "Pedro\n\u202E",
	}
	if err := req.Validate(); err != nil {
		t.Fatalf("unexpected validation error: %v", err)
	}
	if req.DisplayName != "Pedro" {
		t.Fatalf("expected sanitized displayName 'Pedro', got %q", req.DisplayName)
	}
	if req.UserId != "user123" {
		t.Fatalf("expected sanitized userId 'user123', got %q", req.UserId)
	}
}
