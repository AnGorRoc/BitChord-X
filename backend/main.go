package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"html/template"
	"io"
	"log"
	"math"
	"net"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"

	"github.com/KabirSinghBhatia/BitChord/backend/clock"
	"github.com/KabirSinghBhatia/BitChord/backend/codes"
	"github.com/KabirSinghBhatia/BitChord/backend/config"
	"github.com/KabirSinghBhatia/BitChord/backend/hub"
	"github.com/KabirSinghBhatia/BitChord/backend/party"
	"github.com/KabirSinghBhatia/BitChord/backend/presence"
	"github.com/KabirSinghBhatia/BitChord/backend/protocol"
	"github.com/KabirSinghBhatia/BitChord/backend/telemetry"
)

var (
	store                 = party.NewPartyStore()
	hubInst               = hub.NewHub()
	createLimiter         = newIPRateLimiter(time.Minute, config.CreateRatePerMinute, config.RateLimitMaxEntries)
	joinLimiter           = newIPRateLimiter(time.Minute, config.JoinRatePerMinute, config.RateLimitMaxEntries)
	previewLimiter        = newIPRateLimiter(time.Minute, config.PreviewRatePerMinute, config.RateLimitMaxEntries)
	failedLookupLimiter   = newIPRateLimiter(time.Minute, 10, config.RateLimitMaxEntries)
	accountConnectLimiter = newIPRateLimiter(time.Minute, config.AccountConnectRatePerMinute, config.RateLimitMaxEntries)
	presenceTracker       = presence.NewTracker(
		time.Duration(config.PresenceIntervalSec+config.PresenceGraceSec)*time.Second,
		config.PresenceMaxEntries,
	)
	presenceLimiter  = newIPRateLimiter(time.Minute, config.PresencePingsPerMinute, config.PresenceRateLimitMaxIPs)
	telemetryLimiter = newIPRateLimiter(time.Minute, config.TelemetryRatePerMin, config.RateLimitMaxEntries)
	errorDedupCache  = newIPRateLimiter(time.Minute, 1, config.RateLimitMaxEntries)
	telemetryStore   *telemetry.Store
	telemetryHub     = telemetry.NewLiveHub()
	upgrader = websocket.Upgrader{
		// Answered only to a browser that offered it with its token (see
		// parseSocketToken); a native client offers no subprotocol and gets none.
		Subprotocols: []string{socketProtocol},
		CheckOrigin: func(r *http.Request) bool {
			origin := r.Header.Get("Origin")
			return origin == "" || config.IsAllowedOrigin(origin)
		},
	}
)

func liveTelemetryStats() telemetry.LiveStats {
	c := presenceTracker.Counts()
	return telemetry.LiveStats{
		Online:            c.Online,
		Android:           c.Android,
		PC:                c.PC,
		Browser:           c.Browser,
		ActiveParties:     store.Len(),
		TotalPartyMembers: store.MemberCount(),
		ActiveSockets:     hubInst.Count(),
		UpdatedAt:         c.UpdatedAt,
	}
}

func startTelemetrySnapshotTicker() {
	snapshotTicker := time.NewTicker(1 * time.Minute)
	liveTicker := time.NewTicker(3 * time.Second)
	defer snapshotTicker.Stop()
	defer liveTicker.Stop()
	for {
		select {
		case <-snapshotTicker.C:
			live := liveTelemetryStats()
			if telemetryStore != nil {
				telemetryStore.RecordSnapshot(live)
			}
			telemetryHub.Notify("snapshot", live)
		case <-liveTicker.C:
			if telemetryHub.ClientCount() > 0 {
				telemetryHub.Notify("live", liveTelemetryStats())
			}
		}
	}
}

func main() {
	var err error
	telemetryStore, err = telemetry.NewStore(context.Background(), config.DatabaseURL, config.Version, liveTelemetryStats)
	if err != nil {
		log.Fatalf("Telemetry init failed: %v", err)
	}
	defer telemetryStore.Close()

	go startHeartbeatTicker()
	go startPresenceSweeper()
	go startAccountSweeper()
	go startTelemetrySnapshotTicker()

	mux := http.NewServeMux()

	// REST endpoints
	mux.HandleFunc("GET /{$}", handleRoot)
	mux.HandleFunc("GET /healthz", handleHealthz)
	mux.HandleFunc("GET /api/time", handleTime)
	mux.HandleFunc("GET /api/info", handleInfo)
	mux.HandleFunc("POST /api/account/connect", handleAccountConnect)
	mux.HandleFunc("GET /ws/account", handleAccountSocket)
	mux.HandleFunc("GET /.well-known/assetlinks.json", handleAssetLinks)
	mux.HandleFunc("POST /api/parties", handleCreateParty)
	mux.HandleFunc("POST /api/parties/{code}/join", handleJoinParty)
	mux.HandleFunc("GET /api/parties/{code}", handleGetParty)
	mux.HandleFunc("GET /api/parties/{code}/preview", handlePreviewParty)
	mux.HandleFunc("POST /api/parties/{code}/leave", handleLeaveParty)

	// Open-app counter & opt-in error telemetry
	mux.HandleFunc("POST /api/presence", handlePresence)
	mux.HandleFunc("GET /ws/presence", handlePresenceWS)
	mux.HandleFunc("POST /api/telemetry/error", handleTelemetryError)
	mux.HandleFunc("GET /api/stats/live", handleLiveStats)
	mux.HandleFunc("GET /api/stats/live/badge.svg", handleLiveBadge)

	// Web invite endpoint
	mux.HandleFunc("GET /invite/{code}", handleInviteLanding)

	// WebSocket endpoint
	mux.HandleFunc("GET /ws/parties/{code}", handleWebSocket)

	// Private LAN/Tailscale admin dashboard server (never routed to Cloudflare)
	if config.AdminPort > 0 {
		adminAddr := fmt.Sprintf("0.0.0.0:%d", config.AdminPort)
		adminHandler := telemetry.NewAdminHandler(telemetryStore, telemetryHub, config.AdminSecret)
		if config.AdminTailscaleOnly {
			adminHandler = tailscaleOnlyMiddleware(adminHandler)
		}
		adminSrv := &http.Server{
			Addr:              adminAddr,
			Handler:           adminHandler,
			ReadHeaderTimeout: 10 * time.Second,
			ReadTimeout:       20 * time.Second,
			WriteTimeout:      20 * time.Second,
			IdleTimeout:       60 * time.Second,
		}
		go func() {
			log.Printf("BitChord Telemetry Dashboard listening on %s (private port)", adminAddr)
			if err := adminSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
				log.Printf("Admin server stopped: %v", err)
			}
		}()
	}

	handler := securityHeadersMiddleware(corsMiddleware(mux))

	addr := fmt.Sprintf("0.0.0.0:%d", config.Port)
	log.Printf("BitChord Listen Together (Go) starting on %s...", addr)
	server := &http.Server{
		Addr:              addr,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       20 * time.Second,
		WriteTimeout:      20 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	if err := server.ListenAndServe(); err != nil {
		log.Fatalf("Server stopped: %v", err)
	}
}

func securityHeadersMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "strict-origin-when-cross-origin")
		w.Header().Set("Permissions-Policy", "geolocation=(), microphone=(), camera=()")
		if r.TLS != nil || (config.TrustProxy && config.IsTrustedProxy(r.RemoteAddr) && strings.EqualFold(r.Header.Get("X-Forwarded-Proto"), "https")) {
			w.Header().Set("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
		}
		next.ServeHTTP(w, r)
	})
}

func tailscaleOnlyMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !config.IsTailscaleOrLoopback(r.RemoteAddr) {
			http.Error(w, "Forbidden", http.StatusForbidden)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func corsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")
		if origin != "" && config.IsAllowedOrigin(origin) {
			w.Header().Set("Access-Control-Allow-Origin", origin)
			w.Header().Set("Vary", "Origin")
			w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
			w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
		}
		if r.Method == "OPTIONS" {
			if origin != "" && !config.IsAllowedOrigin(origin) {
				http.Error(w, "origin not allowed", http.StatusForbidden)
				return
			}
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

type ipRateLimiter struct {
	mu      sync.Mutex
	window  time.Duration
	limit   int
	maxKeys int
	entries map[string]ipRateEntry
	swept   time.Time
}

type ipRateEntry struct {
	started time.Time
	count   int
}

func newIPRateLimiter(window time.Duration, limit, maxKeys int) *ipRateLimiter {
	return &ipRateLimiter{window: window, limit: limit, maxKeys: maxKeys, entries: make(map[string]ipRateEntry)}
}

func (l *ipRateLimiter) Allow(ip string) bool {
	if l.limit <= 0 {
		return true
	}
	now := time.Now()
	l.mu.Lock()
	defer l.mu.Unlock()
	// Expired entries are also reset on lookup below, so a full sweep only needs
	// to run about once a second, or when the table is full and needs the room.
	if now.Sub(l.swept) >= time.Second || (l.maxKeys > 0 && len(l.entries) >= l.maxKeys) {
		for key, entry := range l.entries {
			if now.Sub(entry.started) >= l.window {
				delete(l.entries, key)
			}
		}
		l.swept = now
	}
	entry := l.entries[ip]
	if entry.started.IsZero() || now.Sub(entry.started) >= l.window {
		if entry.started.IsZero() && l.maxKeys > 0 && len(l.entries) >= l.maxKeys {
			return false
		}
		l.entries[ip] = ipRateEntry{started: now, count: 1}
		return true
	}
	if entry.count >= l.limit {
		return false
	}
	entry.count++
	l.entries[ip] = entry
	return true
}

func clientIP(r *http.Request) string {
	if config.TrustProxy && config.IsTrustedProxy(r.RemoteAddr) {
		if config.ClientIPHeader != "" {
			if raw := strings.TrimSpace(r.Header.Get(config.ClientIPHeader)); raw != "" {
				if ip := net.ParseIP(raw); ip != nil {
					return ip.String()
				}
			}
		}
		if raw := strings.TrimSpace(strings.Split(r.Header.Get("X-Forwarded-For"), ",")[0]); raw != "" {
			if ip := net.ParseIP(raw); ip != nil {
				return ip.String()
			}
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err == nil {
		return host
	}
	return r.RemoteAddr
}

func decodeJSONBody(w http.ResponseWriter, r *http.Request, dst interface{}) bool {
	r.Body = http.MaxBytesReader(w, r.Body, config.RequestMaxBytes)
	defer r.Body.Close()
	decoder := json.NewDecoder(r.Body)
	if err := decoder.Decode(dst); err != nil {
		var maxErr *http.MaxBytesError
		if errors.As(err, &maxErr) {
			jsonError(w, http.StatusRequestEntityTooLarge, "request_too_large", "Request body is too large.")
		} else {
			jsonError(w, http.StatusUnprocessableEntity, "invalid_json", "Malformed JSON body.")
		}
		return false
	}
	if err := decoder.Decode(&struct{}{}); err != io.EOF {
		jsonError(w, http.StatusUnprocessableEntity, "invalid_json", "Request body must contain one JSON object.")
		return false
	}
	return true
}

func jsonResponse(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(data)
}

func jsonError(w http.ResponseWriter, status int, errCode, msg string) {
	jsonResponse(w, status, map[string]string{
		"error":   errCode,
		"message": msg,
	})
}

func parseBearerToken(r *http.Request) string {
	auth := r.Header.Get("Authorization")
	parts := strings.SplitN(auth, " ", 2)
	if len(parts) == 2 && strings.EqualFold(parts[0], "Bearer") {
		return strings.TrimSpace(parts[1])
	}
	return ""
}

// Browsers cannot set headers on a WebSocket handshake, so the web player
// offers its token as a subprotocol instead: "bitchord.v1, bitchord.token.<hex>".
// It still stays out of the URL, and so out of access logs, which is the reason
// the header was chosen; the server answers with "bitchord.v1" alone and never
// echoes the token.
const (
	socketProtocol    = "bitchord.v1"
	socketTokenPrefix = "bitchord.token."
)

// parseSocketToken reads the party token from the Authorization header (the
// apps) or from an offered subprotocol (browsers).
func parseSocketToken(r *http.Request) string {
	if token := parseBearerToken(r); token != "" {
		return token
	}
	for _, protocol := range websocket.Subprotocols(r) {
		if strings.HasPrefix(protocol, socketTokenPrefix) {
			return strings.TrimPrefix(protocol, socketTokenPrefix)
		}
	}
	return ""
}

// REST Handlers

func handleRoot(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	jsonResponse(w, http.StatusOK, map[string]interface{}{
		"service":    "bitchord-listen-together",
		"members":    store.MemberCount(),
		"parties":    store.Len(),
		"serverMs":   clock.NowMs(),
	})
}

func handleHealthz(w http.ResponseWriter, r *http.Request) {
	jsonResponse(w, http.StatusOK, map[string]interface{}{
		"ok":       true,
		"serverMs": clock.NowMs(),
	})
}

// serverFeatures is what this build of the server understands beyond the
// original protocol's baseline. Clients read it from /api/info and turn on only
// what is listed, so a party on a server without an entry keeps working in the
// reduced form both builds share. Add a name here in the same change that ships
// the behaviour; never remove one while clients may rely on it.
var serverFeatures = []string{
	"preview",         // GET /api/parties/{code}/preview
	"hostOnlyControl", // setHostOnlyControl
	"autoplay",        // setAutoplay and autoplayEnabled on create
	"kick",            // kick, and bye reason "kicked"
	"maxMembers",      // setMaxMembers and maxMembers on create
	"queueDelta",      // queueAdd / queueRemove / queueClear / queueMove
	"activity",        // activity frames
	"inviteLanding",   // GET /invite/{code}
	"listeningMode",   // setListening, and playsAudio on members
	"people",             // accountProof on join: one person's devices are one seat; personId on members
	"addedBy",            // tracks carry who queued them
	"accountDevices",     // /api/account/connect + /ws/account: a person's devices see each other
	"devicePrefs",        // prefs frame: hidden / noControl / unreachable on a device
}

// handleInfo describes this server so a client can tell an extended build from
// an original one. The original server answers 404 here, which clients read as
// "baseline protocol only".
func handleInfo(w http.ResponseWriter, r *http.Request) {
	version := config.Version
	if version == "" {
		version = "dev"
	}
	jsonResponse(w, http.StatusOK, map[string]interface{}{
		"service":  "bitchord-listen-together",
		"flavor":   "bitchord-x",
		"protocol": 1,
		"version":  version,
		"features": serverFeatures,
		"limits": map[string]interface{}{
			"defaultMaxMembers": config.MaxMembers,
			"minMembers":        2,
			"maxMembers":        10,
			"maxUpcomingQueue":  config.MaxUpcomingQueue,
			"maxDevicesPerPerson": party.MaxDevicesPerPerson,
		},
		"serverMs": clock.NowMs(),
	})
}

// handleAssetLinks publishes the Digital Asset Links statement that lets
// Android verify this domain for the apps in JAM_ANDROID_APP_LINKS, so a tapped
// invite opens the app instead of the landing page.
func handleAssetLinks(w http.ResponseWriter, r *http.Request) {
	if len(config.AndroidAppLinks) == 0 {
		http.NotFound(w, r)
		return
	}
	byPackage := map[string][]string{}
	var order []string
	for _, link := range config.AndroidAppLinks {
		if _, seen := byPackage[link.Package]; !seen {
			order = append(order, link.Package)
		}
		byPackage[link.Package] = append(byPackage[link.Package], link.Fingerprint)
	}
	statements := make([]map[string]interface{}, 0, len(order))
	for _, pkg := range order {
		statements = append(statements, map[string]interface{}{
			"relation": []string{"delegate_permission/common.handle_all_urls"},
			"target": map[string]interface{}{
				"namespace":                "android_app",
				"package_name":             pkg,
				"sha256_cert_fingerprints": byPackage[pkg],
			},
		})
	}
	w.Header().Set("Cache-Control", "public, max-age=3600")
	jsonResponse(w, http.StatusOK, statements)
}

func handleTime(w http.ResponseWriter, r *http.Request) {
	jsonResponse(w, http.StatusOK, map[string]interface{}{
		"serverMs": clock.NowMs(),
	})
}

// handlePresence takes the open apps' heartbeat:
// {"id": "<install uuid>", "platform": "android"|"pc"|"browser", "open": true|false, "version": "1.9.3"}.
func handlePresence(w http.ResponseWriter, r *http.Request) {
	if !presenceLimiter.Allow(clientIP(r)) {
		jsonError(w, http.StatusTooManyRequests, "rate_limited", "Too many presence pings from this address.")
		return
	}
	var body struct {
		ID       string `json:"id"`
		Platform string `json:"platform"`
		Open     *bool  `json:"open"`
		Version  string `json:"version"`
		Level    string `json:"level"`
	}
	if !decodeJSONBody(w, r, &body) {
		return
	}
	id, ok := presence.ParseID(body.ID)
	if !ok {
		jsonError(w, http.StatusUnprocessableEntity, "invalid_id", "id must be a UUID.")
		return
	}
	platform, ok := presence.ParsePlatform(body.Platform)
	if !ok {
		jsonError(w, http.StatusUnprocessableEntity, "invalid_platform", "platform must be android, pc or browser.")
		return
	}
	if body.Open == nil {
		jsonError(w, http.StatusUnprocessableEntity, "invalid_open", "open must be true or false.")
		return
	}
	// The native apps send no Origin; only the web player does, and only from a
	// named origin. Refusing every other browser stops any web page from making
	// its visitors post made-up installs from their addresses.
	origin := r.Header.Get("Origin")
	if (platform == presence.Browser) != (origin != "") || (origin != "" && !config.IsAllowedOrigin(origin)) {
		jsonError(w, http.StatusForbidden, "origin_not_allowed", "Presence is only accepted from the apps and the web player.")
		return
	}
	if *body.Open {
		presenceTracker.Ping(id, platform, clock.NowMs())
	} else {
		presenceTracker.Close(id)
	}
	if telemetryStore != nil {
		if telemetry.ParseLevel(body.Level) == telemetry.Minimal {
			// A headcount by day, version and platform. The ID is the client's
			// per-day one, used to count it once and never stored.
			if *body.Open {
				telemetryStore.RecordMinimalPresence(body.ID, body.Platform, body.Version)
			}
		} else {
			telemetryStore.RecordPresence(body.ID, body.Platform, body.Version, *body.Open)
		}
	}
	telemetryHub.Notify("live", liveTelemetryStats())
	jsonResponse(w, http.StatusOK, map[string]int{"intervalSec": config.PresenceIntervalSec})
}

// handlePresenceWS keeps a lightweight WebSocket per open app so closing or
// force-killing the app drops the live count immediately via TCP close.
func handlePresenceWS(w http.ResponseWriter, r *http.Request) {
	if !presenceLimiter.Allow(clientIP(r)) {
		jsonError(w, http.StatusTooManyRequests, "rate_limited", "Too many presence connections from this address.")
		return
	}
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer conn.Close()

	_ = conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	conn.SetReadLimit(4096)
	_, raw, err := conn.ReadMessage()
	if err != nil {
		return
	}
	var hello struct {
		ID       string `json:"id"`
		Platform string `json:"platform"`
		Version  string `json:"version"`
		Level    string `json:"level"`
	}
	if err := json.Unmarshal(raw, &hello); err != nil {
		return
	}
	id, ok := presence.ParseID(hello.ID)
	if !ok {
		return
	}
	platform, ok := presence.ParsePlatform(hello.Platform)
	if !ok {
		return
	}
	origin := r.Header.Get("Origin")
	if (platform == presence.Browser) != (origin != "") || (origin != "" && !config.IsAllowedOrigin(origin)) {
		return
	}

	minimal := telemetry.ParseLevel(hello.Level) == telemetry.Minimal
	tok := presenceTracker.Pin(id, platform, clock.NowMs())
	if telemetryStore != nil {
		if minimal {
			telemetryStore.RecordMinimalPresence(hello.ID, hello.Platform, hello.Version)
		} else {
			telemetryStore.RecordPresence(hello.ID, hello.Platform, hello.Version, true)
		}
	}
	telemetryHub.Notify("live", liveTelemetryStats())

	defer func() {
		if presenceTracker.CloseToken(id, tok, clock.NowMs()) {
			if telemetryStore != nil && !minimal {
				telemetryStore.RecordPresence(hello.ID, hello.Platform, hello.Version, false)
			}
			telemetryHub.Notify("live", liveTelemetryStats())
		}
	}()

	done := make(chan struct{})
	go func() {
		defer close(done)
		ticker := time.NewTicker(15 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-done:
				return
			case <-ticker.C:
				_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
				if err := conn.WriteMessage(websocket.PingMessage, nil); err != nil {
					_ = conn.Close()
					return
				}
				presenceTracker.TouchToken(id, tok, clock.NowMs())
			}
		}
	}()

	_ = conn.SetReadDeadline(time.Now().Add(35 * time.Second))
	conn.SetPongHandler(func(string) error {
		presenceTracker.TouchToken(id, tok, clock.NowMs())
		return conn.SetReadDeadline(time.Now().Add(35 * time.Second))
	})
	for {
		if _, _, err := conn.ReadMessage(); err != nil {
			break
		}
		_ = conn.SetReadDeadline(time.Now().Add(35 * time.Second))
	}
}

func stripNullBytes(s string) string {
	return strings.ReplaceAll(s, "\x00", "")
}

func isValidAppVersion(v string) bool {
	if len(v) > 32 {
		return false
	}
	for i := 0; i < len(v); i++ {
		c := v[i]
		if !((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_' || c == '+') {
			return false
		}
	}
	return true
}

// handleTelemetryError accepts opt-in error reports with basic device info.
func handleTelemetryError(w http.ResponseWriter, r *http.Request) {
	ip := clientIP(r)
	if !telemetryLimiter.Allow(ip) {
		jsonError(w, http.StatusTooManyRequests, "rate_limited", "Too many error reports from this address.")
		return
	}
	var body struct {
		ID           string `json:"id"`
		Platform     string `json:"platform"`
		AppVersion   string `json:"appVersion"`
		ErrorType    string `json:"errorType"`
		ErrorMessage string `json:"errorMessage"`
		Stacktrace   string `json:"stacktrace"`
		DeviceModel  string `json:"deviceModel"`
		OSVersion    string `json:"osVersion"`
		ABI          string `json:"abi"`
		Level        string `json:"level"`
	}
	if !decodeJSONBody(w, r, &body) {
		return
	}
	platform, ok := presence.ParsePlatform(body.Platform)
	if !ok {
		jsonError(w, http.StatusUnprocessableEntity, "invalid_platform", "platform must be android, pc or browser.")
		return
	}
	origin := r.Header.Get("Origin")
	if (platform == presence.Browser) != (origin != "") || (origin != "" && !config.IsAllowedOrigin(origin)) {
		jsonError(w, http.StatusForbidden, "origin_not_allowed", "Telemetry is only accepted from the apps and the web player.")
		return
	}
	body.AppVersion = strings.TrimSpace(stripNullBytes(body.AppVersion))
	if !isValidAppVersion(body.AppVersion) {
		jsonError(w, http.StatusUnprocessableEntity, "invalid_version", "appVersion contains invalid characters.")
		return
	}
	body.ErrorType = strings.TrimSpace(stripNullBytes(body.ErrorType))
	body.ErrorMessage = strings.TrimSpace(stripNullBytes(body.ErrorMessage))
	body.Stacktrace = stripNullBytes(body.Stacktrace)
	body.DeviceModel = stripNullBytes(body.DeviceModel)
	body.OSVersion = stripNullBytes(body.OSVersion)
	body.ABI = stripNullBytes(body.ABI)

	// A minimal report is counted by its type and nothing else is read from it:
	// whatever else the body carries (an ID, a message, a stack trace, a device)
	// is ignored, so the promise does not depend on the client keeping it.
	if telemetry.ParseLevel(body.Level) == telemetry.Minimal {
		if body.ErrorType == "" {
			jsonError(w, http.StatusUnprocessableEntity, "invalid_error", "errorType is required.")
			return
		}
		if telemetryStore != nil {
			if err := telemetryStore.RecordMinimalError(r.Context(), body.Platform, body.AppVersion, body.ErrorType); err != nil {
				jsonError(w, http.StatusInternalServerError, "store_error", "Failed to store error report.")
				return
			}
		}
		jsonResponse(w, http.StatusAccepted, map[string]bool{"ok": true})
		return
	}
	if body.ID != "" {
		if _, ok := presence.ParseID(body.ID); !ok {
			jsonError(w, http.StatusUnprocessableEntity, "invalid_id", "id must be a UUID.")
			return
		}
	}
	if body.ErrorType == "" || body.ErrorMessage == "" {
		jsonError(w, http.StatusUnprocessableEntity, "invalid_error", "errorType and errorMessage are required.")
		return
	}
	dedupKey := ip + "|" + body.ErrorType + "|" + body.AppVersion
	if !errorDedupCache.Allow(dedupKey) {
		jsonResponse(w, http.StatusAccepted, map[string]bool{"ok": true})
		return
	}
	if telemetryStore != nil {
		id, err := telemetryStore.RecordError(r.Context(), telemetry.ErrorInput{
			InstallID:    body.ID,
			Platform:     body.Platform,
			AppVersion:   body.AppVersion,
			ErrorType:    body.ErrorType,
			ErrorMessage: body.ErrorMessage,
			Stacktrace:   body.Stacktrace,
			DeviceModel:  body.DeviceModel,
			OSVersion:    body.OSVersion,
			ABI:          body.ABI,
		})
		if err != nil {
			jsonError(w, http.StatusInternalServerError, "store_error", "Failed to store error report.")
			return
		}
		telemetryHub.Notify("error", map[string]interface{}{
			"id":         id,
			"errorType":  body.ErrorType,
			"appVersion": body.AppVersion,
			"platform":   body.Platform,
		})
	}
	jsonResponse(w, http.StatusAccepted, map[string]bool{"ok": true})
}

// handleLiveStats is public on purpose: anyone, including a README badge or
// another site's script, may read how many apps are open.
func handleLiveStats(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Cache-Control", "public, max-age=15")
	jsonResponse(w, http.StatusOK, presenceTracker.Counts())
}

// handleLiveBadge draws the README's "listening now" badge itself. Going
// through shields.io meant its 2-5 minute cache stacked on GitHub's camo
// proxy, so the README trailed the real count by minutes. no-cache here is
// what camo honours, so every README view gets the current number.
//
// The geometry copies shields' for-the-badge render byte for byte: a fixed
// 124.25px label, bold Verdana digits at 8.25px each, 12px padding a side.
func handleLiveBadge(w http.ResponseWriter, r *http.Request) {
	value := fmt.Sprint(presenceTracker.Counts().Online)
	valueWidth := 8.25*float64(len(value)) + 24
	width := 124.25 + valueWidth
	svg := fmt.Sprintf(`<svg xmlns="http://www.w3.org/2000/svg" width="%g" height="28" role="img" aria-label="LISTENING NOW: %s"><title>LISTENING NOW: %s</title><g shape-rendering="crispEdges"><rect width="124.25" height="28" fill="#0d1117"/><rect x="124.25" width="%g" height="28" fill="#fb4f67"/></g><g fill="#fff" text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" text-rendering="geometricPrecision" font-size="100"><text transform="scale(.1)" x="621.25" y="175" textLength="1002.5">LISTENING NOW</text><text transform="scale(.1)" x="%g" y="175" textLength="%g" font-weight="bold">%s</text></g></svg>`,
		width, value, value, valueWidth, (124.25+valueWidth/2)*10, 82.5*float64(len(value)), value)
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Content-Type", "image/svg+xml;charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate, max-age=0")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(svg))
}

func handleCreateParty(w http.ResponseWriter, r *http.Request) {
	if !createLimiter.Allow(clientIP(r)) {
		jsonError(w, http.StatusTooManyRequests, "create_rate_limited", "You can create up to two parties per minute. Please try again shortly.")
		return
	}
	var req protocol.JoinRequest
	if !decodeJSONBody(w, r, &req) {
		return
	}
	if err := req.Validate(); err != nil {
		jsonError(w, http.StatusUnprocessableEntity, "validation_error", err.Error())
		return
	}

	maxMembers := 5
	if req.MaxMembers != nil { maxMembers = *req.MaxMembers }
	p, err := store.CreateWithLimits(config.MaxParties, maxMembers)
	if err != nil {
		if pe, ok := err.(*party.PartyError); ok {
			jsonError(w, pe.Status, pe.Code, pe.Message)
			return
		}
		jsonError(w, http.StatusInternalServerError, "server_error", "Failed to create party.")
		return
	}
	// This must be present in the creation response. Waiting for the first
	// WebSocket control can lose the setting before that socket is established.
	if req.AutoplayEnabled != nil {
		p.Playback.AutoplayEnabled = *req.AutoplayEnabled
	}

	p.Lock()
	m, err := p.JoinAs(req.UserId, req.DeviceId, req.DisplayName, req.AvatarUrl, req.AccountProof, req.DeviceName)
	if err != nil {
		p.Unlock()
		store.Drop(p.Code)
		if pe, ok := err.(*party.PartyError); ok {
			jsonError(w, pe.Status, pe.Code, pe.Message)
			return
		}
		jsonError(w, http.StatusInternalServerError, "server_error", err.Error())
		return
	}
	partyWire := p.ToWire()
	youWire := m.ToWire()
	token := m.Token
	code := p.Code
	p.Unlock()

	jsonResponse(w, http.StatusCreated, map[string]interface{}{
		"code":  code,
		"token": token,
		"you":   youWire,
		"party": partyWire,
	})
}

func handleJoinParty(w http.ResponseWriter, r *http.Request) {
	// A join is how a code is guessed: six characters out of thirty-three are far, but not
	// unreachable for something that tries all day from one address.
	if !joinLimiter.Allow(clientIP(r)) {
		jsonError(w, http.StatusTooManyRequests, "join_rate_limited", "Too many attempts. Please try again shortly.")
		return
	}
	code := r.PathValue("code")
	var req protocol.JoinRequest
	if !decodeJSONBody(w, r, &req) {
		return
	}
	if err := req.Validate(); err != nil {
		jsonError(w, http.StatusUnprocessableEntity, "validation_error", err.Error())
		return
	}

	p, err := store.Get(code)
	if err != nil {
		if pe, ok := err.(*party.PartyError); ok {
			jsonError(w, pe.Status, pe.Code, pe.Message)
			return
		}
		if !failedLookupLimiter.Allow(clientIP(r)) {
			jsonError(w, http.StatusTooManyRequests, "join_rate_limited", "Too many attempts. Please try again shortly.")
			return
		}
		jsonError(w, http.StatusNotFound, "no_such_party", "No party with that code.")
		return
	}
	p.Lock()
	// A device that comes back signed in as somebody else leaves its old seat (see JoinAs). Its
	// old socket must not stay behind as a member that no longer exists.
	var before []string
	for id, existing := range p.Members {
		if existing.DeviceId == req.DeviceId {
			before = append(before, id)
		}
	}
	m, err := p.JoinAs(req.UserId, req.DeviceId, req.DisplayName, req.AvatarUrl, req.AccountProof, req.DeviceName)
	var replaced []string
	for _, id := range before {
		if _, still := p.Members[id]; !still {
			replaced = append(replaced, id)
		}
	}
	var replacedMembers map[string]interface{}
	if len(replaced) > 0 {
		replacedMembers = membersFrame(p)
	}
	if err != nil {
		p.Unlock()
		dropReplacedSockets(p.Code, replaced, replacedMembers)
		if pe, ok := err.(*party.PartyError); ok {
			jsonError(w, pe.Status, pe.Code, pe.Message)
			return
		}
		jsonError(w, http.StatusInternalServerError, "server_error", err.Error())
		return
	}
	partyWire := p.ToWire()
	youWire := m.ToWire()
	token := m.Token
	pCode := p.Code
	if len(replaced) > 0 {
		replacedMembers = membersFrame(p)
	}
	p.Unlock()
	dropReplacedSockets(pCode, replaced, replacedMembers)

	jsonResponse(w, http.StatusOK, map[string]interface{}{
		"code":  pCode,
		"token": token,
		"you":   youWire,
		"party": partyWire,
	})
}

// handlePreviewParty answers who is in a party, without a token and without
// joining it.
//
// Deliberately unauthenticated: the whole point is to let somebody who has been
// handed a code see who they would be joining before they commit a device slot
// to it. What it discloses — display names, avatars, how full the party is — is
// exactly what joining would disclose a second later, and anyone holding a code
// can join. What it does not disclose is what the party is playing, its queue,
// member or user ids, or anything that would let a caller act on the party.
//
// It creates nothing and allocates a short slice, but it answers whether a code exists, which is
// all a guesser needs, so it is limited per address like joining is.
func handlePreviewParty(w http.ResponseWriter, r *http.Request) {
	if !previewLimiter.Allow(clientIP(r)) {
		jsonError(w, http.StatusTooManyRequests, "preview_rate_limited", "Too many attempts. Please try again shortly.")
		return
	}
	code := r.PathValue("code")
	p, err := store.Get(code)
	if err != nil {
		if pe, ok := err.(*party.PartyError); ok {
			jsonError(w, pe.Status, pe.Code, pe.Message)
			return
		}
		if !failedLookupLimiter.Allow(clientIP(r)) {
			jsonError(w, http.StatusTooManyRequests, "join_rate_limited", "Too many attempts. Please try again shortly.")
			return
		}
		jsonError(w, http.StatusNotFound, "no_such_party", "No party with that code.")
		return
	}
	p.Lock()
	// Joined order, as the snapshot uses: the caller draws the first few faces
	// and counts the rest, so which faces those are must not change between two
	// reads of an unchanged party.
	ordered := make([]*party.Member, 0, len(p.Members))
	for _, m := range p.Members {
		ordered = append(ordered, m)
	}
	sort.Slice(ordered, func(i, j int) bool {
		return ordered[i].JoinedAtMs < ordered[j].JoinedAtMs
	})
	members := make([]map[string]interface{}, 0, len(ordered))
	hostName := ""
	seen := make(map[string]bool, len(ordered))
	for _, m := range ordered {
		// A person on two devices is one face and one seat, as the size limit counts them.
		if seen[m.PersonId] {
			continue
		}
		seen[m.PersonId] = true
		members = append(members, map[string]interface{}{
			"displayName": m.DisplayName,
			"avatarUrl":   m.AvatarUrl,
			"isHost":      m.IsHost,
		})
		if m.IsHost {
			hostName = m.DisplayName
		}
	}
	preview := map[string]interface{}{
		"code":        p.Code,
		"hostName":    hostName,
		"memberCount": p.PersonCount(),
		"maxMembers":  p.MaxMembers,
		"isFull":      p.PersonCount() >= p.MaxMembers,
		"members":     members,
	}
	p.Unlock()

	jsonResponse(w, http.StatusOK, preview)
}

func handleGetParty(w http.ResponseWriter, r *http.Request) {
	code := r.PathValue("code")
	token := parseBearerToken(r)
	if token == "" {
		jsonError(w, http.StatusUnauthorized, "unauthorized", "Missing bearer token.")
		return
	}

	p, err := store.Get(code)
	if err != nil {
		if pe, ok := err.(*party.PartyError); ok {
			jsonError(w, pe.Status, pe.Code, pe.Message)
			return
		}
		if !failedLookupLimiter.Allow(clientIP(r)) {
			jsonError(w, http.StatusTooManyRequests, "join_rate_limited", "Too many attempts. Please try again shortly.")
			return
		}
		jsonError(w, http.StatusNotFound, "no_such_party", "No party with that code.")
		return
	}

	p.Lock()
	_, err = p.Authenticate(token)
	if err != nil {
		p.Unlock()
		if pe, ok := err.(*party.PartyError); ok {
			jsonError(w, pe.Status, pe.Code, pe.Message)
			return
		}
		jsonError(w, http.StatusUnauthorized, "unauthorized", "Bad token.")
		return
	}
	partyWire := p.ToWire()
	p.Unlock()

	jsonResponse(w, http.StatusOK, partyWire)
}

func handleLeaveParty(w http.ResponseWriter, r *http.Request) {
	code := r.PathValue("code")
	token := parseBearerToken(r)
	if token == "" {
		jsonError(w, http.StatusUnauthorized, "unauthorized", "Missing bearer token.")
		return
	}

	p, err := store.Get(code)
	if err != nil {
		if pe, ok := err.(*party.PartyError); ok {
			jsonError(w, pe.Status, pe.Code, pe.Message)
			return
		}
		if !failedLookupLimiter.Allow(clientIP(r)) {
			jsonError(w, http.StatusTooManyRequests, "join_rate_limited", "Too many attempts. Please try again shortly.")
			return
		}
		jsonError(w, http.StatusNotFound, "no_such_party", "No party with that code.")
		return
	}

	p.Lock()
	member, err := p.Authenticate(token)
	if err != nil {
		p.Unlock()
		if pe, ok := err.(*party.PartyError); ok {
			jsonError(w, pe.Status, pe.Code, pe.Message)
			return
		}
		jsonError(w, http.StatusUnauthorized, "unauthorized", "Bad token.")
		return
	}

	memberId := member.MemberId
	p.Remove(memberId)
	membersFrame := membersFrame(p)
	p.Unlock()

	// Notify room and drop leaving member socket
	hubInst.Send(p.Code, memberId, map[string]interface{}{
		"type":   protocol.FrameBye,
		"reason": "left",
	})
	hubInst.Broadcast(p.Code, membersFrame, memberId)

	jsonResponse(w, http.StatusOK, map[string]bool{"ok": true})
}

// Web Invite Landing Handler

type invitePageData struct {
	Code              string
	DeepLink          string
	IntentURI         template.URL
	SafeDeepLink      template.URL
	ServerOrigin      string
	CurrentSongTitle  string
	CurrentSongArtist string
	MemberCount       int
	IsActive          bool
}

var inviteTemplate = template.Must(template.New("invite").Parse(`<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>BitChord Listen Together - Party {{.Code}}</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            background-color: #0b0b0e;
            color: #f3f3f7;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 24px;
        }
        .container {
            background: rgba(22, 22, 30, 0.85);
            backdrop-filter: blur(24px);
            -webkit-backdrop-filter: blur(24px);
            border: 1px solid rgba(255, 255, 255, 0.08);
            border-radius: 28px;
            max-width: 440px;
            width: 100%;
            padding: 36px 28px;
            text-align: center;
            box-shadow: 0 20px 50px rgba(0, 0, 0, 0.5), 0 0 40px rgba(124, 77, 255, 0.1);
        }
        .badge {
            display: inline-flex;
            align-items: center;
            gap: 6px;
            background: rgba(124, 77, 255, 0.15);
            color: #b388ff;
            font-size: 13px;
            font-weight: 600;
            padding: 6px 14px;
            border-radius: 9999px;
            margin-bottom: 20px;
            letter-spacing: 0.5px;
        }
        .badge-dot {
            width: 8px;
            height: 8px;
            background: #00e676;
            border-radius: 50%;
            box-shadow: 0 0 8px #00e676;
        }
        .badge-dot.offline {
            background: #ff5252;
            box-shadow: 0 0 8px #ff5252;
        }
        h1 {
            font-size: 24px;
            font-weight: 700;
            margin-bottom: 8px;
            letter-spacing: -0.5px;
        }
        .subtitle {
            color: #9e9ea7;
            font-size: 15px;
            line-height: 1.5;
            margin-bottom: 28px;
        }
        .code-box {
            background: rgba(255, 255, 255, 0.04);
            border: 1px solid rgba(255, 255, 255, 0.1);
            border-radius: 18px;
            padding: 16px 20px;
            margin-bottom: 28px;
        }
        .code-label {
            font-size: 12px;
            color: #71717a;
            text-transform: uppercase;
            letter-spacing: 1.5px;
            margin-bottom: 6px;
        }
        .code-val {
            font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
            font-size: 32px;
            font-weight: 800;
            letter-spacing: 6px;
            color: #ffffff;
        }
        .now-playing {
            font-size: 14px;
            color: #d1d1d6;
            margin-top: 10px;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }
        .btn {
            display: block;
            width: 100%;
            padding: 16px 24px;
            background: linear-gradient(135deg, #7c4dff 0%, #3d5afe 100%);
            color: #ffffff;
            font-size: 16px;
            font-weight: 600;
            text-decoration: none;
            border-radius: 16px;
            border: none;
            cursor: pointer;
            transition: transform 0.15s ease, opacity 0.15s ease;
            box-shadow: 0 8px 24px rgba(124, 77, 255, 0.35);
        }
        .btn:hover {
            transform: translateY(-1px);
            opacity: 0.95;
        }
        .btn:active {
            transform: scale(0.98);
        }
        .footer-note {
            margin-top: 24px;
            font-size: 13px;
            color: #71717a;
            line-height: 1.5;
        }
        .footer-note a {
            color: #b388ff;
            text-decoration: none;
        }
        .footer-note a:hover {
            text-decoration: underline;
        }
    </style>
</head>
<body>
    <div class="container">
        {{if .IsActive}}
            <div class="badge">
                <span class="badge-dot"></span>
                <span>Listen Together • {{.MemberCount}} in party</span>
            </div>
            <h1>Join the Music Party</h1>
            <p class="subtitle">Opening BitChord to sync playback in real time.</p>
            <div class="code-box">
                <div class="code-label">Party Code</div>
                <div class="code-val">{{.Code}}</div>
                {{if .CurrentSongTitle}}
                    <div class="now-playing">🎵 {{.CurrentSongTitle}} - {{.CurrentSongArtist}}</div>
                {{end}}
            </div>
            <a id="joinBtn" href="{{.IntentURI}}" class="btn">Join Party in BitChord</a>
            <p class="footer-note">
                Didn’t open automatically? Tap the button above.<br>
                Don't have BitChord yet? <a href="https://github.com/AnGorRoc/BitChord-X/releases" target="_blank" rel="noopener">Download it here</a>.
            </p>
            <script>
                var intentUri = {{.IntentURI}};
                var deepLink = {{.SafeDeepLink}};
                function launch() {
                    if (/Android/i.test(navigator.userAgent)) {
                        window.location.href = intentUri;
                    } else {
                        window.location.href = deepLink;
                    }
                }
                setTimeout(launch, 100);
            </script>
        {{else}}
            <div class="badge">
                <span class="badge-dot offline"></span>
                <span>Party Inactive</span>
            </div>
            <h1>Party Not Found</h1>
            <p class="subtitle">This party code has expired or does not exist on this server.</p>
            <div class="code-box">
                <div class="code-label">Party Code</div>
                <div class="code-val" style="color: #a1a1aa;">{{.Code}}</div>
            </div>
            <p class="footer-note">
                Please ask the host for a new invite link or check your server configuration.
            </p>
        {{end}}
    </div>
</body>
</html>`))

func requestOrigin(r *http.Request) string {
	if config.PublicOrigin != "" {
		return config.PublicOrigin
	}
	proto := "http"
	if r.TLS != nil {
		proto = "https"
	}
	if config.TrustProxy && config.IsTrustedProxy(r.RemoteAddr) {
		if forwardedProto := strings.TrimSpace(strings.Split(r.Header.Get("X-Forwarded-Proto"), ",")[0]); forwardedProto == "http" || forwardedProto == "https" {
			proto = forwardedProto
		}
	}
	host := r.Host
	if host == "" {
		host = "localhost"
	}
	return fmt.Sprintf("%s://%s", proto, host)
}

func handleInviteLanding(w http.ResponseWriter, r *http.Request) {
	code := codes.Normalise(r.PathValue("code"))
	origin := requestOrigin(r)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")

	if len(code) != codes.CodeLength {
		w.WriteHeader(http.StatusBadRequest)
		_ = inviteTemplate.Execute(w, invitePageData{
			Code:     html.EscapeString(r.PathValue("code")),
			IsActive: false,
		})
		return
	}

	p := store.Find(code)
	if p == nil {
		w.WriteHeader(http.StatusNotFound)
		_ = inviteTemplate.Execute(w, invitePageData{
			Code:     code,
			IsActive: false,
		})
		return
	}

	deepLink := fmt.Sprintf("bitchord://party/%s?server=%s", url.PathEscape(code), url.QueryEscape(origin))
	intentURI := fmt.Sprintf("intent://party/%s?server=%s#Intent;scheme=bitchord;end", url.PathEscape(code), url.QueryEscape(origin))

	currentSongTitle := ""
	currentSongArtist := ""
	p.Lock()
	if p.Playback != nil && p.Playback.Track != nil {
		currentSongTitle = p.Playback.Track.Title
		currentSongArtist = p.Playback.Track.Artist
	}
	memberCount := p.PersonCount()
	p.Unlock()

	w.WriteHeader(http.StatusOK)
	_ = inviteTemplate.Execute(w, invitePageData{
		Code:              code,
		DeepLink:          deepLink,
		IntentURI:         template.URL(intentURI),
		SafeDeepLink:      template.URL(deepLink),
		ServerOrigin:      origin,
		CurrentSongTitle:  currentSongTitle,
		CurrentSongArtist: currentSongArtist,
		MemberCount:       memberCount,
		IsActive:          true,
	})
}

// WebSocket Handler

func handleWebSocket(w http.ResponseWriter, r *http.Request) {
	code := r.PathValue("code")
	token := parseSocketToken(r)
	if token == "" {
		http.Error(w, "missing token", http.StatusUnauthorized)
		return
	}

	p, err := store.Get(code)
	if err != nil {
		http.Error(w, "no such party", http.StatusNotFound)
		return
	}

	p.Lock()
	member, err := p.Authenticate(token)
	if err != nil {
		p.Unlock()
		http.Error(w, "bad token", http.StatusUnauthorized)
		return
	}
	p.Unlock()

	if hubInst.Count() >= config.MaxSockets {
		http.Error(w, "server is full", http.StatusServiceUnavailable)
		return
	}
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("WebSocket upgrade failed: %v", err)
		return
	}
	conn.SetReadLimit(config.WebSocketMaxBytes)
	_ = conn.SetReadDeadline(time.Now().Add(time.Duration(config.ConnectionIdleMs) * time.Millisecond))

	p.Lock()
	p.MarkConnected(member, true)
	p.Unlock()

	sc := hubInst.Attach(p.Code, member.MemberId, conn)

	// Send welcome frame
	p.Lock()
	welcome := map[string]interface{}{
		"type":     protocol.FrameWelcome,
		"you":      member.ToWire(),
		"party":    p.ToWire(),
		"serverMs": clock.NowMs(),
	}
	membersF := membersFrame(p)
	p.Unlock()

	_ = sc.WriteJSON(welcome)
	hubInst.Broadcast(p.Code, membersF, "")

	// Read loop
	defer func() {
		hubInst.Detach(p.Code, member.MemberId, sc)
		_ = sc.Close()

		p.Lock()
		p.MarkConnected(member, false)
		mFrame := membersFrame(p)
		p.Unlock()

		hubInst.Broadcast(p.Code, mFrame, "")
	}()

	for {
		var raw map[string]interface{}
		if err := conn.ReadJSON(&raw); err != nil {
			break
		}
		_ = conn.SetReadDeadline(time.Now().Add(time.Duration(config.ConnectionIdleMs) * time.Millisecond))
		handleSocketFrame(p, member, sc, raw)
	}
}

func handleSocketFrame(p *party.Party, member *party.Member, sc *hub.SafeConn, frame map[string]interface{}) {
	kind, _ := frame["type"].(string)
	if kind == "" {
		return
	}

	p.Lock()
	defer p.Unlock()
	if !p.SpendFrameBudget(member) {
		_ = sc.WriteJSON(map[string]interface{}{
			"type": protocol.FrameError, "error": "rate_limited", "message": "Too many messages at once.",
		})
		return
	}

	member.LastSeenMs = clock.NowMs()

	switch kind {
	case protocol.FramePing:
		var clientMs interface{}
		if v, ok := frame["clientMs"].(float64); ok && !math.IsNaN(v) && !math.IsInf(v, 0) {
			clientMs = int64(v)
		}
		_ = sc.WriteJSON(map[string]interface{}{
			"type":     protocol.FramePong,
			"clientMs": clientMs,
			"serverMs": clock.NowMs(),
		})

	case protocol.FrameSync:
		_ = sc.WriteJSON(stateFrame(p))

	case protocol.FrameSyncQueue:
		_ = sc.WriteJSON(queueFrame(p))

	case protocol.FrameReport:
		// Playhead position report from client for monitoring
		if posNum, ok := frame["positionMs"].(float64); ok && p.Playback.IsPlaying {
			drift := int64(posNum) - p.Playback.PositionAt(clock.NowMs())
			if drift > 1500 || drift < -1500 {
				log.Printf("party %s: %s drifted %dms", p.Code, member.DisplayName, drift)
			}
		}

	case protocol.FrameControl:
		if !p.SpendControlBudget(member) {
			_ = sc.WriteJSON(map[string]interface{}{
				"type":    protocol.FrameError,
				"error":   "rate_limited",
				"message": "Too many controls at once.",
			})
			return
		}

		queueBefore := p.Playback.QueueSeq
		action, _ := frame["action"].(string)
		success, errCode, errMsg := applyControl(p, member, action, frame)
		if success {
			p.Touch()
			// If queue changed, broadcast queue first
			if p.Playback.QueueSeq != queueBefore {
				hubInst.Broadcast(p.Code, queueFrame(p), "")
			}
			hubInst.Broadcast(p.Code, stateFrame(p), "")
			if action == protocol.ActionKick ||
				action == protocol.ActionSetListening ||
				action == protocol.ActionSetMaxMembers ||
				action == protocol.ActionSetHostOnlyControl {
				hubInst.Broadcast(p.Code, membersFrame(p), "")
			}
			hubInst.Broadcast(p.Code, activityFrame(member, action, frame), "")
		} else if errCode != "" {
			_ = sc.WriteJSON(map[string]interface{}{
				"type":    protocol.FrameError,
				"error":   errCode,
				"message": errMsg,
			})
		}
	}
}

func applyControl(p *party.Party, member *party.Member, action string, frame map[string]interface{}) (bool, string, string) {
	// Enforced here rather than left to the clients. An app that hides its own
	// next button is a courtesy; this is what actually stops a listener's
	// device — a stale build, a backgrounded one still echoing an old intent,
	// or something else entirely — from moving the music for everybody.
	if protocol.ControlActions[action] && !p.MayControl(member) {
		return false, "host_only", "Only the host can control the music in this party."
	}

	const maxPositionMs int64 = 24 * 60 * 60 * 1000
	var posPtr *int64
	if pos, ok := frame["positionMs"].(float64); ok && !math.IsNaN(pos) && !math.IsInf(pos, 0) {
		pVal := int64(pos)
		if pVal < 0 {
			pVal = 0
		} else if pVal > maxPositionMs {
			pVal = maxPositionMs
		}
		posPtr = &pVal
	}

	switch action {
	case protocol.ActionPlay:
		p.Playback.Play(&member.MemberId, posPtr)
		return true, "", ""

	case protocol.ActionPause:
		p.Playback.Pause(&member.MemberId, posPtr)
		return true, "", ""

	case protocol.ActionSeek:
		if posPtr == nil {
			return false, "missing_position", "seek requires positionMs"
		}
		p.Playback.Seek(&member.MemberId, *posPtr)
		return true, "", ""

	case protocol.ActionSetTrack:
		trackMap, _ := frame["track"].(map[string]interface{})
		t := party.TrackFromWire(trackMap)
		if t == nil {
			return false, "invalid_track", "Track must include videoId"
		}
		pos := int64(0)
		if posPtr != nil {
			pos = *posPtr
		}
		isPlaying := true
		if pVal, ok := frame["isPlaying"].(bool); ok {
			isPlaying = pVal
		}
		var qIndexPtr *int
		if qIndex, ok := frame["queueIndex"].(float64); ok {
			qi := int(qIndex)
			qIndexPtr = &qi
		}
		p.StampQueue([]*party.Track{t}, member)
		p.Playback.SetTrack(&member.MemberId, t, pos, isPlaying, qIndexPtr, &member.DisplayName)
		return true, "", ""

	case protocol.ActionSetQueue:
		itemsRaw, _ := frame["queue"].([]interface{})
		var tracks []*party.Track
		for _, item := range itemsRaw {
			if len(tracks) >= config.MaxQueueLength {
				break
			}
			if m, ok := item.(map[string]interface{}); ok {
				if tr := party.TrackFromWire(m); tr != nil {
					tracks = append(tracks, tr)
				}
			}
		}
		qIdx := -1
		if idxNum, ok := frame["queueIndex"].(float64); ok {
			qIdx = int(idxNum)
		}
		p.StampQueue(tracks, member)
		p.Playback.SetQueue(&member.MemberId, tracks, qIdx)
		return true, "", ""

	case protocol.ActionQueueAdd:
		var tracksToAdd []*party.Track
		if singleMap, ok := frame["track"].(map[string]interface{}); ok {
			if tr := party.TrackFromWire(singleMap); tr != nil {
				tracksToAdd = append(tracksToAdd, tr)
			}
		} else if itemsRaw, ok := frame["tracks"].([]interface{}); ok {
			for _, item := range itemsRaw {
				if m, ok := item.(map[string]interface{}); ok {
					if tr := party.TrackFromWire(m); tr != nil {
						tracksToAdd = append(tracksToAdd, tr)
					}
				}
			}
		}
		playNext, _ := frame["playNext"].(bool)
		p.StampAdded(tracksToAdd, member)
		ok, reason := p.Playback.AddUpcoming(&member.MemberId, tracksToAdd, playNext)
		if !ok {
			if reason == "queue_full" {
				return false, "queue_full", fmt.Sprintf("Queue is full (maximum %d upcoming songs).", config.MaxUpcomingQueue)
			}
			return false, reason, "Could not add track to queue."
		}
		return true, "", ""

	case protocol.ActionQueueRemove:
		vid, _ := frame["videoId"].(string)
		if vid == "" {
			if idxNum, ok := frame["index"].(float64); ok {
				idx := int(idxNum)
				if idx >= 0 && idx < len(p.Playback.Queue) {
					vid = p.Playback.Queue[idx].VideoId
				}
			}
		}
		if vid == "" {
			return false, "missing_track", "queueRemove requires videoId or index"
		}
		if !p.Playback.RemoveUpcoming(&member.MemberId, vid) {
			return false, "not_found", "Track is not in the upcoming queue"
		}
		return true, "", ""

	case protocol.ActionQueueClear:
		if !p.Playback.ClearUpcoming(&member.MemberId) {
			return false, "no_upcoming", "No upcoming tracks to clear"
		}
		return true, "", ""

	case protocol.ActionQueueMove:
		fromNum, okFrom := frame["fromIndex"].(float64)
		toNum, okTo := frame["toIndex"].(float64)
		if !okFrom || !okTo {
			return false, "missing_indices", "queueMove requires fromIndex and toIndex"
		}
		var videoId string
		if v, ok := frame["videoId"].(string); ok {
			videoId = v
		}
		if !p.Playback.MoveUpcoming(&member.MemberId, int(fromNum), int(toNum), videoId) {
			return false, "invalid_move", "Invalid queue move indices"
		}
		return true, "", ""

	case protocol.ActionNext:
		if !p.Playback.Step(&member.MemberId, 1, &member.DisplayName) {
			return false, "end_of_queue", "Already at the end of the queue."
		}
		return true, "", ""

	case protocol.ActionPrevious:
		if !p.Playback.Step(&member.MemberId, -1, &member.DisplayName) {
			return false, "start_of_queue", "Already at the beginning of the queue."
		}
		return true, "", ""

	case protocol.ActionSetMaxMembers:
		value, ok := frame["maxMembers"].(float64)
		if !ok { return false, "invalid_capacity", "Choose a party size between 2 and 10." }
		if err := p.SetMaxMembers(member, int(value)); err != nil {
			pe := err.(*party.PartyError)
			return false, pe.Code, pe.Message
		}
		return true, "", ""

	case protocol.ActionSetListening:
		enabled, ok := frame["enabled"].(bool)
		if !ok {
			return false, "bad_request", "enabled must be true or false."
		}
		member.Silent = !enabled
		return true, "", ""

	case protocol.ActionSetAutoplay:
		enabled, ok := frame["enabled"].(bool)
		if !ok {
			return false, "invalid_autoplay", "AutoPlay must be enabled or disabled."
		}
		p.Playback.SetAutoplay(&member.MemberId, enabled)
		return true, "", ""

	case protocol.ActionSetHostOnlyControl:
		enabled, ok := frame["enabled"].(bool)
		if !ok {
			return false, "invalid_control_policy", "Host-only control must be enabled or disabled."
		}
		if err := p.SetHostOnlyControl(member, enabled); err != nil {
			pe := err.(*party.PartyError)
			return false, pe.Code, pe.Message
		}
		return true, "", ""

	case protocol.ActionKick:
		targetID, _ := frame["memberId"].(string)
		if !member.IsHost { return false, "host_only", "Only the host can remove listeners." }
		if targetID == "" || targetID == member.MemberId { return false, "invalid_member", "Choose another listener to remove." }
		if target, found := p.Members[targetID]; found && target.PersonId == member.PersonId {
			return false, "invalid_member", "Choose another listener to remove."
		}
		// A person leaves with all of their devices.
		removed := p.RemovePerson(targetID)
		if len(removed) == 0 { return false, "not_found", "That listener is no longer in this party." }
		// Removed for good, devices and all: see party.Ban.
		p.Ban(removed)
		for _, gone := range removed {
			hubInst.Send(p.Code, gone.MemberId, map[string]interface{}{
				"type":    protocol.FrameBye,
				"reason":  "kicked",
				"message": "The host removed you from this party.",
			})
			hubInst.CloseMember(p.Code, gone.MemberId)
		}
		return true, "", ""

	default:
		return false, "unknown_action", fmt.Sprintf("Unknown control action '%s'", action)
	}
}

// Frame builders

func stateFrame(p *party.Party) map[string]interface{} {
	return map[string]interface{}{
		"type":     protocol.FrameState,
		"playback": p.Playback.ToWire(clock.NowMs()),
		"serverMs": clock.NowMs(),
	}
}

func activityFrame(member *party.Member, action string, frame map[string]interface{}) map[string]interface{} {
	detail := action
	trackTitle := func(raw interface{}) string {
		track, _ := raw.(map[string]interface{})
		title, _ := track["title"].(string)
		return title
	}
	switch action {
	case protocol.ActionSetTrack:
		if title := trackTitle(frame["track"]); title != "" { detail = "Changed the song to \"" + title + "\"" }
	case protocol.ActionQueueAdd:
		if tracks, ok := frame["tracks"].([]interface{}); ok && len(tracks) > 0 {
			title := trackTitle(tracks[0])
			if title != "" {
				if len(tracks) == 1 { detail = "Added \"" + title + "\" to the queue" } else { detail = fmt.Sprintf("Added \"%s\" and %d more to the queue", title, len(tracks)-1) }
			}
		}
	case protocol.ActionSetQueue:
		if tracks, ok := frame["queue"].([]interface{}); ok { detail = fmt.Sprintf("Replaced the queue with %d tracks", len(tracks)) }
	case protocol.ActionQueueRemove: detail = "Removed a track from the queue"
	case protocol.ActionQueueClear: detail = "Cleared upcoming tracks"
	case protocol.ActionQueueMove: detail = "Reordered the upcoming queue"
	case protocol.ActionNext: detail = "Skipped to the next song"
	case protocol.ActionPrevious: detail = "Went back to the previous song"
	case protocol.ActionPlay: detail = "Started playback"
	case protocol.ActionPause: detail = "Paused playback"
	case protocol.ActionSeek: detail = "Changed the playback position"
	case protocol.ActionKick: detail = "Removed a listener from the party"
	case protocol.ActionSetMaxMembers: detail = "Changed the party size"
	case protocol.ActionSetAutoplay: detail = "Changed AutoPlay"
	case protocol.ActionSetListening:
		if enabled, ok := frame["enabled"].(bool); ok && !enabled {
			detail = "Switched to listening through the host"
		} else {
			detail = "Switched to listening on their own device"
		}
	}
	return map[string]interface{}{"type": protocol.FrameActivity, "action": action, "by": member.DisplayName, "detail": detail, "atMs": clock.NowMs()}
}

func queueFrame(p *party.Party) map[string]interface{} {
	return map[string]interface{}{
		"type":     protocol.FrameQueue,
		"queue":    p.Playback.QueueToWire(),
		"serverMs": clock.NowMs(),
	}
}

func membersFrame(p *party.Party) map[string]interface{} {
	membersList := make([]map[string]interface{}, 0, len(p.Members))
	for _, m := range p.Members {
		membersList = append(membersList, m.ToWire())
	}
	return map[string]interface{}{
		"type":            protocol.FrameMembers,
		"members":         membersList,
		"maxMembers":      p.MaxMembers,
		"hostOnlyControl": p.HostOnlyControl,
		"serverMs":        clock.NowMs(),
	}
}

// Background Heartbeat Ticker

// startPresenceSweeper refreshes the live counts. A pass over 10k installs is
// well under a millisecond, and stats reads never wait on it.
func startPresenceSweeper() {
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()
	for range ticker.C {
		presenceTracker.Sweep(clock.NowMs())
	}
}

func startHeartbeatTicker() {
	interval := time.Duration(config.StateHeartbeatMs) * time.Millisecond
	if interval < 1*time.Second {
		interval = 1 * time.Second
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for range ticker.C {
		now := clock.NowMs()
		for _, p := range store.All() {
			if len(hubInst.MembersOnline(p.Code)) > 0 {
				p.Lock()
				sf := stateFrame(p)
				p.Unlock()
				hubInst.Broadcast(p.Code, sf, "")
			}
		}

		changed := store.Sweep(now)
		for _, p := range changed {
			p.Lock()
			mf := membersFrame(p)
			p.Unlock()
			hubInst.Broadcast(p.Code, mf, "")
		}

		dropOrphanRooms()
	}
}

// dropOrphanRooms closes the sockets of rooms that belong to no live party, which is what is
// left when a party was swept while somebody still held a socket to it.
//
// Account rooms (see account_http.go) share the hub but are not parties, so they never are in the
// store; they have their own lifetime and are skipped here. Closing them every heartbeat was a
// real bug: every device's account socket dropped and reconnected every five seconds.
func dropOrphanRooms() {
	live := make(map[string]bool)
	for _, lp := range store.All() {
		live[lp.Code] = true
	}
	for _, code := range hubInst.ActiveCodes() {
		if strings.HasPrefix(code, accountRoomPrefix) {
			continue
		}
		if !live[code] {
			hubInst.DropParty(code)
		}
	}
}

// dropReplacedSockets closes the sockets of members that were replaced by a join (a device that
// came back as another account) and tells the rest of the party who is left.
func dropReplacedSockets(code string, replaced []string, members map[string]interface{}) {
	if len(replaced) == 0 {
		return
	}
	for _, id := range replaced {
		hubInst.Send(code, id, map[string]interface{}{
			"type": protocol.FrameBye, "reason": "replaced", "message": "This device joined as someone else.",
		})
		hubInst.CloseMember(code, id)
	}
	if members != nil {
		hubInst.Broadcast(code, members, "")
	}
}
