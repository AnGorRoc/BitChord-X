package telemetry

import (
	"crypto/subtle"
	_ "embed"
	"encoding/json"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

//go:embed dashboard.html
var dashboardHTML []byte

//go:embed logo.png
var logoPNG []byte

// NewAdminHandler builds the HTTP handler intended for the private LAN/Tailscale
// port (ADMIN_PORT, default 8001). When adminSecret is empty, no password is
// required; when set, HTTP Basic Auth or Bearer token authentication is enforced.
// extra adds routes from other features (feedback); they get the same authentication and CSRF
// check as the rest.
func NewAdminHandler(store *Store, liveHub *LiveHub, adminSecret string, extra ...func(*http.ServeMux)) http.Handler {
	mux := http.NewServeMux()
	for _, register := range extra {
		register(mux)
	}

	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"ok":              true,
			"databaseEnabled": store.DatabaseEnabled(),
		})
	})

	// Multi-page SPA routes (all serve dashboard.html and deep-link client-side)
	mux.HandleFunc("GET /{$}", serveDashboard)
	mux.HandleFunc("GET /admin", serveDashboard)
	mux.HandleFunc("GET /admin/{$}", serveDashboard)
	mux.HandleFunc("GET /admin/overview", serveDashboard)
	mux.HandleFunc("GET /admin/errors", serveDashboard)
	mux.HandleFunc("GET /admin/installs", serveDashboard)
	mux.HandleFunc("GET /admin/parties", serveDashboard)
	mux.HandleFunc("GET /admin/ai", serveDashboard)
	mux.HandleFunc("GET /admin/system", serveDashboard)
	mux.HandleFunc("GET /admin/feedback", serveDashboard)
	mux.HandleFunc("GET /admin/feedback/{id}", serveDashboard)
	mux.HandleFunc("GET /logo.png", serveLogo)
	mux.HandleFunc("GET /favicon.ico", serveLogo)

	// Real-time WebSocket stream for live dashboard updates
	mux.HandleFunc("GET /ws/admin/live", func(w http.ResponseWriter, r *http.Request) {
		if liveHub == nil {
			http.Error(w, "live hub disabled", http.StatusServiceUnavailable)
			return
		}
		liveHub.ServeWS(w, r, store.Live())
	})

	// AI-friendly discovery and direct Markdown/JSON endpoints
	mux.HandleFunc("GET /llms.txt", serveLLMsTxt)
	mux.HandleFunc("GET /api/admin/ai/schema", serveAISchema)
	mux.HandleFunc("GET /api/admin/ai/brief", func(w http.ResponseWriter, r *http.Request) {
		rangeStr := r.URL.Query().Get("range")
		if wantsMarkdown(r, "md") {
			md, err := store.FormatOverviewMarkdown(r.Context(), rangeStr)
			if err != nil {
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}
			writeMarkdown(w, http.StatusOK, md)
			return
		}
		data, err := store.Overview(r.Context(), rangeStr)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, data)
	})

	mux.HandleFunc("GET /api/admin/ai/errors", func(w http.ResponseWriter, r *http.Request) {
		filter := parseErrorFilter(r)
		if filter.Limit <= 0 {
			filter.Limit = 20
		}
		if wantsMarkdown(r, "md") {
			md, err := store.FormatErrorsMarkdown(r.Context(), filter)
			if err != nil {
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}
			writeMarkdown(w, http.StatusOK, md)
			return
		}
		res, err := store.ListErrors(r.Context(), filter)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, res)
	})

	// Core JSON endpoints
	mux.HandleFunc("GET /api/admin/overview", func(w http.ResponseWriter, r *http.Request) {
		rangeStr := r.URL.Query().Get("range")
		if wantsMarkdown(r, "json") {
			md, err := store.FormatOverviewMarkdown(r.Context(), rangeStr)
			if err != nil {
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}
			writeMarkdown(w, http.StatusOK, md)
			return
		}
		data, err := store.Overview(r.Context(), rangeStr)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, data)
	})

	mux.HandleFunc("GET /api/admin/parties", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, store.Live())
	})

	mux.HandleFunc("GET /api/admin/installs", func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		limit, _ := strconv.Atoi(q.Get("limit"))
		offset, _ := strconv.Atoi(q.Get("offset"))
		res, err := store.ListInstalls(r.Context(), InstallFilter{
			Platform: strings.TrimSpace(q.Get("platform")),
			Version:  strings.TrimSpace(q.Get("version")),
			Status:   strings.TrimSpace(q.Get("status")),
			Limit:    limit,
			Offset:   offset,
		})
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, res)
	})

	mux.HandleFunc("GET /api/admin/errors", func(w http.ResponseWriter, r *http.Request) {
		filter := parseErrorFilter(r)
		if wantsMarkdown(r, "json") {
			md, err := store.FormatErrorsMarkdown(r.Context(), filter)
			if err != nil {
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}
			writeMarkdown(w, http.StatusOK, md)
			return
		}
		res, err := store.ListErrors(r.Context(), filter)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, res)
	})

	mux.HandleFunc("GET /api/admin/errors/{id}", func(w http.ResponseWriter, r *http.Request) {
		id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
		if err != nil || id <= 0 {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid id"})
			return
		}
		rep, found, err := store.GetError(r.Context(), id)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		if !found {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": "error report not found"})
			return
		}
		if wantsMarkdown(r, "json") {
			writeMarkdown(w, http.StatusOK, FormatErrorDetailMarkdown(rep))
			return
		}
		writeJSON(w, http.StatusOK, rep)
	})

	mux.HandleFunc("POST /api/admin/errors/{id}/resolve", func(w http.ResponseWriter, r *http.Request) {
		id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
		if err != nil || id <= 0 {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid id"})
			return
		}
		var body struct {
			Resolved bool `json:"resolved"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			body.Resolved = true
		}
		if err := store.SetErrorResolved(r.Context(), id, body.Resolved); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		if liveHub != nil {
			liveHub.Notify("error_updated", map[string]interface{}{"id": id, "resolved": body.Resolved})
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"ok": true, "id": id, "resolved": body.Resolved})
	})

	mux.HandleFunc("POST /api/admin/errors/resolve-type", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			ErrorType string `json:"errorType"`
			Resolved  bool   `json:"resolved"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil || strings.TrimSpace(body.ErrorType) == "" {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "errorType is required"})
			return
		}
		affected, err := store.BulkResolveByType(r.Context(), strings.TrimSpace(body.ErrorType), body.Resolved)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		if liveHub != nil {
			liveHub.Notify("error_updated", map[string]interface{}{"errorType": body.ErrorType, "resolved": body.Resolved})
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"ok": true, "affected": affected})
	})

	mux.HandleFunc("DELETE /api/admin/errors/{id}", func(w http.ResponseWriter, r *http.Request) {
		id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
		if err != nil || id <= 0 {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid id"})
			return
		}
		if err := store.DeleteError(r.Context(), id); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		if liveHub != nil {
			liveHub.Notify("error_deleted", map[string]interface{}{"id": id})
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"ok": true, "id": id})
	})

	mux.HandleFunc("GET /api/admin/export.csv", func(w http.ResponseWriter, r *http.Request) {
		data, err := store.ExportErrorsCSV(r.Context())
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "text/csv; charset=utf-8")
		w.Header().Set("Content-Disposition", `attachment; filename="bitchord-errors.csv"`)
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(data)
	})

	// Database storage & maintenance
	mux.HandleFunc("GET /api/admin/database", func(w http.ResponseWriter, r *http.Request) {
		stats, err := store.GetDatabaseStats(r.Context())
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, stats)
	})

	mux.HandleFunc("POST /api/admin/database/prune-errors", func(w http.ResponseWriter, r *http.Request) {
		deleted, err := store.PruneResolvedErrors(r.Context())
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		if liveHub != nil {
			liveHub.Notify("error_deleted", map[string]interface{}{"deleted": deleted})
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"ok": true, "deleted": deleted})
	})

	mux.HandleFunc("POST /api/admin/database/prune-history", func(w http.ResponseWriter, r *http.Request) {
		deleted, err := store.PruneOldHistory(r.Context(), 30)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		if liveHub != nil {
			liveHub.Notify("snapshot", store.Live())
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"ok": true, "deleted": deleted})
	})

	mux.HandleFunc("POST /api/admin/database/reset", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Confirm string `json:"confirm"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Confirm != "RESET" {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "confirmation 'RESET' required"})
			return
		}
		if err := store.ResetDatabase(r.Context()); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		if liveHub != nil {
			liveHub.Notify("snapshot", store.Live())
		}
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	})

	return adminSecurityHeadersMiddleware(authMiddleware(adminSecret, csrfMiddleware(mux)))
}

// csrfMiddleware refuses state-changing requests that a browser made on behalf of another site.
// The dashboard authenticates with HTTP Basic, which the browser attaches by itself to any
// request to this host, so without this a page elsewhere could post a form to
// /api/admin/database/reset while the developer has the dashboard open. Browsers say where a
// request comes from in Sec-Fetch-Site (and Origin); scripts and curl send neither and are not
// a CSRF vector, so they pass.
func csrfMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodGet, http.MethodHead, http.MethodOptions:
			next.ServeHTTP(w, r)
			return
		}
		if site := r.Header.Get("Sec-Fetch-Site"); site != "" && site != "same-origin" && site != "none" {
			http.Error(w, "cross-site request refused", http.StatusForbidden)
			return
		}
		if origin := r.Header.Get("Origin"); origin != "" {
			u, err := url.Parse(origin)
			if err != nil || !strings.EqualFold(u.Host, r.Host) {
				http.Error(w, "cross-site request refused", http.StatusForbidden)
				return
			}
		}
		next.ServeHTTP(w, r)
	})
}

func parseErrorFilter(r *http.Request) ErrorFilter {
	q := r.URL.Query()
	limit, _ := strconv.Atoi(q.Get("limit"))
	offset, _ := strconv.Atoi(q.Get("offset"))
	return ErrorFilter{
		Version:        strings.TrimSpace(q.Get("version")),
		Platform:       strings.TrimSpace(q.Get("platform")),
		ErrorType:      strings.TrimSpace(q.Get("errorType")),
		UnresolvedOnly: q.Get("unresolved") == "true" || q.Get("unresolved") == "1",
		Search:         strings.TrimSpace(q.Get("search")),
		Limit:          limit,
		Offset:         offset,
	}
}

func wantsMarkdown(r *http.Request, defaultFmt string) bool {
	if f := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("format"))); f != "" {
		return f == "md" || f == "markdown" || f == "text"
	}
	if strings.Contains(strings.ToLower(r.Header.Get("Accept")), "text/markdown") {
		return true
	}
	return defaultFmt == "md"
}

func serveDashboard(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(dashboardHTML)
}

func serveLogo(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "public, max-age=86400")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(logoPNG)
}

func serveLLMsTxt(w http.ResponseWriter, r *http.Request) {
	const txt = `# BitChord-X Telemetry & Operations API (Private LAN/Tailscale)
> Direct, browser-free endpoints for AI agents, scripts, and CLI tools.
> Add ?format=md or ?format=json on supported endpoints.

## Quick Endpoints
- GET /api/admin/ai/brief?range=7d          : Executive Markdown brief (live users, retention, session durations, rotation, versions, top errors).
- GET /api/admin/ai/errors?unresolved=true  : Unresolved error reports with stacktraces and device info in Markdown.
- GET /api/admin/ai/schema                  : Machine-readable JSON schema of all admin endpoints.
- GET /api/admin/overview?range=24h|7d|30d  : Full JSON metrics including sessions (real durations), rotation (hours per install, next-hour overlap) and hourly active users.
- GET /api/admin/errors/{id}?format=md      : Single error report detail with full stacktrace.
- GET /api/admin/installs?status=lost|active24h|returning : Anonymized install list and session counts.
- GET /api/admin/parties                    : Live Listen Together party and socket counts.
- POST /api/admin/errors/{id}/resolve       : Body {"resolved": true|false} to mark an error resolved.
- POST /api/admin/errors/resolve-type       : Body {"errorType": "...", "resolved": true} to bulk-resolve.
- GET /api/admin/export.csv                 : CSV export of error reports.
- GET /api/admin/ai/feedback?status=open    : Open bug reports and ideas from the apps, in Markdown (format=json for JSON).
- GET /api/admin/feedback/tickets/{id}?format=md : One feedback ticket with its whole thread.
- WS  /ws/admin/live                        : Real-time WebSocket stream (live, error, error_updated, error_deleted, feedback).
`
	writeMarkdown(w, http.StatusOK, txt)
}

func serveAISchema(w http.ResponseWriter, r *http.Request) {
	schema := map[string]interface{}{
		"service":     "bitchord-x-telemetry",
		"description": "Private LAN/Tailscale telemetry API designed for both human dashboards and direct AI agent queries without a browser.",
		"formats":     []string{"json", "md"},
		"websocket":   "/ws/admin/live",
		"endpoints": []map[string]interface{}{
			{"method": "GET", "path": "/llms.txt", "description": "Plain-text AI discovery index"},
			{"method": "GET", "path": "/api/admin/ai/brief", "query": []string{"range=24h|7d|30d", "format=md|json"}, "description": "Concise summary of live users, retention, session durations, rotation, versions, and top errors"},
			{"method": "GET", "path": "/api/admin/ai/errors", "query": []string{"unresolved=true|false", "version", "platform", "errorType", "limit", "format=md|json"}, "description": "Filtered error reports with device info and stacktraces"},
			{"method": "GET", "path": "/api/admin/overview", "query": []string{"range=24h|7d|30d", "format=json|md"}, "description": "Full KPIs, concurrency series, hourly connections with active users, real session durations, rotation metrics, versions, platforms, top errors"},
			{"method": "GET", "path": "/api/admin/errors", "query": []string{"version", "platform", "errorType", "unresolved", "search", "limit", "offset", "format=json|md"}, "description": "Paginated error list"},
			{"method": "GET", "path": "/api/admin/errors/{id}", "query": []string{"format=json|md"}, "description": "Single error detail"},
			{"method": "POST", "path": "/api/admin/errors/{id}/resolve", "body": map[string]bool{"resolved": true}, "description": "Resolve or reopen an error"},
			{"method": "POST", "path": "/api/admin/errors/resolve-type", "body": map[string]interface{}{"errorType": "string", "resolved": true}, "description": "Bulk resolve/reopen by errorType"},
			{"method": "DELETE", "path": "/api/admin/errors/{id}", "description": "Delete an error report"},
			{"method": "GET", "path": "/api/admin/installs", "query": []string{"platform", "version", "status=all|active24h|returning|lost", "limit", "offset"}, "description": "Anonymized install directory"},
			{"method": "GET", "path": "/api/admin/parties", "description": "Live Listen Together counts"},
			{"method": "GET", "path": "/api/admin/export.csv", "description": "CSV export of error reports"},
		},
	}
	writeJSON(w, http.StatusOK, schema)
}

type adminAuthLimiter struct {
	mu      sync.Mutex
	entries map[string]adminFailEntry
}

type adminFailEntry struct {
	started time.Time
	count   int
}

func newAdminAuthLimiter() *adminAuthLimiter {
	return &adminAuthLimiter{entries: make(map[string]adminFailEntry)}
}

func (l *adminAuthLimiter) isBlocked(ip string) bool {
	now := time.Now()
	l.mu.Lock()
	defer l.mu.Unlock()
	e, ok := l.entries[ip]
	if !ok || now.Sub(e.started) >= time.Minute {
		return false
	}
	return e.count >= 10
}

func (l *adminAuthLimiter) recordFailure(ip string) {
	now := time.Now()
	l.mu.Lock()
	defer l.mu.Unlock()
	if len(l.entries) > 1000 {
		for k, v := range l.entries {
			if now.Sub(v.started) >= time.Minute {
				delete(l.entries, k)
			}
		}
	}
	e := l.entries[ip]
	if e.started.IsZero() || now.Sub(e.started) >= time.Minute {
		l.entries[ip] = adminFailEntry{started: now, count: 1}
		return
	}
	e.count++
	l.entries[ip] = e
}

func adminSecurityHeadersMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "no-referrer")
		next.ServeHTTP(w, r)
	})
}

func authMiddleware(secret string, next http.Handler) http.Handler {
	if secret == "" {
		return next
	}
	limiter := newAdminAuthLimiter()
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/healthz" {
			next.ServeHTTP(w, r)
			return
		}
		ip, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil {
			ip = r.RemoteAddr
		}
		if limiter.isBlocked(ip) {
			http.Error(w, "Too Many Requests", http.StatusTooManyRequests)
			return
		}
		if auth := r.Header.Get("Authorization"); strings.HasPrefix(auth, "Bearer ") {
			token := strings.TrimPrefix(auth, "Bearer ")
			if subtle.ConstantTimeCompare([]byte(token), []byte(secret)) == 1 {
				next.ServeHTTP(w, r)
				return
			}
		}
		if _, pass, ok := r.BasicAuth(); ok {
			if subtle.ConstantTimeCompare([]byte(pass), []byte(secret)) == 1 {
				next.ServeHTTP(w, r)
				return
			}
		}
		limiter.recordFailure(ip)
		w.Header().Set("WWW-Authenticate", `Basic realm="BitChord Telemetry"`)
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
	})
}

func writeJSON(w http.ResponseWriter, status int, payload interface{}) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func writeMarkdown(w http.ResponseWriter, status int, body string) {
	w.Header().Set("Content-Type", "text/markdown; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(body))
}
